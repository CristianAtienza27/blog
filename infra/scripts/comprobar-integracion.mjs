#!/usr/bin/env node
// Integración local de la API, la web y PostgreSQL en contenedores (fase 6.3.5). Desde la raíz:
//
//   node infra/scripts/comprobar-integracion.mjs [salida.json] [--conservar]
//
// Levanta un proyecto Compose AISLADO y efímero (cristian-atienza-cms-e2e: volúmenes, puertos y
// subred propios) con infra/local/compose.yaml y las imágenes ya construidas
// (cristian-atienza/cms-api:local y cristian-atienza/web:local). Nunca toca el proyecto local
// (cristian-atienza-cms) ni sus volúmenes: lo comprueba al final. Al terminar borra solo los
// volúmenes del proyecto e2e (salvo --conservar).
//
// Comprueba: configuración (puertos solo en 127.0.0.1, redes internas, imágenes fijadas,
// endurecimiento, registros), usuarios y capacidades de los procesos, escritura solo en los
// volúmenes y /tmp autorizados, la preparación idempotente de los volúmenes, el aislamiento de red
// y la publicación de extremo a extremo (cambio visible sin build, vuelta atrás, rechazo,
// respuesta perdida y reconciliación, API caída, reinicios de la web y de PostgreSQL, parada
// ordenada) y mide memoria, CPU y latencias con una carga ligera.
//
// Para cambiar el contenido usa el login de la API (cms_api, como la API) y, para leer la
// auditoría, el de la herramienta de seguridad (cms_security_tool, solo lectura de audit_event):
// nunca el superusuario. Las contraseñas solo se leen dentro del contenedor de PostgreSQL; nunca
// salen en argumentos ni en la salida. No imprime ni guarda contenido.
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import net from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const COMPOSE = join(ROOT, 'infra/local/compose.yaml');
const PROJECT = 'cristian-atienza-cms-e2e';
const LOCAL_PROJECT = 'cristian-atienza-cms';
const WEB_IP = '10.203.64.10';
const PORTS = { db: 55432, api: 58080, web: 54000 };
const ENV = {
  ...process.env,
  CMS_DB_PORT: String(PORTS.db),
  CMS_API_PORT: String(PORTS.api),
  CMS_WEB_PORT: String(PORTS.web),
  CMS_PUBLICACION_SUBNET: '10.203.64.0/28',
  CMS_WEB_INTERNAL_IP: WEB_IP,
  PUBLIC_SITE_URL: '',
  MEDIA_BASE_URL: '',
};
const WEB = `http://localhost:${PORTS.web}`;
const ARTICLE = '/viajes/calas-de-mallorca';
const args = process.argv.slice(2);
const KEEP = args.includes('--conservar');
const OUT = args.find((a) => !a.startsWith('--'));

// --- Utilidades ---------------------------------------------------------------------------------

const results = [];
const measures = {};
function check(id, name, ok, detail = '') {
  results.push({ id, name, ok: Boolean(ok), detail });
  console.log(`${ok ? 'OK   ' : 'FALLO'} ${id} ${name}${detail ? ` — ${detail}` : ''}`);
}

function run(cmd, argv, { input, timeout = 600_000 } = {}) {
  const r = spawnSync(cmd, argv, {
    env: ENV,
    encoding: 'utf8',
    input,
    timeout,
    maxBuffer: 64 << 20,
  });
  return { status: r.status, out: (r.stdout ?? '').trim(), err: (r.stderr ?? '').trim() };
}
const docker = (argv, opts) => run('docker', argv, opts);
const dc = (argv, opts) => docker(['compose', '-p', PROJECT, '-f', COMPOSE, ...argv], opts);
const sh = (service, script, opts) => dc(['exec', '-T', service, 'sh', '-c', script], opts);
const container = (service) => `${PROJECT}-${service}-1`;
const inspect = (service, format) => docker(['inspect', '-f', format, container(service)]).out;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** SQL dentro del contenedor de PostgreSQL con un login del CMS (la contraseña no sale de él). */
function sql(login, secret, query) {
  const script =
    `PGPASSWORD="$(cat /run/secrets/${secret})" ` +
    `psql -X -q -t -A -v ON_ERROR_STOP=1 -h 127.0.0.1 -U ${login} -d "$POSTGRES_DB" -f -`;
  const r = sh('postgres', script, { input: query });
  if (r.status !== 0) throw new Error(`SQL como ${login}: ${r.err.split('\n').at(-1)}`);
  return r.out;
}
const asApi = (q) => sql('cms_api', 'cms_api_password', q);

