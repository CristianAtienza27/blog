#!/usr/bin/env node
/**
 * Pruebas de fallos y reinicios de la fase 6.2.7.4, solo sobre el proyecto cms-bench:
 *
 *   node infra/bench/scripts/fallos.mjs <R1|R3|R4|R5|R6a|R6b|R7|R8|R9> [repeticiones]
 *
 *   R1   SIGKILL de la API con carga mixta: reinicio, sesiones, auditoría (R2) y coherencia
 *   R3   SIGKILL de la API con el bloqueo por usuario tomado: bloqueos liberados, recuperación
 *   R4   SIGKILL de la herramienta de seguridad esperando el bloqueo (determinista): nada cambia
 *   R5   contadores de intentos frente a la muerte de la API y al reinicio de PostgreSQL
 *   R6a  reinicio de PostgreSQL (docker restart) con carga · R6b: SIGKILL del postmaster (WAL)
 *   R7   integridad: pg_amcheck (superusuario, verificación excepcional, K2) y comparación
 *   R8   OOM de la API con 384 MiB SOLO en este contenedor (K3), y vuelta a 768 MiB
 *   R9   tareas programadas tras reiniciar (purgas y agregación CSRF, pérdida documentada E5)
 *
 * Fallos «como el OOM killer»: SIGKILL desde el espacio de procesos del equipo (no `docker kill`,
 * que Docker trata como parada manual). Política de reinicio unless-stopped (BENCH_RESTART).
 * Consultas con logins de permisos mínimos y la contraseña leída dentro del contenedor.
 * Resultados en <BENCH_ROOT>/resultados/<fecha>-6274-<prueba>/ (fuera del repositorio).
 */
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(import.meta.dirname, '../../..');
const ROOT = path.resolve(process.env.BENCH_ROOT ?? path.join(REPO, '../blog-bench'));
const PG_IMAGE =
  'postgres:18.6-trixie@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const [test, repsArg] = process.argv.slice(2);
const API_MEM = process.env.API_MEM ?? '768m';
const env = {
  ...process.env,
  BENCH_DB: process.env.BENCH_DB ?? 'v627',
  SUT_CPUS: process.env.SUT_CPUS ?? '0-3',
  API_MEM,
  API_IP_FAILURES: process.env.API_IP_FAILURES ?? '1000000',
  BENCH_RESTART: 'unless-stopped',
  BENCH_OUT: path.join(ROOT, 'resultados'),
  BENCH_IMAGES: path.join(ROOT, 'imagenes-prueba'),
  BENCH_CRED: path.join(ROOT, 'credenciales'),
  MSYS_NO_PATHCONV: '1',
};
const composeArgs = ['compose', '-f', 'infra/bench/compose.yaml', '--profile', 'tools'];

function run(cmd, args, { check = true, input, extraEnv = {} } = {}) {
  const res = spawnSync(cmd, args, {
    cwd: REPO,
    encoding: 'utf8',
    env: { ...env, ...extraEnv },
    input,
    stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
    maxBuffer: 256 * 1024 * 1024,
  });
  if (check && res.status !== 0)
    throw new Error(`${cmd} ${args.join(' ')} → ${res.status}\n${res.stderr}`);
  return res;
}
const compose = (args, o) => run('docker', [...composeArgs, ...args], o);
const cid = (service) =>
  compose(['ps', '-aq', service], { check: false }).stdout.trim().split('\n')[0] || null;
const bench = (...args) =>
  run(process.execPath, ['infra/bench/scripts/bench.mjs', ...args], { check: false });
const state = (service) =>
  JSON.parse(run('docker', ['inspect', '-f', '{{json .State}}', cid(service)]).stdout);
const restarts = (service) =>
  Number(run('docker', ['inspect', '-f', '{{.RestartCount}}', cid(service)]).stdout);
/** SIGKILL desde el espacio de procesos del equipo (como el OOM killer). */
const hostkill = (pid) =>
  run('docker', [
    'run',
    '--rm',
    '--pid=host',
    '--entrypoint',
    'sh',
    PG_IMAGE,
    '-c',
    `kill -9 ${pid}`,
  ]);
const hostPid = (service) =>
  Number(run('docker', ['inspect', '-f', '{{.State.Pid}}', cid(service)]).stdout);

/** Consulta con un login de permisos mínimos, contraseña leída del secreto montado en PostgreSQL. */
function psql(login, sql) {
  return run(
    'docker',
    [
      'exec',
      '-i',
      cid('postgres'),
      'sh',
      '-c',
      `PGPASSWORD="$(cat /run/secrets/${login}_password)" psql -h localhost -U ${login} -d cms -v ON_ERROR_STOP=1 -At -F '|'`,
    ],
    { input: sql },
  ).stdout.trim();
}
const one = (login, sql) => psql(login, sql).split('\n')[0];

