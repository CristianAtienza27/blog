#!/usr/bin/env node
// Prueba de extremo a extremo de la activación (fase 6.3.3) con el servidor real del build de
// instantáneas (`npm run build:snapshot`). Desde la raíz:
//
//   node infra/scripts/comprobar-activacion.mjs <carpeta con la instantánea v1 de la API>
//
// Trabaja en una carpeta temporal (nunca toca el origen): v1 es una copia de la instantánea real,
// v2 la misma con el título de un artículo cambiado y v3 una que no pasa la puerta. Comprueba la
// recuperación desde un volumen vacío, los tiempos de activación, que ninguna página mezcle
// versiones bajo carga, volver atrás, el rechazo, la idempotencia, el reinicio, un puntero dañado,
// la caché y que el puerto público no activa nada. Sale con 0 si todo es correcto y con 1 si no.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [source] = process.argv.slice(2);
if (!source) {
  console.error('Uso: comprobar-activacion.mjs <carpeta con v1>');
  process.exit(1);
}
const BUILD = 'dist/cristian-atienza-instantaneas';
const PORT = 4321;
const INTERNAL = 4322;
const ARTICLE = '/viajes/calas-de-mallorca';
const SLUG = 'calas-de-mallorca';
const V2_TITLE = 'Calas de Mallorca · segunda versión';
const failures = [];
const report = [];
const check = (ok, label) => {
  report.push(`${ok ? 'OK   ' : 'FALLO'} ${label}`);
  if (!ok) failures.push(label);
};

// --- Instantáneas de prueba ------------------------------------------------------------------------

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const work = mkdtempSync(join(tmpdir(), 'activacion-'));
const snapshots = join(work, 'instantaneas');
const state = join(work, 'estado');
const staging = join(work, 'preparadas');
mkdirSync(snapshots);

/** Copia v1 como `version`, aplica `change` a las colecciones y rehace el manifiesto. */
function derive(version, change) {
  const dir = join(staging, version);
  cpSync(join(source, 'v1'), dir, { recursive: true });
  const read = (file) => JSON.parse(readFileSync(join(dir, file), 'utf8'));
  change?.(dir, read);
  const manifest = read('manifest.json');
  manifest.version = version;
  manifest.files = manifest.files.map((f) => {
    const bytes = readFileSync(join(dir, ...f.path.split('/')));
    return { path: f.path, bytes: bytes.length, sha256: sha256(bytes) };
  });
  manifest.contentSha256 = sha256(
    Buffer.from(manifest.files.map((f) => `${f.path} ${f.sha256}\n`).join('')),
  );
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest, null, 2));
}
const editArticles = (fn) => (dir, read) =>
  writeFileSync(join(dir, 'articles.json'), JSON.stringify(read('articles.json').map(fn), null, 2));
derive('v1');
derive(
  'v2',
  editArticles((a) => (a.slug === SLUG ? { ...a, title: V2_TITLE } : a)),
);
derive(
  'v3',
  editArticles((a) => (a.slug === SLUG ? { ...a, tags: ['etiqueta-que-no-existe'] } : a)),
);
const originalTitle = JSON.parse(readFileSync(join(source, 'v1', 'articles.json'), 'utf8')).find(
  (a) => a.slug === SLUG,
).title;
const secrets = JSON.parse(readFileSync(join(source, 'v1', 'media.json'), 'utf8')).flatMap(
  ({ original }) => [original.file, original.sha256, original.url].filter(Boolean),
);

// --- Servidor --------------------------------------------------------------------------------------