/** publication-tool: solo la línea de resultado (sin registros ni contenido). */
function tool(...toolArgs) {
  const t0 = performance.now();
  const r = dc(['run', '--rm', '-T', 'publication-tool', ...toolArgs]);
  const line =
    r.out.split('\n').find((l) => l.startsWith('Resultado:') || l.startsWith('Error')) ?? '';
  return { status: r.status, line, ms: Math.round(performance.now() - t0) };
}

async function page(path) {
  try {
    const r = await fetch(`${WEB}${path}`, { signal: AbortSignal.timeout(10_000) });
    return { status: r.status, text: await r.text() };
  } catch {
    return { status: 0, text: '' };
  }
}
const marker = (label) => `<meta property="article:tag" content="${label}">`;

async function waitHealthy(service, timeoutMs = 120_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (inspect(service, '{{.State.Health.Status}}') === 'healthy') return Date.now() - t0;
    await sleep(1000);
  }
  return -1;
}

async function waitFor(fn, timeoutMs = 60_000, every = 250) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    if (await fn()) return Date.now() - t0;
    await sleep(every);
  }
  return -1;
}

/** Estado del puerto interno de la web, consultado desde la API (red publicacion). */
function webStatus() {
  const r = sh('api', `curl -fsS --max-time 5 http://${WEB_IP}:4100/interno/estado`);
  if (r.status !== 0) return null;
  const body = JSON.parse(r.out); // campos del puerto interno: listo, activa, anterior, epoca…
  return { ...body, active: body.activa };
}
const dbActive = () =>
  asApi("SELECT 'v' || version FROM content_publication WHERE state = 'activa'");
const reconciliation = () => asApi('SELECT status FROM content_publication_check');

/** Intentos de conexión TCP (y DNS) desde un contenedor de prueba en una red del proyecto. */
function probe(network, targets) {
  const code = `
    const net = require('node:net'); const dns = require('node:dns').promises;
    const targets = ${JSON.stringify(targets)};
    (async () => {
      const out = {};
      for (const t of targets) {
        if (t.dns) { out[t.id] = await dns.lookup(t.dns).then(() => 'resuelve', (e) => e.code); continue; }
        out[t.id] = await new Promise((ok) => {
          const s = net.connect({ host: t.host, port: t.port });
          const done = (v) => { s.destroy(); ok(v); };
          s.setTimeout(2500, () => done('sin respuesta'));
          s.on('connect', () => done('conecta'));
          s.on('error', (e) => done(e.code));
        });
      }
      console.log(JSON.stringify(out));
    })();`;
  const r = docker([
    'run',
    '--rm',
    '--network',
    `${PROJECT}_${network}`,
    '--read-only',
    '--cap-drop',
    'ALL',
    '--security-opt',
    'no-new-privileges:true',
    '--entrypoint',
    'node',
    'cristian-atienza/web:local',
    '-e',
    code,
  ]);
  return r.status === 0 ? JSON.parse(r.out) : { error: r.err.split('\n').at(-1) };
}

function hostConnect(host, port) {
  return new Promise((ok) => {
    const s = net.connect({ host, port });
    const done = (v) => {
      s.destroy();
      ok(v);
    };
    s.setTimeout(2500, () => done('sin respuesta'));
    s.on('connect', () => done('conecta'));
    s.on('error', (e) => done(e.code));
  });
}

function stats(values) {
  if (!values.length) return null;
  const s = [...values].sort((a, b) => a - b);
  const pick = (p) => s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
  const r = (v) => Math.round(v * 10) / 10;
  return { n: s.length, p50: r(pick(50)), p95: r(pick(95)), p99: r(pick(99)), max: r(s.at(-1)) };
}

const MIB = { B: 1 / 1048576, KiB: 1 / 1024, MiB: 1, GiB: 1024 };
function sampleStats() {
  const r = docker(['stats', '--no-stream', '--format', '{{json .}}']);
  const rows = {};
  for (const line of r.out.split('\n').filter(Boolean)) {
    const s = JSON.parse(line);
    if (!s.Name.startsWith(`${PROJECT}-`)) continue;
    const [used] = s.MemUsage.split('/');
    const m = used.trim().match(/^([\d.]+)\s*([KMG]i?B|B)$/);
    rows[s.Name.slice(PROJECT.length + 1).replace(/-1$/, '')] = {
      memMiB: m ? Math.round(Number(m[1]) * (MIB[m[2]] ?? 1)) : null,
      cpu: Number.parseFloat(s.CPUPerc),
    };
  }
  return rows;
}