async function waitReady(maxSeconds = 120) {
  const start = Date.now();
  for (;;) {
    const id = cid('api');
    const ok =
      id &&
      run(
        'docker',
        [
          'exec',
          id,
          'curl',
          '-fsS',
          '-o',
          '/dev/null',
          'http://localhost:8080/actuator/health/readiness',
        ],
        { check: false },
      ).status === 0;
    if (ok) return (Date.now() - start) / 1000;
    if (Date.now() - start > maxSeconds * 1000) throw new Error('la API no vuelve a estar lista');
    await sleep(500);
  }
}
async function waitPostgres(maxSeconds = 120) {
  const start = Date.now();
  for (;;) {
    if (
      run('docker', ['exec', cid('postgres'), 'pg_isready', '-d', 'cms'], { check: false })
        .status === 0
    ) {
      return (Date.now() - start) / 1000;
    }
    if (Date.now() - start > maxSeconds * 1000) throw new Error('PostgreSQL no vuelve');
    await sleep(500);
  }
}

function newOut(name) {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-');
  const dir = path.join(ROOT, 'resultados', `${stamp}-6274-${name}`);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}
const users = (tag) => {
  const id = `${tag}-${Date.now().toString(36)}`;
  return { L: `bench-f-${id}-l@example.test`, P: `bench-f-${id}-p@example.test` };
};
function createUsers(...emails) {
  const res = bench('admin', '--email', emails.join(','));
  if (res.status !== 0) throw new Error(`admin: ${res.stderr}`);
}
async function freshStack() {
  compose(['rm', '-sf', 'api'], { check: false });
  compose(['up', '-d', '--wait', '--force-recreate', 'postgres']);
  bench('reset');
  compose(['up', '-d', '--force-recreate', 'api']);
  return waitReady();
}

function startK6(name, script, k6env, out) {
  const e = Object.entries({ HTTP_TIMEOUT: '60s', ...k6env }).flatMap(([k, v]) => [
    '-e',
    `${k}=${v}`,
  ]);
  compose(
    [
      'run',
      '-d',
      '--name',
      name,
      ...e,
      'k6',
      'run',
      '--quiet',
      '--no-thresholds',
      `/scripts/${script}`,
    ],
    {
      extraEnv: { BENCH_OUT: out },
    },
  );
}
function collectK6(name, out) {
  run('docker', ['wait', name]);
  const logs = run('docker', ['logs', name], { check: false });
  run('docker', ['rm', name], { check: false });
  const text = `${logs.stdout}\n${logs.stderr}`;
  fs.writeFileSync(path.join(out, `${name}.log`), text);
  return text
    .split('\n')
    .map((l) => l.match(/EV (\{.*\})/))
    .filter(Boolean)
    .map((m) => JSON.parse(m[1].replaceAll('\\"', '"')));
}

// --- Clasificación y coherencia (R1/R2, R6 y ajustes 1, 2 y 4) ---------------------------------

const userId = (email) =>
  one('cms_security_tool', `SELECT id FROM app_user WHERE email = '${email}';`);

/**
 * Para cada operación auditada: completadas (respuesta de éxito con su correlación), completadas sin
 * éxito, interrumpidas (sin respuesta: la API murió o PostgreSQL cayó con la petición en curso) y,
 * en la auditoría, sus eventos por correlación. Una interrupción NO es una duplicación: se clasifica.
 */
function classify(events, ops) {
  const out = {};
  for (const op of ops) {
    const reqs = events.filter((e) => op.kinds.includes(e.k));
    const ok = reqs.filter((e) => e.http === op.ok);
    const other = reqs.filter((e) => e.http > 0 && e.http !== op.ok);
    const interrupted = reqs.filter((e) => !e.http);
    const rows = psql(
      'cms_security_tool',
      `SELECT details->>'correlationId' FROM audit_event
      WHERE event = '${op.event}' AND user_id = '${op.user}' AND details->>'correlationId' IS NOT NULL;`,
    )
      .split('\n')
      .filter(Boolean);
    const counts = rows.reduce((m, c) => m.set(c, (m.get(c) ?? 0) + 1), new Map());
    const responded = new Set(reqs.filter((e) => e.cid).map((e) => e.cid));
    const okCids = new Set(ok.map((e) => e.cid));
    const otherCids = new Set(other.map((e) => e.cid));
    const audited = [...counts.keys()];
    out[op.event] = {
      peticiones: reqs.length,
      completadasConExito: ok.length,
      completadasSinExito: other.length,
      interrumpidas: interrupted.length,
      eventos: rows.length,
      duplicados: [...counts.values()].filter((n) => n > 1).length,
      exitoSinAuditoria: [...okCids].filter((c) => !counts.has(c)).length,
      auditadasYDespuesFallidas: audited.filter((c) => otherCids.has(c)).length,
      auditadasConResultadoPerdido: audited.filter((c) => !responded.has(c)).length,
      nuncaAuditadas: Math.max(
        0,
        interrupted.length - audited.filter((c) => !responded.has(c)).length,
      ),
    };
    out[op.event].correcto =
      out[op.event].duplicados === 0 &&
      out[op.event].exitoSinAuditoria === 0 &&
      out[op.event].auditadasConResultadoPerdido <= interrupted.length;
  }
  return out;
}