function start() {
  const child = spawn(process.execPath, [join(BUILD, 'server', 'server.mjs')], {
    env: {
      ...process.env,
      PORT: String(PORT),
      CONTENT_SNAPSHOTS_DIR: snapshots,
      CONTENT_STATE_DIR: state,
      WEB_INTERNAL_BIND: `127.0.0.1:${INTERNAL}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (d) => (output += d));
  child.stderr.on('data', (d) => (output += d));
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`no arranca:\n${output}`)), 30_000);
    child.stdout.on('data', () => {
      if (output.includes('listening')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.on('exit', (code) => reject(new Error(`terminó (${code}):\n${output}`)));
  });
  return { child, ready, output: () => output };
}
const stop = (server) =>
  new Promise((resolve) => {
    server.child.once('exit', resolve);
    server.child.kill();
  });

const get = async (path, port = PORT) => {
  // localhost: el host que admite la configuración de Angular (security.allowedHosts).
  const r = await fetch(`http://${port === PORT ? 'localhost' : '127.0.0.1'}:${port}${path}`);
  return { status: r.status, headers: r.headers, text: await r.text() };
};
/** 6.3.4: cada activación reserva una época nueva y activa con ella (como la API). */
let epoch = 0;
const internalPost = (path, body) =>
  fetch(`http://127.0.0.1:${INTERNAL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
async function activate(version) {
  const t0 = performance.now();
  epoch++;
  await internalPost('/interno/reservar', { epoch });
  const reserveMs = performance.now() - t0;
  const r = await internalPost('/interno/activar', { version, epoch });
  return { status: r.status, body: await r.json(), ms: performance.now() - t0, reserveMs };
}
const status = async () =>
  (await get('/interno/estado', INTERNAL)).text &&
  JSON.parse((await get('/interno/estado', INTERNAL)).text);
const transferred = (html) =>
  JSON.parse(
    html.match(/<script id="ng-state" type="application\/json">(.*?)<\/script>/s)?.[1] ?? '{}',
  )['content:snapshot-version'];

let server;
try {
  // 1. Recuperación desde un volumen vacío.
  server = start();
  await server.ready;
  check((await get('/_salud/vida')).status === 200, 'volumen vacío: /_salud/vida 200');
  const down = await get('/_salud/listo');
  check(
    down.status === 503 && down.text === '{"status":"DOWN"}',
    'volumen vacío: /_salud/listo 503 DOWN',
  );
  const home503 = await get('/');
  check(
    home503.status === 503 && home503.headers.get('retry-after') === '5',
    'volumen vacío: la portada da 503 con Retry-After',
  );
  check(
    (await get('/content/version.json')).status === 503,
    'volumen vacío: /content/version.json 503',
  );
  check((await get('/favicon.ico')).status === 200, 'volumen vacío: los estáticos se sirven');
  check(
    (await status()).listo === false,
    'volumen vacío: el puerto interno responde (sin versión)',
  );
  const missing = await activate('v1');
  check(missing.status === 422, 'activar v1 antes de que exista en el volumen: 422');
  for (const v of ['v1', 'v2', 'v3'])
    cpSync(join(staging, v), join(snapshots, v), { recursive: true });
  const first = await activate('v1');
  report.push(`INFO  activación de v1: ${first.ms.toFixed(0)} ms`);
  check(
    first.status === 200 && first.body.resultado === 'activada',
    'activar v1 con readiness DOWN: activada',
  );
  check((await get('/_salud/listo')).status === 200, 'tras activar: /_salud/listo 200 UP');
  const article1 = await get(ARTICLE);
  check(
    article1.status === 200 &&
      article1.text.includes(originalTitle) &&
      transferred(article1.text) === 'v1',
    'tras activar: el artículo se sirve con v1',
  );
  check(
    JSON.parse((await get('/content/version.json')).text).version === 'v1',
    'tras activar: /content/version.json = v1',
  );
  check(
    (await get(`/content/v1/articles/${SLUG}.json`)).status === 200,
    'tras activar: /content/v1/ se sirve',
  );

  // 2. Caché.
  const again = await get(ARTICLE);
  check(
    again.headers.get('x-cache') === 'HIT' && again.text === article1.text,
    'caché: la segunda petición es un HIT idéntico',
  );
  check(again.headers.get('cache-control') === 'no-cache', 'caché: el HTML se sirve con no-cache');

  // 3. Activar v2 bajo carga: ninguna página mezcla versiones.
  let running = true;
  const seen = { v1: 0, v2: 0, mixed: 0, errors: 0, total: 0, rendered: 0 };
  const worker = async (path) => {
    while (running) {
      const r = await get(path).catch(() => null);
      seen.total++;
      if (!r || r.status !== 200) {
        seen.errors++;
        continue;
      }
      const version = transferred(r.text);
      const hasV2 = r.text.includes(V2_TITLE);
      const hasV1 =
        r.text.includes(`${originalTitle}<`) || r.text.includes(`"title":"${originalTitle}"`);
      if (path.includes('?')) seen.rendered++;
      if (version === 'v1' && !hasV2 && hasV1) seen.v1++;
      else if (version === 'v2' && hasV2 && !hasV1) seen.v2++;
      else seen.mixed++;
    }
  };
  // La mitad esquiva la caché (un parámetro de consulta): el SSR renderiza durante la activación.
  const paths = ['/', ARTICLE, '/?sin-cache', `${ARTICLE}?sin-cache`];
  const workers = [...Array(8)].map((_, i) => worker(paths[i % paths.length]));
  await new Promise((r) => setTimeout(r, 1500));
  const second = await activate('v2');
  await new Promise((r) => setTimeout(r, 1500));
  running = false;
  await Promise.all(workers);
  report.push(
    `INFO  activación de v2 bajo carga (8 clientes, 4 sin caché): ${second.ms.toFixed(0)} ms (reservar ${second.reserveMs.toFixed(0)} ms + activar ${(second.ms - second.reserveMs).toFixed(0)} ms)`,
  );
  report.push(
    `INFO  peticiones durante la prueba: ${seen.total} (v1: ${seen.v1}, v2: ${seen.v2}, mezcladas: ${seen.mixed}, errores: ${seen.errors}; renderizadas sin caché: ${seen.rendered})`,
  );
  check(
    second.status === 200 && second.body.previous === 'v1',
    'activar v2 bajo carga: activada, con v1 como anterior',
  );
  check(
    seen.mixed === 0 && seen.errors === 0 && seen.v1 > 0 && seen.v2 > 0,
    'bajo carga: 0 páginas mezcladas y 0 errores, con páginas de las dos versiones',
  );
  check(second.ms < 5000, `activación en menos de 5 s (${second.ms.toFixed(0)} ms)`);
  check(
    (await get(`/content/v1/articles/${SLUG}.json`)).status === 200,
    'la anterior (v1) se sigue sirviendo en /content/',
  );

  // 4. Rechazo, volver atrás e idempotencia.
  const rejected = await activate('v3');
  check(
    rejected.status === 422 &&
      JSON.stringify(rejected.body.incidencias).includes('etiqueta-que-no-existe'),
    'v3 no pasa la puerta: 422 con la incidencia',
  );
  check(transferred((await get(ARTICLE)).text) === 'v2', 'tras el rechazo sigue activa v2');
  const back = await activate('v1');
  report.push(`INFO  volver a v1: ${back.ms.toFixed(0)} ms`);
  check(
    back.status === 200 &&
      back.body.previous === 'v2' &&
      (await get(ARTICLE)).text.includes(originalTitle),
    'volver atrás a v1: activada y servida',
  );
  const pointerFile = join(state, 'activa.json');
  const mtime = statSync(pointerFile).mtimeMs;
  const same = await activate('v1');
  check(
    same.body.resultado === 'sin cambios' && statSync(pointerFile).mtimeMs === mtime,
    'activar la versión activa: sin cambios y sin reescribir el puntero',
  );
  check((await get('/content/v3/tags.json')).status === 410, 'una versión que no se sirve: 410');
  check(
    readdirSync(state).sort().join() === 'activa.json,control.json',
    'estado/ solo contiene activa.json y control.json',
  );

  // 5. El puerto público no activa nada.
  const viaPublic = await fetch(`http://localhost:${PORT}/interno/activar`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{"version":"v2"}',
  });
  check(
    viaPublic.status !== 200 && (await status()).activa === 'v1',
    `el puerto público no activa (estado ${viaPublic.status})`,
  );

  // 6. Reinicio: se mantiene la activa (y la anterior).
  await stop(server);
  server = start();
  await server.ready;
  const restarted = await status();
  check(
    restarted.listo && restarted.activa === 'v1' && restarted.anterior === 'v2',
    'reinicio: v1 activa y v2 anterior',
  );
  check((await get(ARTICLE)).text.includes(originalTitle), 'reinicio: se sirve v1');

  // 7. Un puntero dañado: sin versión, y se recupera activando.
  await stop(server);
  writeFileSync(pointerFile, '{"version":"../v1"}');
  server = start();
  await server.ready;
  check(
    (await get('/_salud/listo')).status === 503 && (await get('/')).status === 503,
    'puntero dañado: DOWN y 503, sin volver atrás solo',
  );
  check(
    (await activate('v2')).status === 200 && (await get('/_salud/listo')).status === 200,
    'puntero dañado: se recupera activando v2',
  );

  // 8. Privacidad en lo servido (y en la caché).
  const pages = [
    await get(ARTICLE),
    await get(ARTICLE),
    await get('/'),
    await get('/content/v2/media.json'),
  ];
  const leaked = pages.some(
    (p) => secrets.some((s) => p.text.includes(s)) || /"original"\s*:/.test(p.text),
  );
  check(!leaked, 'ningún valor privado en el HTML, la caché ni /content/');
} catch (error) {
  failures.push(error.message);
  report.push(`FALLO ${error.message}`);
} finally {
  if (server) await stop(server).catch(() => undefined);
  rmSync(work, { recursive: true, force: true });
}

console.log(report.join('\n'));
console.log(`\n${failures.length ? `${failures.length} FALLOS` : 'CORRECTO'}`);
process.exit(failures.length ? 1 : 0);
