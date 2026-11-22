#!/usr/bin/env node
/**
 * Orquestador de mediciones de la fase 6.1.4. Se ejecuta desde la raíz del proyecto con Node:
 *
 *   node infra/bench/scripts/bench.mjs <comando> [--opción valor ...]
 *
 * Comandos:
 *   comprobar                       protecciones (proyecto cms-bench, montajes, rutas de salida)
 *   preparar                        arranca postgres e importa el contenido (BENCH_CONTENT)
 *   reposo     --servicios --segundos
 *   k6         --script --niveles 1,5,10 --duracion 60s --servicios [--modo] [--rate]
 *   pgbench    --clientes 1,2,5,10 --duracion 60 --servicios postgres
 *   media      --modo derive|fases --imagenes a.jpg,b.heic [--concurrencia N] [--mem 1024m]
 *   combinada  --rate 20 --api-rate 5 --pgbench 5 [--imagen a.heic] --duracion 180
 *
 * Autenticación (fase 6.2.7):
 *   admin      [--email a@b,c@d]   usuarios de prueba con crear-admin (credencial en <BENCH_ROOT>/credenciales)
 *   argon2     --clientes 1,2,4 --niveles 1,2,4,8 [--mem 768m]   coste de un Argon2id (CPU de la JVM, hilos)
 *   nmt        [--nombre x]        memoria de la JVM de la API (NMT y heap con jcmd) y su cgroup
 *   sesiones   --clientes 10000    sesiones caducadas sintéticas (crecimiento de spring_session)
 *   reset                          estado de autenticación entre variantes (6.2.7.3): sesiones,
 *                                  contadores de intentos, fallos por sesión y challenges, como cms_api
 *   gc                             pausas del recolector en el registro de la API (-Xlog:gc)
 *   k6 --email                     usuario de prueba de los escenarios de passkeys
 *   pgbench --sql archivo.sql      script propio de infra/bench/pgbench/ en lugar de las consultas de contenido
 *
 * Cada ejecución escribe en <BENCH_ROOT>/resultados/<fecha>-<nombre>/ (fuera del repositorio):
 * el resumen de carga (k6/pgbench/media), las muestras del cgroup (CSV y resumen) y meta.json.
 * Envolventes: variables SUT_CPUS, LOAD_CPUS, PG_MEM, API_MEM, SSR_MEM, MEDIA_MEM (compose.yaml).
 */
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';

const REPO = path.resolve(import.meta.dirname, '../../..');
const BENCH_DIR = path.join(REPO, 'infra/bench');
const ROOT = path.resolve(process.env.BENCH_ROOT ?? path.join(REPO, '../blog-bench'));
const PROJECT = 'cms-bench';
const REAL_VOLUME = 'cristian-atienza-cms_postgres-data';
// Credencial de prueba de este entorno (6.2.7, D5): fuera del repositorio, solo para cms-bench.
const CRED = path.resolve(process.env.BENCH_CRED ?? path.join(ROOT, 'credenciales'));
// La misma imagen de JDK (versión y digest) con la que se construye la API (api/Dockerfile).
const JDK_IMAGE =
  'eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d';
const NODE_IMAGE =
  'node:26.13.0-trixie-slim@sha256:e4db4c914bf8ad241bd24804fc25d272d89bad97e68cdc8a1414862790a02c39';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
// Valores por defecto que compose.yaml exige (cada ejecución los sustituye por los suyos).
process.env.BENCH_OUT ??= path.join(ROOT, 'resultados');
process.env.BENCH_IMAGES ??= path.join(ROOT, 'imagenes-prueba');

const [command, ...rest] = process.argv.slice(2);
const { values: opt } = parseArgs({
  args: rest,
  options: {
    servicios: { type: 'string', default: 'postgres,api,ssr' },
    segundos: { type: 'string', default: '600' },
    script: { type: 'string' },
    modo: { type: 'string' },
    niveles: { type: 'string', default: '1' },
    rate: { type: 'string' },
    'api-rate': { type: 'string', default: '5' },
    duracion: { type: 'string', default: '60s' },
    pausa: { type: 'string', default: '15' },
    clientes: { type: 'string', default: '1' },
    imagenes: { type: 'string', default: '' },
    imagen: { type: 'string' },
    concurrencia: { type: 'string', default: '' },
    mem: { type: 'string' },
    pgbench: { type: 'string', default: '0' },
    nombre: { type: 'string' },
    'max-vus': { type: 'string' },
    email: { type: 'string' },
    sql: { type: 'string' },
  },
});

// --- Docker ------------------------------------------------------------------------------------

function run(cmd, args, { capture = false, check = true, env = {}, input } = {}) {
  const res = spawnSync(cmd, args, {
    cwd: REPO,
    encoding: 'utf8',
    env: { ...process.env, ...env },
    input,
    stdio: capture ? [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'] : 'inherit',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (check && res.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} → ${res.status}\n${res.stderr ?? ''}`);
  }
  return res;
}

function runAsync(cmd, args, env = {}) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { cwd: REPO, env: { ...process.env, ...env } });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.stderr.on('data', (d) => (stderr += d));
    child.on('close', (status) => resolve({ status, stdout, stderr }));
  });
}

// Sin infra/local/.env: este entorno tiene sus propios secretos (infra/bench/secrets).
const composeArgs = ['compose', '-f', 'infra/bench/compose.yaml', '--profile', 'tools'];
const compose = (args, options) => run('docker', [...composeArgs, ...args], options);
const containerId = (service) =>
  compose(['ps', '-q', service], { capture: true }).stdout.trim() || null;

// --- Protecciones ----------------------------------------------------------------------------

function outsideRepo(dir) {
  const rel = path.relative(REPO, path.resolve(dir));
  return rel.startsWith('..') || path.isAbsolute(rel);
}