/** Coherencia de la auditoría con las operaciones confirmadas y de los challenges (ajustes 2 y 4). */
function consistency(L, P) {
  const ids = `'${L}', '${P}'`;
  const sec = (sql) => Number(one('cms_security_tool', sql));
  const api = (sql) => Number(one('cms_api', sql));
  const r = {
    eventosDeLaApiSinSuIp: sec(`SELECT count(*) FROM audit_event e WHERE e.user_id IN (${ids})
      AND e.details->>'correlationId' IS NOT NULL AND NOT EXISTS (SELECT 1 FROM audit_event_ip i WHERE i.event_id = e.id);`),
    eventosDeUsuariosInexistentes:
      sec(`SELECT count(*) FROM audit_event e WHERE e.user_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = e.user_id);`),
    stepUpAuditados: sec(
      `SELECT count(*) FROM audit_event WHERE user_id = '${P}' AND event = 'step_up.succeeded';`,
    ),
    stepUpAceptadosEnElContador: api(`SELECT count(*) FROM webauthn_assertion WHERE user_id = '${P}'
      AND ceremony = 'step_up' AND outcome = 'accepted';`),
    accesosFAuditados: sec(
      `SELECT count(*) FROM audit_event WHERE user_id = '${P}' AND event = 'login.passkey_succeeded';`,
    ),
    accesosFAceptadosEnElContador:
      api(`SELECT count(*) FROM webauthn_assertion WHERE user_id = '${P}'
      AND ceremony = 'authenticate' AND outcome = 'accepted';`),
    dispositivosAuditados: sec(
      `SELECT count(*) FROM audit_event WHERE user_id = '${P}' AND event = 'device.token_issued';`,
    ),
    dispositivosEnLaTabla: sec(`SELECT count(*) FROM trusted_device WHERE user_id = '${P}';`),
    // Un challenge consumido (con su aserción verificada) nunca vuelve a estar disponible.
    challengesConsumidosDisponibles: api(`SELECT count(*) FROM webauthn_challenge c
      JOIN webauthn_assertion a ON a.challenge_id = c.id;`),
  };
  r.correcto =
    r.eventosDeLaApiSinSuIp === 0 &&
    r.eventosDeUsuariosInexistentes === 0 &&
    r.stepUpAceptadosEnElContador >= r.stepUpAuditados &&
    r.accesosFAceptadosEnElContador >= r.accesosFAuditados &&
    r.dispositivosEnLaTabla >= r.dispositivosAuditados &&
    r.challengesConsumidosDisponibles === 0;
  return r;
}

/** ¿Sobreviven las sesiones? Usuarios de «me» y de step-up que siguen sin volver a iniciar sesión. */
function sessions(events, failTs) {
  // «Vuelve a iniciar sesión» solo quien YA tenía sesión antes del fallo (no quien aún no la tenía).
  const count = (okKind, okStatus, loginKind) => {
    const vus = (f) => new Set(events.filter(f).map((e) => e.vu));
    const had = vus((e) => e.k === okKind && e.http === okStatus && e.ts < failTs);
    const relogged = vus((e) => e.k === loginKind && e.ts > failTs && had.has(e.vu));
    const okAfter = vus((e) => e.k === okKind && e.http === okStatus && e.ts > failTs);
    return {
      conSesionAntesDelFallo: had.size,
      conLaMismaSesionDespues: [...had].filter((v) => okAfter.has(v) && !relogged.has(v)).length,
      volvieronAIniciarSesion: relogged.size,
    };
  };
  return { me: count('me', 200, 'me_login'), stepup: count('stepup', 204, 'stepup_login') };
}

function timeline(events, failTs) {
  const probes = events.filter((e) => e.k === 'readiness').sort((a, b) => a.ts - b.ts);
  const down = probes.find((e) => e.ts >= failTs - 1000 && e.http !== 200);
  const up = down && probes.find((e) => e.ts > down.ts && e.http === 200);
  const liveFail = events.filter((e) => e.k === 'liveness' && e.http !== 200 && e.ts >= failTs);
  const byStatus = {};
  for (const e of events.filter(
    (x) => x.ts >= failTs && !['readiness', 'liveness'].includes(x.k),
  )) {
    const key = e.http
      ? `${e.http}${e.codigo ? `_${e.codigo}` : ''}`
      : `sin_respuesta${e.error ? `_${e.error}` : ''}`;
    byStatus[key] = (byStatus[key] ?? 0) + 1;
  }
  return {
    readinessNoListaSegundos: down ? Math.round((down.ts - failTs) / 100) / 10 : null,
    readinessDeNuevoListaSegundos: up ? Math.round((up.ts - failTs) / 100) / 10 : null,
    livenessSinRespuestaTrasElFallo: liveFail.length,
    estadosTrasElFallo: byStatus,
  };
}