const statList = (service, dirs) =>
  sh(service, `find ${dirs} -exec stat -c '%u:%g %a %n' {} + | sort`).out;

function down(withVolumes) {
  if (PROJECT !== 'cristian-atienza-cms-e2e') throw new Error('proyecto inesperado');
  return dc(['down', '--remove-orphans', ...(withVolumes ? ['-v'] : [])]);
}
const localVolumes = () =>
  docker(['volume', 'ls', '--format', '{{.Name}}'])
    .out.split('\n')
    .filter((v) => v && !v.startsWith(`${PROJECT}_`))
    .sort();

// --- 0. Configuración ---------------------------------------------------------------------------

const volumesBefore = localVolumes();
down(true); // restos de una ejecución anterior del proyecto e2e (solo sus volúmenes)

{
  const cfg = JSON.parse(dc(['--profile', 'tools', 'config', '--format', 'json']).out);
  const services = Object.entries(cfg.services);
  const ports = services.flatMap(([name, s]) => (s.ports ?? []).map((p) => ({ name, ...p })));
  check(
    'C1',
    'puertos publicados solo en 127.0.0.1',
    ports.every((p) => p.host_ip === '127.0.0.1'),
    ports.map((p) => `${p.name}:${p.published}`).join(', '),
  );
  check(
    'C2',
    'el puerto interno 4100 no se publica',
    !ports.some((p) => Number(p.target) === 4100),
  );
  check(
    'C3',
    'redes datos y publicacion internas',
    cfg.networks.datos?.internal === true && cfg.networks.publicacion?.internal === true,
  );
  const hardened = ['api', 'web', 'publication-tool'].every((n) => {
    const s = cfg.services[n];
    return (
      s.read_only === true &&
      s.cap_drop?.includes('ALL') &&
      !s.cap_add?.length &&
      s.security_opt?.includes('no-new-privileges:true')
    );
  });
  check(
    'C4',
    'API, web y publication-tool endurecidas (read_only, cap_drop ALL, sin cap_add, no-new-privileges)',
    hardened,
  );
  const vol = cfg.services.volumenes;
  check(
    'C5',
    'volumenes: sin red, solo CHOWN, solo lectura',
    vol.network_mode === 'none' &&
      vol.read_only === true &&
      JSON.stringify(vol.cap_add) === '["CHOWN"]' &&
      vol.cap_drop?.includes('ALL'),
  );
  const logs = services
    .filter(([, s]) => !s.profiles)
    .every(([, s]) => s.logging?.options?.['max-size'] === '10m');
  check('C6', 'rotación de registros en todos los servicios de la pila', logs);
  const pinned =
    cfg.services.postgres.image.includes('@sha256:') &&
    ['api/Dockerfile', 'infra/web/Dockerfile'].every(
      (f) =>
        run('node', [
          '-e',
          `const t=require('fs').readFileSync(${JSON.stringify(join(ROOT, f))},'utf8');` +
            `process.exit(/^ARG (JRE|JDK|NODE)_IMAGE=[^\\n]*@sha256:/m.test(t)?0:1)`,
        ]).status === 0,
    );
  check('C7', 'imágenes base fijadas por digest', pinned);
  const limits = Object.fromEntries(
    ['postgres', 'api', 'web'].map((n) => [n, cfg.services[n].deploy?.resources?.limits]),
  );
  const expected = { postgres: [1073741824, 2], api: [805306368, 3], web: [536870912, 2] };
  check(
    'C8',
    'límites: PostgreSQL 1 GiB/2 CPU, API 768 MiB/3 CPU, web 512 MiB/2 CPU',
    Object.entries(expected).every(
      ([n, [mem, cpus]]) => Number(limits[n]?.memory) === mem && Number(limits[n]?.cpus) === cpus,
    ),
    Object.entries(limits)
      .map(([n, l]) => `${n} ${Math.round(Number(l?.memory) / 1048576)} MiB/${l?.cpus} CPU`)
      .join(', '),
  );
}

// --- 1. Arranque desde volúmenes vacíos ---------------------------------------------------------

