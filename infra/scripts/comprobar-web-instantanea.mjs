#!/usr/bin/env node
// Comprobación del build de instantáneas de la web (fase 6.3.2). Desde la raíz, con los dos builds
// hechos (`npm run build` y `npm run build:snapshot`) y una instantánea generada por la API:
//
//   node infra/scripts/comprobar-web-instantanea.mjs <carpeta de instantáneas> <versión> [<build de referencia>]
//
// 1. Build convencional frente a uno de referencia (opcional, hecho antes de los cambios): todos los
//    archivos del navegador idénticos byte a byte, y las 20 páginas prerenderizadas idénticas salvo
//    la numeración de las plantillas de hidratación, que Angular no genera de forma determinista.
// 2. Arranca los dos servidores y compara el HTML de las 20 rutas: prerenderizado (JSON) frente a
//    renderizado en el servidor (instantánea).
// 3. Privacidad: ningún valor privado de los originales (archivo, SHA-256, DNG, URL), ninguna clave
//    "original" y ningún hash de 64 caracteres en los bundles del navegador del build de
//    instantáneas, en las respuestas HTTP de /content/ ni en el HTML y el TransferState de las 20
//    rutas. Nunca imprime un valor privado: solo dónde aparece.
// 4. Rutas: peticiones crudas fuera de la lista cerrada de /content/ reciben un 404.
//
// Sale con 0 si todo es correcto y con 1 si algo falla.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { connect } from 'node:net';
import { join, relative, sep } from 'node:path';

const [snapshotsDir, version, referenceDir] = process.argv.slice(2);
if (!snapshotsDir || !version) {
  console.error(
    'Uso: comprobar-web-instantanea.mjs <carpeta de instantáneas> <versión> [<build de referencia>]',
  );
  process.exit(1);
}
const JSON_BUILD = 'dist/cristian-atienza';
const SNAPSHOT_BUILD = 'dist/cristian-atienza-instantaneas';
const JSON_PORT = 4310;
const SNAPSHOT_PORT = 4311;
const INTERNAL_PORT = 4312;
const failures = [];
const fail = (message) => failures.push(message);

const walk = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
  );
const rel = (base, file) => relative(base, file).split(sep).join('/');
const BINARY = /\.(avif|webp|png|jpe?g|ico|woff2?|ttf)$/;

// --- Normalización del HTML ----------------------------------------------------------------------

/** Renumera las plantillas de hidratación ("t17") por orden de aparición: misma estructura, mismo texto. */
function renumberTemplates(text) {
  const ids = new Map();
  return text.replace(/"t(\d+)"/g, (_, n) => {
    if (!ids.has(n)) ids.set(n, ids.size);
    return `"t${ids.get(n)}"`;
  });
}

function sortKeys(value) {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((k) => [k, sortKeys(value[k])]),
    );
  }
  return value;
}

function normalize(html, { assets = false } = {}) {
  let out = html.replace(
    /(<script id="ng-state" type="application\/json">)(.*?)(<\/script>)/s,
    (_, open, json, close) => {
      const state = JSON.parse(json);
      delete state['content:snapshot-version'];
      // El orden de las claves del estado no se ve en la página: la instantánea usa el orden
      // canónico del contrato y src/content el suyo. Se compara con las claves ordenadas.
      return open + renumberTemplates(JSON.stringify(sortKeys(state))) + close;
    },
  );
  out = out.replace(/ng-server-context="ss[gr]"/g, 'ng-server-context="X"');
  if (assets)
    out = out.replace(/\b(chunk|main|polyfills|styles)-[A-Za-z0-9_-]{8}\.(js|css)/g, '$1-X.$2');
  return out;
}

function firstDifference(a, b) {
  let i = 0;
  while (i < a.length && a[i] === b[i]) i++;
  return `posición ${i}: «${a.slice(Math.max(0, i - 60), i + 60)}» ≠ «${b.slice(Math.max(0, i - 60), i + 60)}»`;
}

// --- Rutas y valores privados --------------------------------------------------------------------

const routes = walk(join(JSON_BUILD, 'browser'))
  .filter((f) => f.endsWith('index.html'))
  .map(
    (f) =>
      `/${rel(join(JSON_BUILD, 'browser'), f).replace(/index\.html$/, '')}`.replace(/\/$/, '') ||
      '/',
  )
  .sort();
if (routes.length !== 20)
  fail(`se esperaban 20 rutas prerenderizadas en el build convencional, hay ${routes.length}`);