const OPS = (L, P) => [
  { event: 'login.password_succeeded', user: L, kinds: ['login', 'me_login'], ok: 200 },
  { event: 'login.password_succeeded', user: P, kinds: ['stepup_login', 'setup_login'], ok: 200 },
  { event: 'step_up.succeeded', user: P, kinds: ['stepup'], ok: 204 },
  { event: 'login.passkey_succeeded', user: P, kinds: ['f'], ok: 200 },
];
// Dos operaciones con el mismo evento (L y P): se distinguen por usuario.
function classifyAll(events, L, P) {
  const result = {};
  for (const op of OPS(L, P))
    Object.assign(result, {
      [`${op.event} (${op.user === L ? 'L' : 'P'})`]: classify(events, [op])[op.event],
    });
  return result;
}

// --- Pruebas --------------------------------------------------------------------------------

async function mixedFailure(name, fail, { failAt = 45, seconds = 150, k6env = {} } = {}) {
  const out = newOut(name);
  const u = users(name);
  const ready = await freshStack();
  createUsers(u.L, u.P);
  const [L, P] = [userId(u.L), userId(u.P)];
  const k6 = `fallos-${name}-${Date.now()}`;
  startK6(
    k6,
    'fallos.js',
    { EMAIL: u.L, PASSKEY_EMAIL: u.P, DURATION: `${seconds}s`, ...k6env },
    out,
  );
  await sleep(failAt * 1000);
  const failTs = Date.now();
  const detail = await fail();
  const events = collectK6(k6, out);
  // Registro de la API desde el arranque de la prueba (sin secretos): errores y clases de excepción.
  const apiLog = run(
    'docker',
    ['logs', '--since', new Date(failTs - failAt * 1000 - 5000).toISOString(), cid('api')],
    { check: false },
  );
  const apiText = `${apiLog.stdout}\n${apiLog.stderr}`;
  fs.writeFileSync(path.join(out, 'api.log'), apiText);
  const notable = new Map();
  for (const line of apiText.split('\n')) {
    const m = line.match(/ (ERROR|WARN) .*? : (.*)$/);
    if (m)
      notable.set(
        `${m[1]} ${m[2].slice(0, 150)}`,
        (notable.get(`${m[1]} ${m[2].slice(0, 150)}`) ?? 0) + 1,
      );
    if (/OutOfMemoryError|Terminating due to/.test(line))
      notable.set(line.slice(0, 200), (notable.get(line.slice(0, 200)) ?? 0) + 1);
  }
  const result = {
    prueba: name,
    registroApi: Object.fromEntries([...notable.entries()].slice(0, 20)),
    apiListaInicialSegundos: ready,
    fallo: detail,
    reiniciosApi: restarts('api'),
    reiniciosPostgres: restarts('postgres'),
    linea: timeline(events, failTs),
    sesiones: sessions(events, failTs),
    auditoria: classifyAll(events, L, P),
    coherencia: consistency(L, P),
  };
  // Criterio 6 de la 6.2.7.4: ningún 5xx salvo 503 «unavailable» después del fallo.
  result.cincoXXDistintosDe503 = Object.entries(result.linea.estadosTrasElFallo)
    .filter(([k]) => /^5\d\d/.test(k) && k !== '503_unavailable')
    .reduce((n, [, v]) => n + v, 0);
  result.correcto =
    Object.values(result.auditoria).every((x) => x.correcto) &&
    result.coherencia.correcto &&
    result.cincoXXDistintosDe503 === 0;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`${name} ${JSON.stringify(result)}`);
  return result;
}

const killApi = async () => {
  const pid = hostPid('api');
  hostkill(pid);
  const back = await waitReady();
  return {
    tipo: 'SIGKILL a la JVM de la API',
    listaDeNuevoSegundos: back,
    estado: state('api').Status,
  };
};

async function R1() {
  const reps = Number(repsArg ?? 3);
  const all = [];
  for (let r = 1; r <= reps; r++) all.push(await mixedFailure(`R1-r${r}`, killApi));
  return all;
}