function guard() {
  if (!outsideRepo(ROOT)) throw new Error(`BENCH_ROOT (${ROOT}) está dentro del repositorio`);
  const config = JSON.parse(compose(['config', '--format', 'json'], { capture: true }).stdout);
  if (config.name !== PROJECT) throw new Error(`el proyecto es «${config.name}», no ${PROJECT}`);
  for (const [name, service] of Object.entries(config.services)) {
    for (const v of service.volumes ?? []) {
      const volumeName = config.volumes?.[v.source]?.name ?? '';
      if (
        v.type === 'volume' &&
        (!volumeName.startsWith(`${PROJECT}_`) ||
          volumeName.includes('/') ||
          volumeName.includes('..'))
      ) {
        throw new Error(`${name} usa un volumen ajeno: ${v.source}`);
      }
      if (v.source === REAL_VOLUME) throw new Error(`${name} monta el volumen real`);
    }
  }
  for (const [vname, volume] of Object.entries(config.volumes ?? {})) {
    if (volume.name === REAL_VOLUME || volume.external) {
      throw new Error(`volumen no permitido: ${vname} (${volume.name})`);
    }
  }
  // El servicio de imágenes solo puede montar las copias de prueba y el script de medición.
  const media = config.services.media.volumes.map((v) => v.target).sort();
  const expected = ['/app/src/bench.mjs', '/bench-in'];
  if (JSON.stringify(media) !== JSON.stringify(expected)) {
    throw new Error(`montajes de media inesperados: ${media.join(', ')}`);
  }
  const imagesSource = config.services.media.volumes.find((v) => v.target === '/bench-in');
  if (!imagesSource.read_only || !outsideRepo(imagesSource.source)) {
    throw new Error('las imágenes de prueba deben estar fuera del repositorio y en solo lectura');
  }
  return { volumes: Object.values(config.volumes ?? {}).map((v) => v.name) };
}

function benchEnv(out) {
  return {
    BENCH_CRED: CRED,
    BENCH_OUT: out,
    BENCH_IMAGES: process.env.BENCH_IMAGES ?? path.join(ROOT, 'imagenes-prueba'),
  };
}

// --- Resultados ----------------------------------------------------------------------------------