let t0 = Date.now();
const up = dc(['up', '-d', '--no-build']);
check(
  'A1',
  'arranque de la pila (volúmenes vacíos)',
  up.status === 0,
  up.status ? up.err.split('\n').at(-1) : '',
);
const apiReady = await waitHealthy('api');
const webReady = await waitHealthy('web');
measures.arranque = { apiHealthyMs: apiReady, webHealthyMs: webReady, totalMs: Date.now() - t0 };
check(
  'A2',
  'API y web «healthy»',
  apiReady >= 0 && webReady >= 0,
  `${Math.round((Date.now() - t0) / 1000)} s`,
);
const volLog = dc(['logs', 'volumenes']).out;
check(
  'A3',
  'volumenes prepara las raíces vacías',
  /instantaneas: 0:0 755 -> 999:999 755/.test(volLog) &&
    /estado: 0:0 755 -> 1000:1000 755/.test(volLog),
);
check(
  'A4',
  'sin versión activa la web responde 503 (listo DOWN)',
  (await page('/_salud/listo')).status === 503,
);

const imported = dc(['run', '--rm', '-T', 'content-tool', '--cms.tool=import']);
check('A5', 'importación del contenido (content-tool)', imported.status === 0);
const first = tool('--cms.tool=publicar');
check(
  'A6',
  'primera publicación',
  first.status === 0 && /activada \(v1\)/.test(first.line),
  first.line,
);
check('A7', 'la web sirve v1', (await page(ARTICLE)).text.includes(marker('Costa')));

// --- 2. Usuarios, capacidades y sistema de archivos ---------------------------------------------

for (const [service, uid] of [
  ['web', '1000'],
  ['api', '999'],
]) {
  const uids = sh(
    service,
    "grep -h '^Uid:' /proc/[0-9]*/status 2>/dev/null | awk '{print $2}' | sort -u",
  ).out;
  check(
    'U1',
    `${service}: todos los procesos con uid ${uid}`,
    uids === uid,
    uids.replace(/\n/g, ','),
  );
  const caps = sh(service, "grep -h '^CapEff:' /proc/[0-9]*/status 2>/dev/null | sort -u").out;
  const nnp = sh(service, "grep -h '^NoNewPrivs:' /proc/[0-9]*/status 2>/dev/null | sort -u").out;
  check(
    'U2',
    `${service}: sin capacidades efectivas y NoNewPrivs`,
    /^CapEff:\s+0+$/.test(caps) && /^NoNewPrivs:\s+1$/.test(nnp),
  );
  check(
    'U3',
    `${service}: sistema de archivos raíz en solo lectura`,
    inspect(service, '{{.HostConfig.ReadonlyRootfs}}') === 'true' &&
      / \/ \S+ ro[,\s]/.test(sh(service, 'cat /proc/mounts').out),
  );
  check(
    'U4',
    `${service}: /tmp en memoria y sin ejecución`,
    /^tmpfs \/tmp tmpfs \S*noexec/m.test(sh(service, 'cat /proc/mounts').out),
  );
}

const can = (service, path) =>
  sh(service, `touch '${path}' 2>/dev/null && rm -f '${path}' && echo SI || echo NO`).out === 'SI';
const writes = {
  web: {
    '/instantaneas/.p': false,
    '/instantaneas/v1/.p': false,
    '/estado/.p': true,
    '/tmp/.p': true,
    '/app/.p': false,
    '/.p': false,
    '/home/node/.p': false,
  },
  api: {
    '/instantaneas/.p': true,
    '/instantaneas/v1/.p': true,
    '/estado/.p': false,
    '/tmp/.p': true,
    '/app/.p': false,
    '/.p': false,
  },
};
for (const [service, paths] of Object.entries(writes)) {
  const wrong = Object.entries(paths).filter(([p, expected]) => can(service, p) !== expected);
  check(
    'P1',
    `${service}: escribe solo en sus volúmenes y /tmp`,
    wrong.length === 0,
    wrong.length
      ? wrong.map(([p]) => p).join(', ')
      : Object.entries(paths)
          .map(([p, e]) => `${p}=${e ? 'sí' : 'no'}`)
          .join(' '),
  );
}
check(
  'P2',
  'la API lee estado/activa.json (archivo de la web)',
  sh('api', 'cat /estado/activa.json > /dev/null && echo SI').out === 'SI',
);
const roots = sh('api', "stat -c '%u:%g %a' /instantaneas /estado").out.split('\n');
check(
  'P3',
  'raíces: instantaneas 999:999 755, estado 1000:1000 755',
  roots[0] === '999:999 755' && roots[1] === '1000:1000 755',
  roots.join(' | '),
);
const snapModes = sh(
  'api',
  "find /instantaneas/v1 -exec stat -c '%u %a %F' {} + | sort -u",
).out.split('\n');
check(
  'P4',
  'instantáneas: archivos 0644 y carpetas 0755 de la API',
  snapModes.every((l) => l === '999 644 regular file' || l === '999 755 directory'),
  snapModes.join(' | '),
);
const stateModes = sh(
  'api',
  "find /estado -mindepth 1 -exec stat -c '%u %a %F' {} + | sort -u",
).out.split('\n');
check(
  'P5',
  'estado: archivos 0644 de la web',
  stateModes.every((l) => l === '1000 644 regular file'),
  stateModes.join(' | '),
);