async function R3() {
  const out = newOut('R3');
  const u = users('R3');
  await freshStack();
  createUsers(u.L, u.P);
  const k6 = `fallos-R3-${Date.now()}`;
  startK6(
    k6,
    'fallos.js',
    { EMAIL: u.L, PASSKEY_EMAIL: u.P, DURATION: '90s', LOGIN_RATE: 1, ME_VUS: 1, STEPUP_VUS: 20 },
    out,
  );
  await sleep(30000);
  const before = Number(
    one('cms_security_tool', "SELECT count(*) FROM pg_stat_activity WHERE usename = 'cms_api';"),
  );
  const t0 = Date.now();
  hostkill(hostPid('api'));
  // Las conexiones de la API muerta desaparecen y, con ellas, sus transacciones y bloqueos.
  let gone = null;
  for (let i = 0; i < 100 && gone === null; i++) {
    const n = Number(
      one('cms_security_tool', "SELECT count(*) FROM pg_stat_activity WHERE usename = 'cms_api';"),
    );
    if (n === 0) gone = (Date.now() - t0) / 1000;
    else await sleep(100);
  }
  // Nadie retiene la fila del usuario: FOR UPDATE NOWAIT (como cms_security_tool) entra al momento.
  const nowait = psql(
    'cms_security_tool',
    `BEGIN; SELECT 'libre' FROM app_user WHERE email = '${u.P}' FOR UPDATE NOWAIT; ROLLBACK;`,
  );
  // Una recuperación justo después (antes de que la API vuelva): tiempo normal.
  const pw = path.join(env.BENCH_CRED, 'cms_admin_password');
  const t1 = Date.now();
  const rec = compose(
    [
      'run',
      '--rm',
      'security-tool',
      '--cms.tool=recuperacion-emergencia',
      `--cms.usuario=${u.P}`,
      '--cms.nueva-contrasena-desde=/run/cms-input/cms_admin_password',
    ],
    { check: false },
  );
  const recoverySeconds = (Date.now() - t1) / 1000;
  const back = await waitReady();
  const events = collectK6(k6, out);
  const result = {
    conexionesDeLaApiAntes: before,
    conexionesDesaparecidasEnSegundos: gone,
    filaLibreConNowait: nowait.includes('libre'),
    recuperacion: {
      salida: rec.status,
      segundos: recoverySeconds,
      referenciaSinCargaSegundos: 5.5,
    },
    apiDeNuevoListaSegundos: back,
    stepUpAntesDelFallo: events.filter((e) => e.k === 'stepup' && e.http === 204 && e.ts < t0)
      .length,
    archivoDeContraseña: fs.existsSync(pw),
  };
  result.correcto =
    gone !== null && result.filaLibreConNowait && rec.status === 0 && recoverySeconds < 15;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`R3 ${JSON.stringify(result)}`);
  return result;
}

async function R4() {
  const out = newOut('R4');
  const u = users('R4');
  await freshStack();
  createUsers(u.P);
  const snapshot = () =>
    psql(
      'cms_security_tool',
      `SELECT password_hash || '|' || password_changed_at || '|' || enroll_required
    || '|' || (SELECT count(*) FROM spring_session WHERE principal_name = u.id::text)
    || '|' || (SELECT count(*) FROM trusted_device WHERE user_id = u.id AND state <> 'REVOKED')
    FROM app_user u WHERE email = '${u.P}';`,
    );
  const before = snapshot();
  // Retiene el bloqueo de la cuenta como lo haría una operación de la API (cms_api, FOR KEY SHARE).
  const holder = `cms-bench-holder-${Date.now()}`;
  run('docker', [
    'exec',
    '-d',
    cid('postgres'),
    'sh',
    '-c',
    `PGPASSWORD="$(cat /run/secrets/cms_api_password)" PGAPPNAME=${holder} psql -h localhost -U cms_api -d cms -c "BEGIN" -c "SELECT 1 FROM app_user WHERE email = '${u.P}' FOR KEY SHARE" -c "SELECT pg_sleep(300)"`,
  ]);
  await sleep(2000);
  const toolName = `cms-bench-r4-tool-${Date.now()}`;
  compose([
    'run',
    '-d',
    '--name',
    toolName,
    'security-tool',
    '--cms.tool=recuperacion-emergencia',
    `--cms.usuario=${u.P}`,
    '--cms.nueva-contrasena-desde=/run/cms-input/cms_admin_password',
  ]);
  // Espera determinista: la herramienta está bloqueada (pg_locks, legible por todos).
  let waiting = false;
  for (let i = 0; i < 300 && !waiting; i++) {
    waiting =
      Number(one('cms_security_tool', 'SELECT count(*) FROM pg_locks WHERE NOT granted;')) > 0;
    if (!waiting) await sleep(200);
  }
  const toolPid = Number(run('docker', ['inspect', '-f', '{{.State.Pid}}', toolName]).stdout);
  hostkill(toolPid);
  run('docker', ['wait', toolName], { check: false });
  const exitTool = state2(toolName);
  run('docker', ['rm', toolName], { check: false });
  const killedAt = Date.now();
  const afterKill = snapshot();
  // La conexión de la herramienta muerta sigue ESPERANDO en el servidor: un backend bloqueado no
  // comprueba si su cliente vive (client_connection_check_interval está desactivado por defecto).
  // Termina por el lock_timeout de la herramienta (30 s): se mide, con el bloqueo aún retenido.
  let zombieSeconds = null;
  const zombieAtKill = Number(
    one('cms_security_tool', 'SELECT count(*) FROM pg_locks WHERE NOT granted;'),
  );
  for (let i = 0; i < 450 && zombieSeconds === null; i++) {
    if (
      Number(one('cms_security_tool', 'SELECT count(*) FROM pg_locks WHERE NOT granted;')) === 0
    ) {
      zombieSeconds = (Date.now() - killedAt) / 1000;
    } else await sleep(100);
  }
  const afterZombie = snapshot();
  const locksWaiting = Number(
    one('cms_security_tool', 'SELECT count(*) FROM pg_locks WHERE NOT granted;'),
  );
  // Se suelta el bloqueo retenido (cms_api puede terminar sus propias conexiones).
  one(
    'cms_api',
    `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = '${holder}';`,
  );
  await sleep(500);
  const t = Date.now();
  const again = compose(
    [
      'run',
      '--rm',
      'security-tool',
      '--cms.tool=recuperacion-emergencia',
      `--cms.usuario=${u.P}`,
      '--cms.nueva-contrasena-desde=/run/cms-input/cms_admin_password',
    ],
    { check: false },
  );
  const result = {
    herramientaEsperandoElBloqueo: waiting,
    salidaDeLaHerramientaMatada: exitTool,
    sinCambiosTrasMatarla: before === afterKill,
    esperaHuerfanaAlMatarla: zombieAtKill,
    esperaHuerfanaTerminaEnSegundos: zombieSeconds,
    sinCambiosTrasTerminarLaEspera: before === afterZombie,
    bloqueosEnEsperaDespues: locksWaiting,
    repeticion: {
      salida: again.status,
      segundos: (Date.now() - t) / 1000,
      cambiaElEstado: snapshot() !== before,
    },
  };
  result.correcto =
    waiting &&
    result.sinCambiosTrasMatarla &&
    result.sinCambiosTrasTerminarLaEspera &&
    zombieSeconds !== null &&
    zombieSeconds <= 31 &&
    locksWaiting === 0 &&
    again.status === 0 &&
    result.repeticion.cambiaElEstado;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`R4 ${JSON.stringify(result)}`);
  return result;
}
function state2(name) {
  const s = JSON.parse(run('docker', ['inspect', '-f', '{{json .State}}', name]).stdout);
  return { codigo: s.ExitCode, oom: s.OOMKilled };
}