const internalMedia = JSON.parse(readFileSync(join(snapshotsDir, version, 'media.json'), 'utf8'));
const secrets = new Set();
for (const { original } of internalMedia) {
  for (const value of [
    original.file,
    original.sha256,
    original.url,
    original.raw?.file,
    original.raw?.sha256,
  ]) {
    if (value) secrets.add(value);
  }
}
/** Dónde aparece algo privado en un texto (sin imprimir el valor). */
function leaks(where, text) {
  let count = 0;
  for (const value of secrets) if (text.includes(value)) count++;
  if (count) fail(`${where}: ${count} valores privados de originales`);
  if (/"original"\s*:/.test(text)) fail(`${where}: clave "original"`);
  if (/[0-9a-f]{64}/.test(text)) fail(`${where}: un hash de 64 caracteres`);
}

// --- HTTP ------------------------------------------------------------------------------------------

function raw(port, path, method = 'GET') {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    let data = '';
    socket.setEncoding('latin1');
    socket.on('data', (chunk) => (data += chunk));
    socket.on('error', reject);
    socket.on('end', () => {
      const [head, ...rest] = data.split('\r\n\r\n');
      resolve({ status: Number(head.split(' ')[1]), head, body: rest.join('\r\n\r\n') });
    });
    socket.write(
      `${method} ${path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n`,
      'latin1',
    );
  });
}

const get = async (port, path) => {
  const response = await fetch(`http://localhost:${port}${path}`);
  return { status: response.status, headers: response.headers, text: await response.text() };
};