// --- 3. Red -------------------------------------------------------------------------------------

const webBorde = inspect(
  'web',
  `{{(index .NetworkSettings.Networks "${PROJECT}_borde").IPAddress}}`,
);
const pgDatos = inspect(
  'postgres',
  `{{(index .NetworkSettings.Networks "${PROJECT}_datos").IPAddress}}`,
);
{
  const host = {
    local: await hostConnect('127.0.0.1', 4100),
    interna: await hostConnect(WEB_IP, 4100),
  };
  check(
    'R1',
    'el puerto interno no es alcanzable desde el equipo',
    host.local !== 'conecta' && host.interna !== 'conecta',
    JSON.stringify(host),
  );
  const borde = probe('borde', [
    { id: 'webInterna', host: WEB_IP, port: 4100 },
    { id: 'webBorde4100', host: webBorde, port: 4100 },
    { id: 'webPublico', host: webBorde, port: 4000 },
    { id: 'postgres', dns: 'postgres' },
    { id: 'postgresIp', host: pgDatos, port: 5432 },
  ]);
  check(
    'R2',
    'desde la red borde: sin puerto interno ni PostgreSQL (el público sí)',
    borde.webInterna !== 'conecta' &&
      borde.webBorde4100 !== 'conecta' &&
      borde.webPublico === 'conecta' &&
      borde.postgres !== 'resuelve' &&
      borde.postgresIp !== 'conecta',
    JSON.stringify(borde),
  );
  check('R3', 'la API alcanza el puerto interno de la web', webStatus()?.active === 'v1');
  const fromWeb = dc([
    'exec',
    '-T',
    'web',
    'node',
    '-e',
    "require('node:dns').promises.lookup('postgres').then(() => console.log('resuelve'), (e) => console.log(e.code))",
  ]).out;
  check('R4', 'la web no resuelve PostgreSQL', fromWeb !== 'resuelve', fromWeb);
  const egress = {
    datos: probe('datos', [{ id: 'internet', host: '1.1.1.1', port: 443 }]).internet,
    publicacion: probe('publicacion', [{ id: 'internet', host: '1.1.1.1', port: 443 }]).internet,
  };
  check(
    'R5',
    'las redes internas no tienen salida a Internet',
    egress.datos !== 'conecta' && egress.publicacion !== 'conecta',
    JSON.stringify(egress),
  );
}

// --- 4. Publicación de extremo a extremo --------------------------------------------------------

// E1. Cambio visible sin build.
asApi("UPDATE tag SET label = 'Costa E2E' WHERE id = 'costa'");
t0 = performance.now();
const p2 = tool('--cms.tool=publicar');
const visibleMs =
  (await waitFor(async () => (await page(ARTICLE)).text.includes(marker('Costa E2E')), 60_000)) >= 0
    ? Math.round(performance.now() - t0)
    : -1;
measures.cambioVisibleMs = visibleMs;
check(
  'E1',
  'un cambio se ve en menos de 60 s sin build',
  p2.status === 0 && /activada \(v2\)/.test(p2.line) && visibleMs >= 0 && visibleMs < 60_000,
  `${p2.line}; visible en ${visibleMs} ms (incluye el arranque de la JVM de la herramienta, ${p2.ms} ms)`,
);

// E2. Vuelta atrás.
const back = tool('--cms.tool=activar', '--cms.version=v1');
check(
  'E2',
  'vuelta atrás a v1',
  back.status === 0 &&
    (await page(ARTICLE)).text.includes(marker('Costa')) &&
    webStatus()?.active === 'v1',
  back.line,
);

// E3. Rechazo: v2 manipulada en disco; la web la verifica al activarla y la rechaza.
sh(
  'api',
  'cp /instantaneas/v2/tags.json /tmp/tags.json && printf " " >> /instantaneas/v2/tags.json',
);
const rejected = tool('--cms.tool=activar', '--cms.version=v2');
const stillV1 =
  webStatus()?.active === 'v1' && (await page(ARTICLE)).text.includes(marker('Costa'));