async function R5() {
  const out = newOut('R5');
  const u = users('R5');
  await freshStack();
  createUsers(u.L);
  const rows = () =>
    psql(
      'cms_api',
      `SELECT key_type || ':' || failures || ':' || block_level || ':' || coalesce(blocked_until::text, '-')
    FROM login_attempt ORDER BY key_type, key_hash;`,
    );
  const k6 = `contadores-${Date.now()}`;
  startK6(k6, 'contadores.js', { EMAIL: u.L, SECONDS: 150 }, out);
  await sleep(15000);
  const before = rows();
  const tKill = Date.now();
  hostkill(hostPid('api'));
  await waitReady();
  const afterApi = rows();
  await sleep(15000);
  const tPg = Date.now();
  compose(['restart', 'postgres']);
  await waitPostgres();
  await waitReady();
  const afterPg = rows();
  const events = collectK6(k6, out);
  const fails = events.filter((e) => e.k === 'fallo').map((e) => e.http);
  const correct = events.filter((e) => e.k === 'correcta');
  const answered = correct.filter((e) => e.http);
  // Retry-After + momento del intento ≈ fin del bloqueo: constante si el estado se conserva.
  const ends = answered
    .filter((e) => e.retryAfter)
    .map((e) => Math.round(e.ts / 1000 + e.retryAfter));
  const result = {
    fallosIniciales: fails,
    intentosConLaContraseñaCorrecta: correct.length,
    respondidos: answered.length,
    estados: answered.reduce(
      (m, e) => ({
        ...m,
        [`${e.http}_${e.codigo ?? ''}`]: (m[`${e.http}_${e.codigo ?? ''}`] ?? 0) + 1,
      }),
      {},
    ),
    algunInicioDeSesionConcedido: answered.some((e) => e.http === 200),
    finDelBloqueoDesviacionSegundos: ends.length ? Math.max(...ends) - Math.min(...ends) : null,
    intentosTrasMatarLaApi: answered.filter((e) => e.ts > tKill).length,
    intentosTrasReiniciarPostgres: answered.filter((e) => e.ts > tPg).length,
    filasAntes: before.split('\n'),
    igualesTrasMatarLaApi: before === afterApi,
    igualesTrasReiniciarPostgres: before === afterPg,
  };
  result.correcto =
    !result.algunInicioDeSesionConcedido &&
    result.igualesTrasMatarLaApi &&
    result.igualesTrasReiniciarPostgres &&
    answered.every((e) => e.http === 429 || e.http === 503) &&
    result.intentosTrasReiniciarPostgres > 0;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`R5 ${JSON.stringify(result)}`);
  return result;
}

async function R6(kind) {
  const fail = async () => {
    const t = Date.now();
    if (kind === 'a') compose(['restart', 'postgres']);
    else hostkill(hostPid('postgres'));
    const pgBack = await waitPostgres();
    const apiBack = await waitReady();
    const log = run(
      'docker',
      ['logs', '--since', new Date(t - 1000).toISOString(), cid('postgres')],
      { check: false },
    );
    const recovery = `${log.stdout}\n${log.stderr}`
      .split('\n')
      .filter((l) =>
        /database system was|redo (starts|done)|ready to accept|automatic recovery|terminated by signal/.test(
          l,
        ),
      );
    return {
      tipo:
        kind === 'a'
          ? 'docker restart postgres (parada rápida)'
          : 'SIGKILL al postmaster (recuperación desde el WAL)',
      postgresListoSegundos: t ? pgBack : 0,
      apiListaSegundos: apiBack,
      registroPostgres: recovery.map((l) => l.replace(/^.*?(LOG|FATAL):\s*/, '')),
    };
  };
  return mixedFailure(`R6${kind}`, fail);
}