function start(build, port, env = {}) {
  const child = spawn(process.execPath, [join(build, 'server', 'server.mjs')], {
    env: { ...process.env, PORT: String(port), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (d) => (output += d));
  child.stderr.on('data', (d) => (output += d));
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${build} no arranca:\n${output}`)), 30_000);
    child.stdout.on('data', () => {
      if (output.includes('listening')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.on('exit', (code) => reject(new Error(`${build} terminó (${code}):\n${output}`)));
  });
  return { child, ready, output: () => output };
}

// --- 1. Build convencional frente a la referencia -----------------------------------------------

if (referenceDir) {
  const base = join(JSON_BUILD, 'browser');
  const reference = join(referenceDir, 'browser');
  const current = walk(base)
    .map((f) => rel(base, f))
    .sort();
  const expected = walk(reference)
    .map((f) => rel(reference, f))
    .sort();
  if (JSON.stringify(current) !== JSON.stringify(expected))
    fail('el build convencional no tiene los mismos archivos que la referencia');
  let identical = 0;
  let pages = 0;
  for (const file of expected.filter((f) => current.includes(f))) {
    const a = readFileSync(join(reference, file));
    const b = readFileSync(join(base, file));
    if (file.endsWith('index.html')) {
      pages++;
      const na = normalize(a.toString('utf8'));
      const nb = normalize(b.toString('utf8'));
      if (na !== nb) fail(`build convencional, ${file}: ${firstDifference(na, nb)}`);
    } else if (a.equals(b)) {
      identical++;
    } else {
      fail(`build convencional, ${file}: distinto de la referencia`);
    }
  }
  console.log(
    `1. Build convencional: ${identical} archivos idénticos byte a byte y ${pages} páginas prerenderizadas comparadas.`,
  );
}

// --- 3a. Bundles del build de instantáneas --------------------------------------------------------

const browserFiles = walk(join(SNAPSHOT_BUILD, 'browser')).filter((f) => !BINARY.test(f));
for (const file of browserFiles)
  leaks(`build de instantáneas, ${rel(SNAPSHOT_BUILD, file)}`, readFileSync(file, 'utf8'));
// Ni rastro del contenido editorial: ni los marcadores de las imágenes ni los cuerpos.
const markers = [internalMedia[0].placeholder.slice(30, 90), internalMedia[0].alt];
for (const file of browserFiles) {
  const text = readFileSync(file, 'utf8');
  if (markers.some((m) => text.includes(m)))
    fail(`build de instantáneas, ${rel(SNAPSHOT_BUILD, file)}: contiene contenido editorial`);
}
console.log(
  `3a. Bundles del build de instantáneas: ${browserFiles.length} archivos de texto revisados.`,
);

// --- 2, 3b, 3c y 4. Servidores ---------------------------------------------------------------------

const json = start(JSON_BUILD, JSON_PORT);
// 6.3.3: la web arranca con un estado vacío (sin versión) y se activa por el puerto interno.
const stateDir = mkdtempSync(join(tmpdir(), 'estado-'));
const snapshot = start(SNAPSHOT_BUILD, SNAPSHOT_PORT, {
  CONTENT_SNAPSHOTS_DIR: snapshotsDir,
  CONTENT_STATE_DIR: stateDir,
  WEB_INTERNAL_BIND: `127.0.0.1:${INTERNAL_PORT}`,
});
try {
  await Promise.all([json.ready, snapshot.ready]);
  // 6.3.4: se reserva una época y se activa con ella (como hace la API).
  const internal = (path, body) =>
    fetch(`http://127.0.0.1:${INTERNAL_PORT}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  await internal('/interno/reservar', { epoch: 1 });
  const activation = await internal('/interno/activar', { version, epoch: 1 });
  if (activation.status !== 200) fail(`activar ${version}: estado ${activation.status}`);

  // 2. HTML idéntico en las 20 rutas.
  let equal = 0;
  for (const route of routes) {
    const [a, b] = await Promise.all([get(JSON_PORT, route), get(SNAPSHOT_PORT, route)]);
    if (a.status !== 200 || b.status !== 200) {
      fail(`${route}: estado ${a.status} (JSON) / ${b.status} (instantánea)`);
      continue;
    }
    const na = normalize(a.text, { assets: true });
    const nb = normalize(b.text, { assets: true });
    if (na === nb) equal++;
    else fail(`${route}: HTML distinto, ${firstDifference(na, nb)}`);
    // 3b. HTML y TransferState de la instantánea, también la copia servida desde la caché.
    leaks(`HTML de ${route} (instantánea)`, b.text);
    const cached = await get(SNAPSHOT_PORT, route);
    leaks(`HTML de ${route} (instantánea, segunda petición)`, cached.text);
    if (cached.headers.get('x-cache') === 'HIT' && cached.text !== b.text) {
      fail(`${route}: la caché sirve algo distinto de lo renderizado`);
    }
    const state =
      b.text.match(/<script id="ng-state" type="application\/json">(.*?)<\/script>/s)?.[1] ?? '{}';
    const transferred = JSON.parse(state)['content:snapshot-version'];
    const prerendered = !(route === '/' || route.split('/').length === 3);
    if (!prerendered && transferred !== version)
      fail(`${route}: TransferState sin la versión ${version}`);
  }
  console.log(
    `2. HTML: ${equal} de ${routes.length} rutas idénticas entre el build JSON y el de instantáneas.`,
  );

  // 3c. Respuestas de /content/.
  const collections = [
    'categories',
    'tags',
    'authors',
    'media',
    'galleries',
    'destinations',
    'trips',
    'projects',
    'articles',
  ];
  const slugs = JSON.parse(readFileSync(join(snapshotsDir, version, 'articles.json'), 'utf8')).map(
    (a) => a.slug,
  );
  const paths = [
    '/content/version.json',
    ...collections.map((c) => `/content/${version}/${c}.json`),
    ...slugs.map((s) => `/content/${version}/articles/${s}.json`),
  ];
  for (const path of paths) {
    const response = await get(SNAPSHOT_PORT, path);
    if (response.status !== 200) fail(`${path}: estado ${response.status}`);
    const expected = path.endsWith('version.json')
      ? 'no-cache'
      : 'public, max-age=31536000, immutable';
    if (response.headers.get('cache-control') !== expected)
      fail(`${path}: Cache-Control ${response.headers.get('cache-control')}`);
    leaks(`HTTP ${path}`, response.text);
  }
  const publicMedia = JSON.parse((await get(SNAPSHOT_PORT, `/content/${version}/media.json`)).text);
  const expectedMedia = internalMedia.map(({ original: _original, ...rest }) => rest);
  if (JSON.stringify(publicMedia) !== JSON.stringify(expectedMedia))
    fail('media.json público: no es exactamente el interno sin original');
  console.log(
    `3c. HTTP: ${paths.length} respuestas de /content/ revisadas (media.json = interno sin original).`,
  );

  // 4. Rutas fuera de la lista cerrada, como peticiones crudas.
  const bad = [
    `/content/${version}/manifest.json`,
    `/content/${version}/../${version}/media.json`,
    `/content/${version}/%2e%2e/media.json`,
    `/content/${version}/%252e%252e/media.json`,
    `/content/${version}\\media.json`,
    `/content/${version}/%5cmedia.json`,
    `/content/${version}/media.json%00`,
    '/content/../server/server.mjs',
    '/content/%2e%2e/%2e%2e/package.json',
    `/CONTENT/${version}/media.json`,
    `/content/${version}/articles/..%2fmedia.json`,
  ];
  for (const path of bad) {
    const response = await raw(SNAPSHOT_PORT, path);
    if (response.status !== 404) fail(`${path}: estado ${response.status}, se esperaba 404`);
  }
  const retired = await raw(SNAPSHOT_PORT, '/content/v999999/media.json');
  if (retired.status !== 410)
    fail(`versión que no se sirve: estado ${retired.status}, se esperaba 410`);
  console.log(
    `4. Rutas: ${bad.length} peticiones crudas fuera de la lista cerrada, todas 404 (y 410 para una versión que no se sirve).`,
  );
} catch (error) {
  fail(error.message);
} finally {
  json.child.kill();
  snapshot.child.kill();
  rmSync(stateDir, { recursive: true, force: true });
}

if (failures.length) {
  console.log(`\nResultado: ${failures.length} FALLOS`);
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
console.log('\nResultado: CORRECTO');