check(
  'E3',
  'una instantánea manipulada se rechaza y la web sigue con v1',
  rejected.status === 2 && /rechazada/.test(rejected.line) && stillV1,
  rejected.line,
);
sh('api', 'cat /tmp/tags.json > /instantaneas/v2/tags.json && rm /tmp/tags.json');
const again = tool('--cms.tool=activar', '--cms.version=v2');
check(
  'E4',
  'restaurada, v2 se activa',
  again.status === 0 && /activada \(v2\)/.test(again.line),
  again.line,
);

// E5–E6. Respuesta perdida: la web activa la versión, pero la API deja de esperar antes (tiempo de
// espera corto con --cms.publication.web-request-timeout). Dos caminos posibles (6.3.4):
//   inmediato  al agotarse el tiempo, activa.json ya señala vN: la API la registra como activada;
//   diferido   la web termina DESPUÉS: la API la deja fallida (con su huella) y la siguiente
//              ejecución reconcilia con activa.json (fallida -> activa).
// El tiempo debe quedar entre lo que tarda la reserva (rápida) y lo que tarda la activación (lee y
// verifica la versión entera); se prueban valores pequeños crecientes hasta observar uno de los dos.
{
  const audit = () =>
    sql(
      'cms_security_tool',
      'cms_security_tool_password',
      "SELECT event || ' ' || coalesce(details ->> 'motivo', '') FROM audit_event WHERE event LIKE 'content.%' ORDER BY occurred_at DESC LIMIT 1",
    );
  const stateOf = (n) => asApi(`SELECT state FROM content_publication WHERE version = ${n}`);
  let observed = null;
  let incoherent = null;
  const attempts = [];
  measures.respuestaPerdida = attempts;
  for (const [i, timeout] of [
    '5ms',
    '10ms',
    '20ms',
    '30ms',
    '45ms',
    '60ms',
    '100ms',
    '150ms',
  ].entries()) {
    asApi(`UPDATE tag SET label = 'Costa E2E R${i}' WHERE id = 'costa'`);
    const r = tool('--cms.tool=publicar', `--cms.publication.web-request-timeout=${timeout}`);
    const lastEvent = audit();
    await sleep(2000); // la web termina lo que tuviera en curso
    const version = r.line.match(/\(v(\d+)\)/)?.[1];
    const web = webStatus()?.active;
    const db = dbActive();
    const state = version ? stateOf(version) : null;
    attempts.push({
      timeout,
      resultado: r.line,
      ultimoEvento: lastEvent,
      web,
      postgres: db,
      estadoVersion: state,
    });
    if (
      r.status === 0 &&
      lastEvent.includes('la web no respondió, pero activa.json ya la señala')
    ) {
      observed = { camino: 'inmediato', timeout, line: r.line };
      break;
    }
    // Diferido: la herramienta informa «fallida», pero la web sí la activó y la reconciliación (al
    // final de la misma ejecución o en la siguiente) la ha pasado a activa en PostgreSQL.
    if (
      version &&
      /fallida/.test(r.line) &&
      web === `v${version}` &&
      (state === 'fallida' || (state === 'activa' && lastEvent.startsWith('content.reconciled')))
    ) {
      observed = { camino: 'diferido', timeout, line: r.line, version };
      break;
    }
    if (web !== db) {
      incoherent = { timeout, web, db };
      break;
    }
  }
  check(
    'E5',
    'respuesta perdida reproducida (la web activa, la API no recibe la respuesta)',
    observed !== null && !incoherent,
    observed
      ? `camino ${observed.camino} con ${observed.timeout}: ${observed.line}`
      : `no observada ${JSON.stringify(incoherent ?? '')}`,
  );
  // La siguiente ejecución reconcilia (diferido) o no tiene nada que hacer (inmediato).
  const after = tool('--cms.tool=publicar');
  const recovered = observed?.camino === 'diferido' ? stateOf(observed.version) === 'activa' : true;
  check(
    'E6',
    'tras la respuesta perdida: reconciliada, coherente y CONCILIADO',
    after.status === 0 &&
      /sin cambios/.test(after.line) &&
      recovered &&
      reconciliation() === 'CONCILIADO' &&
      webStatus()?.active === dbActive(),
    `${after.line}; web ${webStatus()?.active}, PostgreSQL ${dbActive()}, ${reconciliation()}; último evento: ${audit()}`,
  );
}