async function R7() {
  const out = newOut('R7');
  const pg = cid('postgres');
  // Verificación administrativa EXCEPCIONAL (K2): pg_amcheck necesita la extensión y privilegios
  // que solo tiene el superusuario. Solo en este entorno de pruebas.
  const am = run(
    'docker',
    [
      'exec',
      pg,
      'sh',
      '-c',
      'pg_amcheck -U "$POSTGRES_USER" -d cms --install-missing --heapallindexed 2>&1; echo salida=$?',
    ],
    { check: false },
  );
  const exportDir = path.join(out, 'export');
  fs.mkdirSync(exportDir, { recursive: true });
  const ex = compose(['run', '--rm', 'content-tool', '--cms.tool=export'], {
    check: false,
    extraEnv: { BENCH_EXPORT: exportDir },
  });
  const cmp = compose(
    ['run', '--rm', 'content-tool', '--cms.tool=compare', '--cms.content-dir=/content'],
    { check: false, extraEnv: { BENCH_EXPORT: exportDir } },
  );
  const result = {
    pgAmcheck: {
      salida: (am.stdout.match(/salida=(\d+)/) ?? [])[1],
      mensajes: am.stdout
        .split('\n')
        .filter((l) => l && !l.startsWith('salida='))
        .slice(0, 5),
    },
    exportacion: ex.status,
    comparacion: {
      salida: cmp.status,
      resumen: cmp.stdout.split('\n').filter((l) => /diferencias|Sin diferencias|Error/.test(l)),
    },
  };
  result.correcto = result.pgAmcheck.salida === '0' && ex.status === 0 && cmp.status === 0;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`R7 ${JSON.stringify(result)}`);
  return result;
}

const DEFINITIVE = [
  'api/Dockerfile',
  'api/src/main/resources/application.yaml',
  'infra/local/compose.yaml',
  'infra/bench/compose.yaml',
];
const hashes = () =>
  Object.fromEntries(
    DEFINITIVE.map((f) => [
      f,
      crypto
        .createHash('sha256')
        .update(fs.readFileSync(path.join(REPO, f)))
        .digest('hex')
        .slice(0, 16),
    ]),
  );

async function R8() {
  const before = hashes();
  const original = env.API_MEM;
  // 384 MiB SOLO para este contenedor del entorno de medición (K3): una variable de esta ejecución.
  env.API_MEM = '384m';
  let result;
  try {
    const fail = async () => {
      const t = Date.now();
      let oomSeen = null;
      for (let i = 0; i < 180 && oomSeen === null; i++) {
        const s = state('api');
        if (s.OOMKilled || restarts('api') > 0)
          oomSeen = {
            segundos: (Date.now() - t) / 1000,
            oomKilled: s.OOMKilled,
            reinicios: restarts('api'),
          };
        else await sleep(500);
      }
      const back = await waitReady().catch(() => null);
      const log = run('docker', ['logs', cid('api')], { check: false });
      return {
        tipo: 'límite de 384 MiB con carga de inicios de sesión',
        limiteEnElContenedorBytes: Number(
          run('docker', ['inspect', '-f', '{{.HostConfig.Memory}}', cid('api')]).stdout,
        ),
        primerFallo: oomSeen,
        // Un OutOfMemoryError real, no la opción -XX:+ExitOnOutOfMemoryError de la línea de arranque.
        outOfMemoryErrorEnElRegistro: `${log.stdout}\n${log.stderr}`
          .split('\n')
          .some((l) => /java\.lang\.OutOfMemoryError|Terminating due to/.test(l)),
        listaDeNuevoSegundos: back,
      };
    };
    result = await mixedFailure('R8', fail, {
      // El fallo se espera con las sesiones ya establecidas (20 s) y con más carga.
      failAt: 20,
      seconds: 150,
      k6env: { LOGIN_RATE: 60, ME_VUS: 10, STEPUP_VUS: 5 },
    });
  } finally {
    env.API_MEM = original;
  }
  // Vuelta al límite original y comprobación de que la configuración definitiva no ha cambiado.
  compose(['up', '-d', '--force-recreate', 'api']);
  await waitReady();
  const restored = Number(
    run('docker', ['inspect', '-f', '{{.HostConfig.Memory}}', cid('api')]).stdout,
  );
  const after = hashes();
  const extra = {
    limiteRestauradoBytes: restored,
    limiteRestauradoEsElOriginal: restored === 768 * 1024 * 1024,
    archivosDefinitivosSinCambios: JSON.stringify(before) === JSON.stringify(after),
    huellas: after,
  };
  console.log(`R8-restauracion ${JSON.stringify(extra)}`);
  const out = fs
    .readdirSync(path.join(ROOT, 'resultados'))
    .filter((d) => d.endsWith('-6274-R8'))
    .sort()
    .pop();
  fs.writeFileSync(
    path.join(ROOT, 'resultados', out, 'restauracion.json'),
    `${JSON.stringify(extra, null, 2)}\n`,
  );
  return { ...result, restauracion: extra };
}