function newRun(name) {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, '').replace('T', '-');
  const dir = path.join(ROOT, 'resultados', `${stamp}-${name}`);
  fs.mkdirSync(dir, { recursive: true });
  const meta = {
    nombre: name,
    inicio: new Date().toISOString(),
    entorno: 'medición local en Docker Desktop (WSL2) con límites simulados; no es un VPS',
    sutCpus: process.env.SUT_CPUS ?? '0-1',
    loadCpus: process.env.LOAD_CPUS ?? '8-9',
    limites: {
      postgres: process.env.PG_MEM ?? '448m',
      api: process.env.API_MEM ?? '640m',
      ssr: process.env.SSR_MEM ?? '384m',
      media: process.env.MEDIA_MEM ?? '1024m',
    },
    apiJavaOpts:
      process.env.API_JAVA_OPTS ?? '(imagen) -XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError',
    ssrNodeOptions: process.env.SSR_NODE_OPTIONS ?? '',
    ssrTag: process.env.SSR_TAG ?? 'actual',
    opciones: opt,
    git: run('git', ['rev-parse', 'HEAD'], { capture: true }).stdout.trim(),
  };
  fs.writeFileSync(path.join(dir, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`);
  return dir;
}

function startSampler(out, prefix, services, interval = 500) {
  const targets = services
    .map((service) => {
      const id = service.startsWith('id:')
        ? service.slice(3).split('=')
        : [service, containerId(service)];
      return id[1] ? `${id[0]}=${id[1]}` : null;
    })
    .filter(Boolean);
  const name = `cms-bench-sampler-${Date.now()}`;
  run(
    'docker',
    [
      'run',
      '-d',
      '--rm',
      '--name',
      name,
      '--cpuset-cpus',
      process.env.LOAD_CPUS ?? '8-9',
      '--memory',
      '128m',
      '-v',
      '/sys/fs/cgroup:/cg:ro',
      '-v',
      `${path.join(BENCH_DIR, 'scripts')}:/s:ro`,
      '-v',
      `${out}:/out`,
      '-e',
      `TARGETS=${targets.join(',')}`,
      '-e',
      `INTERVAL_MS=${interval}`,
      '-e',
      `OUT=/out/${prefix}`,
      NODE_IMAGE,
      'node',
      '/s/sampler.mjs',
    ],
    { capture: true },
  );
  return name;
}

async function stopSampler(name, out, prefix) {
  run('docker', ['stop', '-t', '10', name], { capture: true, check: false });
  for (let i = 0; i < 20 && !fs.existsSync(path.join(out, `${prefix}.json`)); i++) await sleep(250);
  return JSON.parse(fs.readFileSync(path.join(out, `${prefix}.json`), 'utf8'));
}

const services = () => opt.servicios.split(',').filter(Boolean);
const print = (label, value) => console.log(`${label} ${JSON.stringify(value)}`);

// --- Datos de los escenarios ---------------------------------------------------------------------

function writeRoutes(out) {
  const dist = path.resolve(
    process.env.SSR_DIST
      ? path.join(BENCH_DIR, process.env.SSR_DIST)
      : path.join(REPO, 'dist/cristian-atienza'),
  );
  const routes = Object.keys(
    JSON.parse(
      fs.readFileSync(path.join(REPO, 'dist/cristian-atienza/prerendered-routes.json'), 'utf8'),
    ).routes,
  );
  const articles = routes.filter((r) => r.split('/').length === 3);
  const sections = [...new Set(articles.map((r) => r.split('/')[1]))];
  const browser = path.join(dist, 'browser');
  const top = fs
    .readdirSync(browser)
    .filter((f) => /\.(js|css)$/.test(f))
    .map((f) => `/${f}`);
  const media = fs
    .readdirSync(path.join(browser, 'media'), { recursive: true })
    .map((f) => f.replaceAll('\\', '/'))
    .filter((f) => /-1024\.(avif|webp)$/.test(f))
    .map((f) => `/media/${f}`);
  const data = {
    prerender: routes,
    articulos: articles,
    secciones: sections,
    estaticos: [...top, ...media],
  };
  fs.writeFileSync(path.join(out, 'rutas.json'), `${JSON.stringify(data, null, 2)}\n`);
  return data;
}

function writePgbench(out) {
  const content = path.join(REPO, 'src/content');
  const articles = JSON.parse(fs.readFileSync(path.join(content, 'articles.json'), 'utf8'));
  const categories = JSON.parse(fs.readFileSync(path.join(content, 'categories.json'), 'utf8'));
  const list = (values) => `ARRAY[${values.map((v) => `'${v.replaceAll("'", "''")}'`).join(',')}]`;
  const slugs = list(articles.map((a) => a.slug));
  const scripts = {
    // Artículo por slug: registro + contenido publicado completo (bloques y etiquetas), leído de
    // las tablas como lo hará la API con cms_api (6.2.2; antes, article_revision_content).
    articulo: `\\set i random(1, ${articles.length})
SELECT a.id, a.slug, a.category_id, a.published_at, a.updated_at, r.title, r.dek, r.excerpt, r.format,
       r.author_id, r.cover_media_id, r.trip_id, r.project_id, r.seo, r.blocks, r.reading_minutes,
       (SELECT array_agg(t.tag_id ORDER BY t.position) FROM article_revision_tag t WHERE t.revision_id = r.id) AS tags
  FROM article a JOIN article_revision r ON r.id = a.published_revision_id WHERE a.slug = (${slugs})[:i];
`,
    // Listado de una sección: tarjetas de los 12 artículos más recientes.
    listado: `\\set c random(1, ${categories.length})
SELECT a.slug, r.title, r.excerpt, r.cover_media_id, r.reading_minutes, a.published_at
  FROM article a JOIN article_revision r ON r.id = a.published_revision_id
 WHERE a.category_id = (${list(categories.map((c) => c.id))})[:c]
 ORDER BY a.published_at DESC NULLS LAST LIMIT 12;
`,
    // Imágenes de un artículo: registros de media referenciados por sus bloques y su portada.
    imagenes: `\\set i random(1, ${articles.length})
SELECT m.id, m.path, m.width, m.height, m.widths, m.alt, m.caption, m.placeholder, m.focal_x, m.focal_y
  FROM media m
 WHERE m.id IN (SELECT b.media_id FROM article_revision_block_ref b JOIN article a ON a.published_revision_id = b.revision_id
                 WHERE a.slug = (${slugs})[:i] AND b.media_id IS NOT NULL
                UNION SELECT r.cover_media_id FROM article a JOIN article_revision r ON r.id = a.published_revision_id
                 WHERE a.slug = (${slugs})[:i]);
`,
  };
  for (const [name, sql] of Object.entries(scripts))
    fs.writeFileSync(path.join(out, `${name}.sql`), sql);
  return Object.keys(scripts);
}

/** Percentiles de los registros por transacción de pgbench (--log). */
function pgbenchLatencies(out, prefix) {
  const files = fs.readdirSync(out).filter((f) => f.startsWith(prefix));
  const byScript = {};
  const all = [];
  for (const file of files) {
    for (const line of fs.readFileSync(path.join(out, file), 'utf8').split('\n')) {
      const parts = line.trim().split(/\s+/);
      if (parts.length < 6) continue;
      const us = Number(parts[2]);
      const script = parts[3];
      (byScript[script] ??= []).push(us);
      all.push(us);
    }
    fs.rmSync(path.join(out, file));
  }
  const stats = (values) => {
    const s = [...values].sort((a, b) => a - b);
    const p = (q) => Math.round(s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)] / 10) / 100;
    return { transacciones: s.length, p50: p(0.5), p95: p(0.95), p99: p(0.99), max: p(1) };
  };
  return {
    total: stats(all),
    porScript: Object.fromEntries(Object.entries(byScript).map(([k, v]) => [k, stats(v)])),
  };
}

// --- Comandos --------------------------------------------------------------------------------------

async function commandK6(out) {
  if (!opt.script) throw new Error('falta --script');
  writeRoutes(out);
  const results = [];
  const levels = opt.rate ? opt.rate.split(',') : opt.niveles.split(',');
  for (const level of levels) {
    const prefix = `${opt.rate ? 'rate' : 'vus'}-${level}`;
    const sampler = startSampler(out, `muestras-${prefix}`, services());
    await sleep(1000);
    const env = ['-e', `NAME=k6-${prefix}`, '-e', `DURATION=${opt.duracion}`];
    if (opt.rate) env.push('-e', `RATE=${level}`, '-e', `MAX_VUS=${opt['max-vus'] ?? 60}`);
    else env.push('-e', `VUS=${level}`);
    if (opt.modo) env.push('-e', `MODE=${opt.modo}`);
    if (opt.email) env.push('-e', `EMAIL=${opt.email}`);
    if (process.env.SSR_URL) env.push('-e', `SSR=${process.env.SSR_URL}`);
    compose(['run', '--rm', ...env, 'k6', 'run', '--quiet', `/scripts/${opt.script}`], {
      capture: true,
      check: false,
      env: benchEnv(out),
    });
    await sleep(1000);
    const recursos = await stopSampler(sampler, out, `muestras-${prefix}`);
    const carga = JSON.parse(fs.readFileSync(path.join(out, `k6-${prefix}.json`), 'utf8'));
    results.push({ nivel: level, carga, recursos });
    print(`NIVEL ${level}`, {
      carga: {
        ps: carga.porSegundo,
        lat: carga.latenciaMs,
        err: carga.errores,
        to: carga.timeouts,
        abortado: carga.abortado,
      },
      recursos: Object.fromEntries(
        Object.entries(recursos.servicios).map(([k, v]) => [
          k,
          { mem: v.memoriaMiB.max, pico: v.picoKernelMiB, cpu: v.cpuPct },
        ]),
      ),
    });
    if (carga.abortado) {
      console.log('Corte automático: no se prueban niveles superiores.');
      break;
    }
    await sleep(Number(opt.pausa) * 1000);
  }
  return results;
}

async function commandPgbench(out) {
  let scripts;
  let weights = { articulo: 6, listado: 3, imagenes: 1 };
  if (opt.sql) {
    const name = path.basename(opt.sql, '.sql');
    let sql = fs.readFileSync(path.join(BENCH_DIR, 'pgbench', path.basename(opt.sql)), 'utf8');
    // Marcador opcional: el id de un usuario de prueba (una consulta previa, como cms_api).
    if (sql.includes('{{usuario}}')) sql = sql.replaceAll('{{usuario}}', testUserId());
    fs.writeFileSync(path.join(out, `${name}.sql`), sql);
    scripts = [name];
    weights = { [name]: 1 };
  } else {
    scripts = writePgbench(out);
  }
  const results = [];
  for (const clients of opt.clientes.split(',')) {
    const prefix = `pgbench-c${clients}`;
    const sampler = startSampler(out, `muestras-${prefix}`, services());
    await sleep(1000);
    const args = [
      'run',
      '--rm',
      'pgbench',
      '-n',
      '-c',
      clients,
      '-j',
      String(Math.min(Number(clients), 2)),
      '-T',
      String(parseInt(opt.duracion, 10)),
      '--log',
      `--log-prefix=/out/${prefix}`,
      ...scripts.flatMap((s) => ['-f', `/out/${s}.sql@${weights[s]}`]),
    ];
    const res = compose(args, { capture: true, check: false, env: benchEnv(out) });
    const recursos = await stopSampler(sampler, out, `muestras-${prefix}`);
    const tps = res.stdout.match(/tps = ([\d.]+)/);
    const failed = res.stdout.match(/number of failed transactions: (\d+)/);
    const latencias = pgbenchLatencies(out, `${prefix}.`);
    const carga = {
      clientes: Number(clients),
      tps: tps ? Number(tps[1]) : null,
      fallidas: failed ? Number(failed[1]) : null,
      latenciaMs: latencias,
    };
    fs.writeFileSync(path.join(out, `${prefix}.txt`), res.stdout + res.stderr);
    results.push({ nivel: clients, carga, recursos });
    print(`CLIENTES ${clients}`, {
      tps: carga.tps,
      fallidas: carga.fallidas,
      lat: latencias.total,
      pg: recursos.servicios.postgres?.memoriaMiB,
      cpu: recursos.servicios.postgres?.cpuPct,
    });
    await sleep(Number(opt.pausa) * 1000);
  }
  return results;
}

async function commandMedia(out, extraEnv = {}) {
  const name = `cms-bench-media-${Date.now()}`;
  const env = ['-e', `MODE=${opt.modo ?? 'derive'}`, '-e', `IMAGES=${opt.imagenes}`];
  if (opt.concurrencia) env.push('-e', `CONCURRENCY=${opt.concurrencia}`);
  const memEnv = opt.mem ? { MEDIA_MEM: opt.mem } : {};
  compose(['run', '-d', '--name', name, ...env, 'media'], {
    capture: true,
    env: { ...benchEnv(out), ...memEnv, ...extraEnv },
  });
  const id = run('docker', ['inspect', '-f', '{{.Id}}', name], { capture: true }).stdout.trim();
  // Verificación de montajes del contenedor real, antes de que procese nada importante.
  const mounts = JSON.parse(
    run('docker', ['inspect', '-f', '{{json .Mounts}}', name], { capture: true }).stdout,
  );
  const targets = mounts.map((m) => `${m.Destination}${m.RW ? '' : ':ro'}`).sort();
  if (JSON.stringify(targets) !== JSON.stringify(['/app/src/bench.mjs:ro', '/bench-in:ro'])) {
    run('docker', ['rm', '-f', name], { capture: true, check: false });
    throw new Error(`montajes inesperados en ${name}: ${targets.join(', ')}`);
  }
  const sampler = startSampler(out, 'muestras-media', [`id:media=${id}`, ...services()], 250);
  // Espera asíncrona: en la carga combinada, k6 sigue generando carga mientras tanto.
  const exit = Number((await runAsync('docker', ['wait', name])).stdout.trim());
  const state = JSON.parse(
    run('docker', ['inspect', '-f', '{{json .State}}', name], { capture: true }).stdout,
  );
  const logs = run('docker', ['logs', name], { capture: true, check: false });
  const recursos = await stopSampler(sampler, out, 'muestras-media');
  run('docker', ['rm', '-f', name], { capture: true, check: false });
  fs.writeFileSync(path.join(out, 'media.log'), logs.stdout + logs.stderr);
  const line = (logs.stdout ?? '').split('\n').find((l) => l.startsWith('RESULTADO '));
  const result = {
    salida: exit,
    oomKilled: state.OOMKilled,
    resultado: line ? JSON.parse(line.slice(10)) : null,
    error: line ? null : (logs.stderr ?? '').trim().split('\n').slice(-3).join(' | '),
    recursos,
  };
  print('MEDIA', {
    salida: exit,
    oom: state.OOMKilled,
    ms: result.resultado?.totalMs,
    pico: result.resultado?.picoCgroupMiB,
    rss: result.resultado?.rssMaxMiB,
    muestreo: recursos.servicios.media?.memoriaMiB,
    cpu: recursos.servicios.media?.cpuPct,
    error: result.error,
  });
  return result;
}

async function commandCombined(out) {
  writeRoutes(out);
  const scripts = writePgbench(out);
  const seconds = parseInt(opt.duracion, 10) || 180;
  const sampler = startSampler(out, 'muestras-combinada', services());
  const background = [];
  if (Number(opt.pgbench) > 0) {
    background.push(
      new Promise((resolve) => {
        const child = spawn(
          'docker',
          [
            ...composeArgs,
            'run',
            '--rm',
            'pgbench',
            '-n',
            '-c',
            opt.pgbench,
            '-j',
            '2',
            '-T',
            String(seconds),
            '-R',
            '20',
            '--log',
            '--log-prefix=/out/pgbench-combinada',
            ...scripts.flatMap((s) => ['-f', `/out/${s}.sql`]),
          ],
          { cwd: REPO, env: { ...process.env, ...benchEnv(out) } },
        );
        let text = '';
        child.stdout.on('data', (d) => (text += d));
        child.on('close', () => resolve({ pgbench: text }));
      }),
    );
  }
  const k6 = runAsync(
    'docker',
    [
      ...composeArgs,
      'run',
      '--rm',
      '-e',
      'NAME=k6-combinada',
      '-e',
      `DURATION=${seconds}s`,
      '-e',
      `RATE=${opt.rate ?? 20}`,
      '-e',
      `API_RATE=${opt['api-rate']}`,
      'k6',
      'run',
      '--quiet',
      '/scripts/combinada.js',
    ],
    benchEnv(out),
  );
  let mediaResult = null;
  if (opt.imagen) {
    // La foto empieza a los 30 s, con la carga web ya estable, y se procesa durante ella.
    await sleep(30000);
    opt.imagenes = opt.imagen;
    const mediaDir = path.join(out, 'media');
    fs.mkdirSync(mediaDir, { recursive: true });
    mediaResult = await commandMedia(mediaDir);
  }
  await k6;
  const bg = await Promise.all(background);
  const recursos = await stopSampler(sampler, out, 'muestras-combinada');
  const carga = JSON.parse(fs.readFileSync(path.join(out, 'k6-combinada.json'), 'utf8'));
  const pg = bg.length
    ? {
        ...pgbenchLatencies(out, 'pgbench-combinada.'),
        salida: (bg[0].pgbench.match(/tps = [\d.]+/) ?? [''])[0],
      }
    : null;
  print('COMBINADA', {
    ps: carga.porSegundo,
    porTipo: carga.porTipo,
    err: carga.errores,
    to: carga.timeouts,
    pg: pg?.total,
    media: mediaResult?.resultado?.totalMs,
    recursos: Object.fromEntries(
      Object.entries(recursos.servicios).map(([k, v]) => [
        k,
        { mem: v.memoriaMiB.max, pico: v.picoKernelMiB, cpu: v.cpuPct, oom: v.oomKills },
      ]),
    ),
  });
  return { carga, pgbench: pg, media: mediaResult, recursos };
}

/**
 * PostgreSQL pausado (docker pause = procesos congelados, conexiones TCP abiertas): cuánto tarda
 * /actuator/health en responder DOWN, tiempos agotados y estado del pool de Hikari.
 */
/** Máximo sin desplegar el array (con cientos de miles de eventos, Math.max(...a) desborda la pila). */
const maxOf = (values) => values.reduce((m, v) => (v > m ? v : m), 0);

async function commandPause(out) {
  const before = 20;
  const paused = Number(opt.segundos === '600' ? 60 : opt.segundos);
  const after = 60;
  const name = `cms-bench-k6-pausa-${Date.now()}`;
  compose(
    [
      'run',
      '-d',
      '--name',
      name,
      '-e',
      `DURATION=${before + paused + after}s`,
      '-e',
      `CARGA_VUS=${opt.clientes === '1' ? 0 : opt.clientes}`,
      '-e',
      `CARGA_MODO=${opt.modo ?? 'health'}`,
      'k6',
      'run',
      '--quiet',
      '/scripts/actuator-pausa.js',
    ],
    { capture: true, env: benchEnv(out) },
  );
  const pg = containerId('postgres');
  await sleep(before * 1000);
  const pausedAt = Date.now();
  run('docker', ['pause', pg], { capture: true });
  console.log(`PostgreSQL pausado ${paused} s`);
  await sleep(paused * 1000);
  run('docker', ['unpause', pg], { capture: true });
  const resumedAt = Date.now();
  run('docker', ['wait', name], { capture: true });
  const apiLog = run(
    'docker',
    ['logs', '--since', new Date(pausedAt - 1000).toISOString(), containerId('api')],
    {
      capture: true,
      check: false,
    },
  );
  const hikari = `${apiLog.stdout}\n${apiLog.stderr}`
    .split('\n')
    .filter((l) =>
      /Connection is not available|HikariPool|connection.*(timed out|failed)/i.test(l),
    );
  fs.writeFileSync(path.join(out, 'api-hikari.log'), hikari.join('\n') + '\n');
  // Registro completo de la API durante la pausa (sin secretos: correlación y clases de excepción).
  fs.writeFileSync(path.join(out, 'api.log'), `${apiLog.stdout}\n${apiLog.stderr}`);
  const logs = run('docker', ['logs', name], { capture: true, check: false });
  run('docker', ['rm', '-f', name], { capture: true, check: false });
  const events = `${logs.stdout}\n${logs.stderr}`
    .split('\n')
    .map((line) => line.match(/EV (\{.*\})/))
    .filter(Boolean)
    .map((m) => JSON.parse(m[1].replaceAll('\\"', '"')));
  fs.writeFileSync(
    path.join(out, 'eventos.jsonl'),
    events.map((e) => JSON.stringify(e)).join('\n') + '\n',
  );
  // Tiempos relativos al inicio de k6 (se alinean con la pausa por la sonda de antes de pausar).
  const probes = events.filter((e) => e.k === 'sonda').sort((a, b) => a.t - b.t);
  const t0 = probes.length ? probes[0].t : 0;
  const pauseT = t0 + before;
  const resumeT = pauseT + (resumedAt - pausedAt) / 1000;
  const answeredAt = (e) => e.t + e.ms / 1000;
  const firstDown = probes
    .filter((e) => e.estado === 'DOWN' || e.http === 503)
    .sort((a, b) => answeredAt(a) - answeredAt(b))[0];
  const firstUpAfter = probes
    .filter((e) => e.t >= resumeT && e.estado === 'UP')
    .sort((a, b) => a.t - b.t)[0];
  const baseline = probes.filter((e) => e.t < pauseT).map((e) => e.ms);
  const during = probes.filter((e) => e.t >= pauseT && e.t < resumeT);
  const load = events.filter((e) => e.k === 'carga');
  // Sondas de la 6.2.7.2: liveness (no debe depender de PostgreSQL) y readiness (sí).
  const byKind = (kind) => events.filter((e) => e.k === kind).sort((a, b) => a.t - b.t);
  const window = (list) => list.filter((e) => e.t >= pauseT && e.t < resumeT);
  const probeSummary = (kind) => {
    const list = byKind(kind);
    if (!list.length) return null;
    const inPause = window(list);
    const down = list
      .filter((e) => e.t >= pauseT && (e.estado === 'DOWN' || e.http === 503))
      .sort((a, b) => answeredAt(a) - answeredAt(b))[0];
    const upAfter = list.filter((e) => e.t >= resumeT && e.estado === 'UP')[0];
    return {
      duranteLaPausa: inPause.length,
      up200: inPause.filter((e) => e.http === 200).length,
      down503: inPause.filter((e) => e.http === 503).length,
      duracionMaxMs: maxOf(inPause.map((e) => e.ms)),
      primerDownSegundos: down ? Math.round((answeredAt(down) - pauseT) * 10) / 10 : null,
      recuperacionSegundos: upAfter ? Math.round((answeredAt(upAfter) - resumeT) * 10) / 10 : null,
    };
  };
  const loadInPause = window(load);
  const pool = events.filter((e) => e.k === 'pool');
  const summary = {
    pausaSegundos: paused,
    usuariosCarga: Number(opt.clientes === '1' ? 0 : opt.clientes),
    sondaAntesMs: {
      media: Math.round(baseline.reduce((a, b) => a + b, 0) / (baseline.length || 1)),
      max: maxOf(baseline),
    },
    primerDown: firstDown
      ? {
          segundosDesdePausaHastaRespuesta: Math.round((answeredAt(firstDown) - pauseT) * 10) / 10,
          duracionPeticionMs: firstDown.ms,
          http: firstDown.http,
        }
      : null,
    sondasIniciadasDuranteLaPausa: {
      total: during.length,
      down503: during.filter((e) => e.http === 503).length,
      // UP tardío: esperaron en el pool y respondieron UP al reanudarse PostgreSQL.
      upTardio: during.filter((e) => e.estado === 'UP' && e.ms > 1000).length,
      timeoutsCliente: during.filter((e) => e.error === 1050).length,
      otrosErrores: during.filter((e) => e.error && e.error !== 1503 && e.error !== 1050).length,
      duracionMaxMs: maxOf(during.map((e) => e.ms)),
    },
    recuperacion: firstUpAfter
      ? {
          segundosDesdeReanudar:
            Math.round((firstUpAfter.t + firstUpAfter.ms / 1000 - resumeT) * 10) / 10,
        }
      : null,
    liveness: probeSummary('liveness'),
    readiness: probeSummary('readiness'),
    carga: load.length
      ? {
          modo: opt.modo ?? 'health',
          peticiones: load.length,
          errores: load.filter((e) => e.error).length,
          http503: load.filter((e) => e.http === 503).length,
          unavailable503: load.filter((e) => e.codigo === 'unavailable').length,
          otrosEstados: load.filter((e) => ![200, 503].includes(e.http)).length,
          duracionMaxMs: maxOf(load.map((e) => e.ms)),
          iniciadasEnLaPausa: loadInPause.length,
          masDe6s: loadInPause.filter((e) => e.ms > 6000).length,
          masDe25s: loadInPause.filter((e) => e.ms > 25000).length,
        }
      : null,
    hikariRegistro: {
      tiemposAgotados: hikari.filter((l) => /Connection is not available/.test(l)).length,
      ejemplo: (hikari.find((l) => /Connection is not available/.test(l)) ?? '').replace(
        /^.*(Connection is not available)/,
        '$1',
      ),
    },
    pool: {
      activasMax: maxOf(pool.map((e) => e.activas ?? 0)),
      pendientesMax: maxOf(pool.map((e) => e.pendientes ?? 0)),
      timeoutsHikari: maxOf(pool.map((e) => e.timeouts ?? 0)),
      metricasSinRespuesta: pool.filter((e) => e.activas === null).length,
    },
  };
  print('PAUSA', summary);
  return summary;
}

/**
 * Qué detecta la JVM de la imagen de la API bajo distintos límites del contenedor: heap
 * máximo, CPU efectivas, recolector elegido, Metaspace, code cache y memoria directa.
 * Usa las opciones de la imagen (o API_JAVA_OPTS) y `java -version`, sin arrancar Spring.
 */
function commandJvmLimits() {
  const mems = (opt.imagenes || '256m,384m,512m,640m,768m,1024m,1536m').split(',');
  const cpuSets = { 1: '0', 2: '0-1', 4: '0-3' };
  const rows = [];
  for (const mem of mems) {
    for (const cpus of (opt.clientes === '1' ? '1,2,4' : opt.clientes).split(',')) {
      const args = [
        'run',
        '--rm',
        '--memory',
        mem,
        '--memory-swap',
        mem,
        '--cpuset-cpus',
        cpuSets[cpus],
        '--entrypoint',
        'java',
      ];
      if (process.env.API_JAVA_OPTS)
        args.push('-e', `JAVA_TOOL_OPTIONS=${process.env.API_JAVA_OPTS}`);
      args.push(
        'cristian-atienza/cms-api:local',
        '-XX:+PrintFlagsFinal',
        '-XshowSettings:system',
        '-version',
      );
      const res = run('docker', args, { capture: true, check: false });
      const text = `${res.stdout}\n${res.stderr}`;
      const flag = (name) => {
        const m = text.match(new RegExp(`\\s${name}\\s+=\\s+(\\S+)`));
        return m ? m[1] : null;
      };
      const mib = (v) => (v === null ? null : Math.round(Number(v) / 1048576));
      const gc = ['UseSerialGC', 'UseG1GC', 'UseParallelGC', 'UseZGC'].find(
        (g) => flag(g) === 'true',
      );
      const row = {
        limite: mem,
        cpus: Number(cpus),
        cpusEfectivas: Number((text.match(/Effective CPU Count:\s+(\d+)/) ?? [])[1] ?? NaN),
        limiteDetectado: (text.match(/Memory Limit:\s+(.+)/) ?? [])[1]?.trim() ?? null,
        heapMaxMiB: mib(flag('MaxHeapSize')),
        heapInicialMiB: mib(flag('InitialHeapSize')),
        gc,
        metaspaceMax:
          flag('MaxMetaspaceSize') === '18446744073709551615'
            ? 'sin límite'
            : mib(flag('MaxMetaspaceSize')),
        codeCacheMiB: mib(flag('ReservedCodeCacheSize')),
        directaMax:
          flag('MaxDirectMemorySize') === '0' ? 'igual al heap' : mib(flag('MaxDirectMemorySize')),
        pilaHiloKiB: Number(flag('ThreadStackSize')),
        compiladoresJit: Number(flag('CICompilerCount')),
      };
      rows.push(row);
      print('JVM', row);
    }
  }
  return rows;
}

// --- Autenticación (6.2.7) -----------------------------------------------------------------------

const LOGIN_EMAIL = 'bench-login@example.test';

/** Consulta en el contenedor de PostgreSQL como cms_api (contraseña del secreto montado allí). */
function apiPsql(sql) {
  const pg = containerId('postgres');
  if (!pg) throw new Error('postgres no está en marcha');
  return run(
    'docker',
    [
      'exec',
      '-i',
      pg,
      'sh',
      '-c',
      'PGPASSWORD="$(cat /run/secrets/cms_api_password)" psql -h localhost -U cms_api -d cms -v ON_ERROR_STOP=1 -At',
    ],
    { capture: true, input: sql },
  ).stdout.trim();
}

function testUserId() {
  const id = apiPsql(`SELECT id FROM app_user WHERE email = '${LOGIN_EMAIL}';`);
  if (!/^[0-9a-f-]{36}$/.test(id)) throw new Error('falta el usuario de prueba: bench.mjs admin');
  return id;
}

/** Usuarios de prueba (ADMIN, P1) con la herramienta de seguridad real, como cms_security_tool. */
function commandAdmin() {
  fs.mkdirSync(CRED, { recursive: true, mode: 0o700 });
  const file = path.join(CRED, 'cms_admin_password');
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) {
    throw new Error(`${file} es una carpeta (creada por Docker sin el archivo): bórrala`);
  }
  if (!fs.existsSync(file)) {
    const random = spawnSync(
      process.execPath,
      ['-e', 'process.stdout.write(require("crypto").randomBytes(24).toString("base64url"))'],
      { encoding: 'utf8' },
    ).stdout;
    fs.writeFileSync(file, random, { mode: 0o600 });
  }
  const emails = (opt.email ?? LOGIN_EMAIL).split(',');
  for (const email of emails) {
    const res = compose(
      [
        'run',
        '--rm',
        'security-tool',
        '--cms.tool=crear-admin',
        `--cms.usuario=${email}`,
        '--cms.contrasena-desde=/run/cms-input/cms_admin_password',
      ],
      { capture: true, check: false, env: { ...benchEnv(ROOT), MSYS_NO_PATHCONV: '1' } },
    );
    if (res.status !== 0 && res.status !== 3) {
      throw new Error(
        `crear-admin → ${res.status}: ${(res.stdout ?? '')
          .split('\n')
          .filter((l) => l.startsWith('Error'))
          .join(' ')}`,
      );
    }
    print('USUARIO', { email, salida: res.status === 0 ? 'creado' : 'ya existía' });
  }
}

/** Bibliotecas de la imagen de la API (las mismas versiones), para medir fuera de Spring. */
function apiLibs() {
  const dir = path.join(ROOT, 'argon2-lib');
  if (!fs.existsSync(path.join(dir, 'lib'))) {
    fs.mkdirSync(dir, { recursive: true });
    const id = run('docker', ['create', 'cristian-atienza/cms-api:local'], {
      capture: true,
    }).stdout.trim();
    try {
      run('docker', ['cp', `${id}:/app/BOOT-INF/lib`, dir], { capture: true });
    } finally {
      run('docker', ['rm', id], { capture: true, check: false });
    }
  }
  return path.join(dir, 'lib');
}

/** Prueba A: un Argon2id con los parámetros de la API, por CPU disponibles y hilos simultáneos. */
function commandArgon2() {
  const lib = apiLibs();
  const cpuSets = { 1: '0', 2: '0-1', 4: '0-3' };
  const rows = [];
  for (const cpus of (opt.clientes === '1' ? '1,2,4' : opt.clientes).split(',')) {
    const res = run(
      'docker',
      [
        'run',
        '--rm',
        '--cpuset-cpus',
        cpuSets[cpus],
        '--memory',
        opt.mem ?? '768m',
        '--memory-swap',
        opt.mem ?? '768m',
        '-e',
        `JAVA_TOOL_OPTIONS=${process.env.ARGON2_JAVA_OPTS ?? '-XX:MaxRAMPercentage=45'}`,
        '-v',
        `${lib}:/applib:ro`,
        '-v',
        `${path.join(BENCH_DIR, 'argon2')}:/src:ro`,
        '--entrypoint',
        'java',
        JDK_IMAGE,
        '-cp',
        '/applib/*',
        '/src/Argon2Bench.java',
        '30',
        opt.niveles === '1' ? '1,2,4,8' : opt.niveles,
      ],
      { capture: true, env: { MSYS_NO_PATHCONV: '1' } },
    );
    for (const line of res.stdout.split('\n').filter((l) => l.startsWith('{'))) {
      const row = { cpus: Number(cpus), ...JSON.parse(line) };
      rows.push(row);
      print('ARGON2', row);
    }
  }
  return rows;
}

/**
 * Memoria de la JVM de la API: NMT (si la API arrancó con -XX:NativeMemoryTracking=summary) y heap,
 * con jcmd desde la imagen de JDK en el espacio de procesos del contenedor (la imagen de la API es
 * un JRE sin jcmd), más el cgroup del contenedor.
 */
function commandNmt(out) {
  const api = containerId('api');
  if (!api) throw new Error('la API no está en marcha');
  const jcmd = (what) =>
    run(
      'docker',
      [
        'run',
        '--rm',
        `--pid=container:${api}`,
        '--user',
        '999:999',
        '--entrypoint',
        'jcmd',
        JDK_IMAGE,
        '1',
        ...what,
      ],
      { capture: true, check: false },
    );
  const nmt = jcmd(['VM.native_memory', 'summary']);
  const heap = jcmd(['GC.heap_info']);
  const cgroup = run(
    'docker',
    [
      'exec',
      api,
      'sh',
      '-c',
      'echo actual=$(cat /sys/fs/cgroup/memory.current) pico=$(cat /sys/fs/cgroup/memory.peak); grep -E "^(anon|file|kernel|sock) " /sys/fs/cgroup/memory.stat',
    ],
    { capture: true, check: false },
  );
  fs.writeFileSync(path.join(out, 'nmt.txt'), nmt.stdout + nmt.stderr);
  fs.writeFileSync(path.join(out, 'heap.txt'), heap.stdout + heap.stderr);
  fs.writeFileSync(path.join(out, 'cgroup.txt'), cgroup.stdout);
  const mb = (label) => {
    const m = nmt.stdout.match(new RegExp(`-\\s+${label} \\(reserved=\\d+KB, committed=(\\d+)KB`));
    return m ? Math.round(Number(m[1]) / 1024) : null;
  };
  const total = nmt.stdout.match(/Total: reserved=\d+KB, committed=(\d+)KB/);
  // «actual=… pico=…» en la primera línea; después, «anon N», «file N»… de memory.stat.
  const stat = Object.fromEntries(
    [...cgroup.stdout.matchAll(/\b(actual|pico|anon|file|kernel|sock)[= ](\d+)/g)].map((m) => [
      m[1],
      m[2],
    ]),
  );
  // El committed de Metaspace está en la línea siguiente a «Metadata:».
  const metaspace = nmt.stdout.match(/Metadata:[^\n]*\n[^\n]*committed=(\d+)KB/);
  const mib = (v) => (v === undefined ? null : Math.round(Number(v) / 1048576));
  const summary = {
    nmtTotalMiB: total ? Math.round(Number(total[1]) / 1024) : null,
    heapMiB: mb('Java Heap'),
    clasesMiB: mb('Class'),
    hilosMiB: mb('Thread'),
    codigoMiB: mb('Code'),
    gcMiB: mb('GC'),
    otrosMiB: mb('Other'),
    simbolosMiB: mb('Symbol'),
    metaspaceMiB: metaspace ? Math.round(Number(metaspace[1]) / 1024) : null,
    hilos: Number((nmt.stdout.match(/\(threads #(\d+)\)/) ?? [])[1] ?? NaN),
    heapUsadoKiB: Number((heap.stdout.match(/used (\d+)K/) ?? [])[1] ?? NaN),
    cgroupActualMiB: mib(stat.actual),
    cgroupPicoMiB: mib(stat.pico),
    anonimaMiB: mib(stat.anon),
    cacheMiB: mib(stat.file),
    nmtDisponible: !!total,
  };
  print('NMT', summary);
  return summary;
}

/** Sesiones caducadas sintéticas (como cms_api, que ya puede escribir spring_session). */
function commandSessions() {
  const count = Number(opt.clientes === '1' ? 10000 : opt.clientes);
  const before = apiPsql('SELECT count(*) FROM spring_session;');
  apiPsql(`INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,
      max_inactive_interval, expiry_time, principal_name)
    SELECT gen_random_uuid()::text, gen_random_uuid()::text, 0, 0, 1800, 1, gen_random_uuid()::text
      FROM generate_series(1, ${count});`);
  // Coste del DELETE de la limpieza de Spring Session, sin aplicarlo (ROLLBACK).
  const plan = apiPsql(`BEGIN; EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
    DELETE FROM spring_session WHERE expiry_time < (extract(epoch FROM now()) * 1000)::bigint; ROLLBACK;`);
  const result = {
    antes: Number(before),
    insertadas: count,
    despues: Number(apiPsql('SELECT count(*) FROM spring_session;')),
    limpiezaMs: Number((plan.match(/Execution Time: ([\d.]+) ms/) ?? [])[1] ?? NaN),
    plan: plan
      .split('\n')
      .filter((l) => /Scan|Delete|Execution/.test(l))
      .map((l) => l.trim()),
  };
  print('SESIONES', result);
  return result;
}

/**
 * Estado de autenticación vacío para que una variante no contamine a otra (6.2.7.3): sesiones de
 * Spring Session, contadores de intentos (y modo de ataque), fallos por sesión y challenges
 * pendientes. Como cms_api, con los permisos que ya tiene sobre esas tablas (V7–V9). La auditoría y
 * la procedencia de las aserciones son de solo inserción: crecen igual para todas las variantes,
 * porque el orden rota entre rondas. Los dispositivos no se tocan: cada ejecución usa usuarios
 * nuevos.
 */
function commandReset() {
  const counts =
    apiPsql(`SELECT (SELECT count(*) FROM spring_session) || ' ' || (SELECT count(*) FROM login_attempt)
    || ' ' || (SELECT count(*) FROM auth_session_failure) || ' ' || (SELECT count(*) FROM webauthn_challenge);`);
  apiPsql(`DELETE FROM spring_session; DELETE FROM login_attempt; DELETE FROM auth_session_failure;
    DELETE FROM webauthn_challenge;`);
  const [sesiones, intentos, fallos, challenges] = counts.split(' ').map(Number);
  const after =
    apiPsql(`SELECT (SELECT count(*) FROM spring_session) + (SELECT count(*) FROM login_attempt)
    + (SELECT count(*) FROM auth_session_failure) + (SELECT count(*) FROM webauthn_challenge);`);
  const result = { borrados: { sesiones, intentos, fallos, challenges }, restantes: Number(after) };
  print('RESET', result);
  return result;
}

/** Pausas del recolector desde el arranque de la API (registro -Xlog:gc en la salida estándar). */
function commandGc(out) {
  const api = containerId('api');
  if (!api) throw new Error('la API no está en marcha');
  const logs = run('docker', ['logs', api], { capture: true, check: false });
  const lines = `${logs.stdout}\n${logs.stderr}`.split('\n').filter((l) => /\[gc\s*\]/.test(l));
  fs.writeFileSync(path.join(out, 'gc.log'), lines.join('\n') + '\n');
  const pauses = lines
    .map((l) => l.match(/Pause (Young|Full|Remark|Cleanup)[^\n]*? ([\d.]+)ms\s*$/))
    .filter(Boolean)
    .map((m) => ({ tipo: m[1], ms: Number(m[2]) }));
  const sorted = pauses.map((p) => p.ms).sort((a, b) => a - b);
  const q = (p) =>
    sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] : null;
  const result = {
    pausas: sorted.length,
    completas: pauses.filter((p) => p.tipo === 'Full').length,
    p50Ms: q(0.5),
    p95Ms: q(0.95),
    maxMs: sorted.length ? sorted[sorted.length - 1] : null,
    totalMs: Math.round(sorted.reduce((a, b) => a + b, 0)),
    recolector: (lines.find((l) => /Using /.test(l)) ?? '').replace(/^.*Using /, '').trim() || null,
  };
  print('GC', result);
  return result;
}

async function main() {
  if (!command) throw new Error('falta el comando');
  const guarded = guard();
  if (command === 'comprobar')
    return print('PROTECCIONES', { proyecto: PROJECT, volumenes: guarded.volumes, raiz: ROOT });
  if (command === 'admin') return commandAdmin();
  if (command === 'preparar') {
    compose(['up', '-d', '--wait', 'postgres'], { env: benchEnv(ROOT) });
    // Migraciones como cms_migrator; después, la importación como cms_tool.
    compose(['run', '--rm', 'migrate'], { capture: true, env: benchEnv(ROOT) });
    const res = compose(
      ['run', '--rm', 'content-tool', '--cms.tool=import', '--cms.content-dir=/content'],
      {
        capture: true,
        check: false,
        env: { ...benchEnv(ROOT), BENCH_CONTENT: process.env.BENCH_CONTENT ?? '../../src/content' },
      },
    );
    console.log(
      res.stdout
        .split('\n')
        .filter((l) => /Importaci|Sin cambios|Error|^  /.test(l))
        .join('\n'),
    );
    return;
  }
  const name = opt.nombre ?? command;
  const out = newRun(name);
  let result;
  if (command === 'reposo') {
    const sampler = startSampler(out, 'muestras-reposo', services(), 1000);
    await sleep(Number(opt.segundos) * 1000);
    result = await stopSampler(sampler, out, 'muestras-reposo');
    print(
      'REPOSO',
      Object.fromEntries(
        Object.entries(result.servicios).map(([k, v]) => [
          k,
          {
            mem: v.memoriaMiB,
            anon: v.anonimaMiB.max,
            cache: v.cacheMiB.max,
            pico: v.picoKernelMiB,
            cpu: v.cpuPct,
            procesos: v.procesosMax,
          },
        ]),
      ),
    );
  } else if (command === 'k6') result = await commandK6(out);
  else if (command === 'pgbench') result = await commandPgbench(out);
  else if (command === 'media') result = await commandMedia(out);
  else if (command === 'combinada') result = await commandCombined(out);
  else if (command === 'pausa') result = await commandPause(out);
  else if (command === 'jvm-limites') result = commandJvmLimits();
  else if (command === 'argon2') result = commandArgon2();
  else if (command === 'nmt') result = commandNmt(out);
  else if (command === 'sesiones') result = commandSessions();
  else if (command === 'reset') result = commandReset();
  else if (command === 'gc') result = commandGc(out);
  else throw new Error(`comando desconocido: ${command}`);
  fs.writeFileSync(path.join(out, 'resultado.json'), `${JSON.stringify(result, null, 2)}\n`);
  console.log(`Resultados: ${out}`);
}

main().catch((error) => {
  console.error(`ERROR: ${error.message}`);
  process.exit(1);
});