// E7. La web no responde durante una publicación (pausada): fallida, sin cambios en lo servido.
{
  const served = webStatus()?.active;
  asApi("UPDATE tag SET label = 'Costa E2E P' WHERE id = 'costa'");
  dc(['pause', 'web']);
  const r = tool('--cms.tool=publicar', '--cms.publication.web-request-timeout=3s');
  dc(['unpause', 'web']);
  // Sin respuesta en la reserva o en la activación: error o fallida (nunca activada), y nada cambia.
  check(
    'E7',
    'web pausada: la publicación no se activa y lo servido no cambia',
    r.status !== 0 &&
      !/activada/.test(r.line) &&
      webStatus()?.active === served &&
      dbActive() === served,
    `${r.line}; sirve ${served}`,
  );
  const retry = tool('--cms.tool=publicar');
  check(
    'E8',
    'reintento tras volver la web',
    retry.status === 0 &&
      /activada/.test(retry.line) &&
      (await page(ARTICLE)).text.includes(marker('Costa E2E P')),
    retry.line,
  );
}

// E9–E10. API caída: la web sigue sirviendo, también tras reiniciarse.
{
  const label = marker('Costa E2E P');
  dc(['stop', 'api']);
  check(
    'E9',
    'API caída: la web sirve la versión activa',
    (await page(ARTICLE)).text.includes(label),
  );
  dc(['restart', 'web']);
  const healthy = await waitHealthy('web');
  check(
    'E10',
    'API caída y web reiniciada: sirve la versión activa',
    healthy >= 0 &&
      (await page('/_salud/listo')).status === 200 &&
      (await page(ARTICLE)).text.includes(label),
    `healthy en ${healthy} ms`,
  );
  dc(['start', 'api']);
  check(
    'E11',
    'la API vuelve y concilia',
    (await waitHealthy('api')) >= 0 &&
      (await waitFor(() => reconciliation() === 'CONCILIADO', 60_000, 1000)) >= 0,
    reconciliation(),
  );
}

// E12. Reinicio de PostgreSQL: la API se recupera y vuelve a publicar.
{
  dc(['restart', 'postgres']);
  await waitHealthy('postgres');
  const ready = await waitFor(
    async () => {
      try {
        return (
          (await fetch(`http://localhost:${PORTS.api}/actuator/health/readiness`)).status === 200
        );
      } catch {
        return false;
      }
    },
    60_000,
    1000,
  );
  const r = tool('--cms.tool=publicar');
  check(
    'E12',
    'PostgreSQL reiniciado: la API vuelve a estar lista y publica',
    ready >= 0 && r.status === 0,
    `lista en ${ready} ms; ${r.line}`,
  );
}

// E13. Fallo del proceso de la web: Docker lo reinicia.
{
  const before = Number(inspect('web', '{{.RestartCount}}'));
  const kill =
    "const fs = require('fs'); for (const p of fs.readdirSync('/proc')) { if (!/^[0-9]+$/.test(p) || +p === process.pid) continue; " +
    "try { if (fs.readFileSync('/proc/' + p + '/cmdline', 'utf8').includes('server/server.mjs')) process.kill(+p, 'SIGKILL'); } catch {} }";
  dc(['exec', '-T', 'web', 'node', '-e', kill]);
  const restarted = await waitFor(
    () => Number(inspect('web', '{{.RestartCount}}')) > before,
    30_000,
    500,
  );
  const healthy = await waitHealthy('web');
  check(
    'E13',
    'proceso de la web terminado: reinicio automático',
    restarted >= 0 && healthy >= 0 && (await page(ARTICLE)).text.includes(marker('Costa E2E P')),
    `reinicios ${before} -> ${inspect('web', '{{.RestartCount}}')}`,
  );
}

// E14. Parada ordenada (SIGTERM con init): mucho antes del SIGKILL de los 10 s.
{
  t0 = Date.now();
  dc(['stop', 'web']);
  const stopMs = Date.now() - t0;
  const exit = inspect('web', '{{.State.ExitCode}}');
  measures.paradaWebMs = stopMs;
  check(
    'E14',
    'la web para con SIGTERM sin esperar al SIGKILL',
    stopMs < 8000,
    `${stopMs} ms, código ${exit}`,
  );
  dc(['start', 'web']);
  await waitHealthy('web');
}

// --- 5. Preparación idempotente de los volúmenes -------------------------------------------------