async function R9() {
  const out = newOut('R9');
  const u = users('R9');
  await freshStack();
  createUsers(u.L);
  const L = userId(u.L);
  const apiStart = Date.now();
  const sec = (sql) => Number(one('cms_security_tool', sql));
  // Filas ya caducadas que las purgas deben retirar (como cms_api, con sus permisos de escritura).
  psql(
    'cms_api',
    `
    INSERT INTO auth_session_failure (session_hash, kind, failures, expires_at)
      SELECT gen_random_bytes_compat, 'passkey', 1, now() - interval '1 hour'
      FROM (SELECT decode(md5(random()::text) || md5(random()::text), 'hex') AS gen_random_bytes_compat FROM generate_series(1, 20)) s;
    INSERT INTO login_attempt (key_type, key_hash, window_start, failures, expires_at)
      SELECT 'ip', decode(md5(random()::text) || md5(random()::text), 'hex'), now() - interval '2 hour', 1, now() - interval '1 hour'
      FROM generate_series(1, 20);
    INSERT INTO webauthn_challenge (id, session_hash, user_id, ceremony, challenge, options, created_at, expires_at)
      SELECT gen_random_uuid(), decode(md5(random()::text) || md5(random()::text), 'hex'), '${L}', 'step_up',
             decode(md5(random()::text) || md5(random()::text), 'hex'), '{}', now() - interval '20 minute', now() - interval '10 minute'
      FROM generate_series(1, 20);
    INSERT INTO trusted_device_presentation (device_id, family_id, kind, ip_hash, agent_hash, presented_at)
      SELECT gen_random_uuid(), gen_random_uuid(), 'active', decode(md5(random()::text) || md5(random()::text), 'hex'),
             decode(md5(random()::text) || md5(random()::text), 'hex'), now() - interval '25 hour'
      FROM generate_series(1, 20);`,
  );
  const expired = () => ({
    fallosPorSesion: Number(
      one(
        'cms_api',
        "SELECT count(*) FROM auth_session_failure WHERE expires_at < now() - interval '30 minute';",
      ),
    ),
    contadores: Number(
      one(
        'cms_api',
        "SELECT count(*) FROM login_attempt WHERE expires_at < now() - interval '30 minute';",
      ),
    ),
    challenges: Number(
      one(
        'cms_api',
        "SELECT count(*) FROM webauthn_challenge WHERE expires_at < now() - interval '5 minute';",
      ),
    ),
    presentaciones: Number(
      one(
        'cms_api',
        "SELECT count(*) FROM trusted_device_presentation WHERE presented_at < now() - interval '24 hour';",
      ),
    ),
  });
  const inserted = expired();
  // Rechazos CSRF (sin token): se agregan por IP y minuto y se escriben al volcar (cada minuto).
  const csrf = (n) => {
    for (let i = 0; i < n; i++)
      run(
        'docker',
        [
          'exec',
          cid('api'),
          'curl',
          '-s',
          '-o',
          '/dev/null',
          '-X',
          'POST',
          'http://localhost:8080/api/auth/logout',
        ],
        { check: false },
      );
  };
  const csrfEvents = () =>
    sec("SELECT count(*) FROM audit_event WHERE event = 'security.csrf_rejected';");
  const c0 = csrfEvents();
  csrf(7);
  await sleep(75000);
  const c1 = csrfEvents();
  // Pérdida documentada (E5): rechazos aún sin volcar cuando la API muere.
  csrf(5);
  hostkill(hostPid('api'));
  const restartTs = Date.now();
  await waitReady();
  await sleep(75000);
  const c2 = csrfEvents();
  // Las purgas: primera ejecución a los 5 min del arranque (la de challenges, al minuto).
  const wait = Math.max(0, restartTs + 5.5 * 60000 - Date.now());
  await sleep(wait);
  const after = expired();
  const log = run('docker', ['logs', cid('api')], { check: false });
  const errors = `${log.stdout}\n${log.stderr}`
    .split('\n')
    .filter((l) => /ERROR|Exception/.test(l) && /schedul|purge|flush|Scheduled/i.test(l));
  const result = {
    insertadasCaducadas: inserted,
    trasLasPurgas: after,
    csrf: { eventosAntes: c0, trasUnVolcado: c1 - c0, trasMatarLaApiAntesDeVolcar: c2 - c1 },
    erroresDeTareasProgramadas: errors.length,
    minutosDesdeElPrimerArranque: Math.round((Date.now() - apiStart) / 6000) / 10,
  };
  result.correcto =
    Object.values(after).every((n) => n === 0) &&
    c1 - c0 === 1 &&
    c2 - c1 === 0 &&
    errors.length === 0;
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`R9 ${JSON.stringify(result)}`);
  return result;
}

const tests = { R1, R3, R4, R5, R6a: () => R6('a'), R6b: () => R6('b'), R7, R8, R9 };
if (!tests[test]) {
  console.error(`prueba desconocida: ${test} (${Object.keys(tests).join(', ')})`);
  process.exit(1);
}
tests[test]().catch((e) => {
  console.error(`ERROR: ${e.message}`);
  process.exit(1);
});