{
  const before = statList('api', '/instantaneas /estado');
  const again = dc(['run', '--rm', '-T', 'volumenes']);
  const after = statList('api', '/instantaneas /estado');
  check(
    'V1',
    'volumenes otra vez: no cambia nada (ni raíces ni contenido)',
    again.status === 0 &&
      /instantaneas: correcto/.test(again.out) &&
      /estado: correcto/.test(again.out) &&
      before === after,
    `${before.split('\n').length} entradas comparadas`,
  );
  // Raíz con modo incorrecto: corrige solo la raíz, nunca el contenido.
  sh('api', 'chmod 0700 /instantaneas');
  const fixed = dc(['run', '--rm', '-T', 'volumenes']);
  const afterFix = statList('api', '/instantaneas /estado');
  check(
    'V2',
    'raíz con modo incorrecto: solo se corrige la raíz',
    /instantaneas: 999:999 700 -> 999:999 755/.test(fixed.out) && afterFix === before,
  );
}

// --- 6. Carga ligera: memoria, CPU y latencias ---------------------------------------------------

{
  const restartsBefore = [
    inspect('web', '{{.RestartCount}}'),
    inspect('api', '{{.RestartCount}}'),
  ].join('/');
  const idle = sampleStats();
  let running = true;
  let n = 0;
  const ssr = [];
  const cached = [];
  const worker = async (bypass, path) => {
    while (running) {
      const t = performance.now();
      const r = await page(bypass ? `${path}?carga=${n++}` : path);
      if (r.status === 200) (bypass ? ssr : cached).push(performance.now() - t);
    }
  };
  const workers = [0, 1, 2, 3, 4, 5, 6, 7].map((i) =>
    worker(i % 2 === 1, i % 4 < 2 ? '/' : ARTICLE),
  );
  const samples = [];
  const sampler = (async () => {
    while (running) {
      samples.push(sampleStats());
      await sleep(1000);
    }
  })();
  await sleep(10_000);
  asApi("UPDATE tag SET label = 'Costa E2E C' WHERE id = 'costa'");
  const loaded = tool('--cms.tool=publicar');
  const visible = await waitFor(
    async () => (await page(ARTICLE)).text.includes(marker('Costa E2E C')),
    60_000,
  );
  await sleep(10_000);
  running = false;
  await Promise.all([...workers, sampler]);
  const peak = (svc, key) => Math.max(...samples.map((s) => s[svc]?.[key] ?? 0));
  measures.carga = {
    clientes: 8,
    sinCache: 4,
    duracionS: 20 + Math.round(loaded.ms / 1000),
    reposo: idle,
    pico: Object.fromEntries(
      ['web', 'api', 'postgres'].map((s) => [
        s,
        { memMiB: peak(s, 'memMiB'), cpu: peak(s, 'cpu') },
      ]),
    ),
    ssrSinCacheMs: stats(ssr),
    conCacheMs: stats(cached),
    publicacionBajoCarga: {
      resultado: loaded.line,
      herramientaMs: loaded.ms,
      visibleTrasHerramientaMs: visible,
    },
  };
  check(
    'L1',
    'carga ligera: publicación bajo carga visible',
    loaded.status === 0 && visible >= 0,
    loaded.line,
  );
  check(
    'L2',
    'carga ligera: la web por debajo de su límite (512 MiB)',
    measures.carga.pico.web.memMiB < 512,
    `pico web ${measures.carga.pico.web.memMiB} MiB, API ${measures.carga.pico.api.memMiB} MiB, PostgreSQL ${measures.carga.pico.postgres.memMiB} MiB`,
  );
  check(
    'L3',
    'sin reinicios durante la carga',
    [inspect('web', '{{.RestartCount}}'), inspect('api', '{{.RestartCount}}')].join('/') ===
      restartsBefore,
  );
}

// --- 7. Final -----------------------------------------------------------------------------------

check(
  'F1',
  'estado final coherente y conciliado',
  webStatus()?.active === dbActive() && reconciliation() === 'CONCILIADO',
  `${dbActive()} ${reconciliation()}`,
);
if (!KEEP) down(true);
const volumesAfter = localVolumes();
check(
  'F2',
  'los volúmenes ajenos al proyecto e2e siguen intactos',
  volumesBefore.every((v) => volumesAfter.includes(v)),
  `${volumesBefore.length} volúmenes`,
);

const failed = results.filter((r) => !r.ok);
const report = {
  fecha: new Date().toISOString(),
  proyecto: PROJECT,
  total: results.length,
  fallos: failed.length,
  resultados: results,
  mediciones: measures,
};
if (OUT) writeFileSync(OUT, JSON.stringify(report, null, 2));
console.log(`\n${results.length - failed.length} de ${results.length} comprobaciones correctas`);
console.log(JSON.stringify(measures, null, 2));
process.exit(failed.length ? 1 : 0);
