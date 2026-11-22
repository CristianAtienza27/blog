#!/usr/bin/env node
// Comparación controlada de la activación entre las fases 6.3.3 y 6.3.4 (misma máquina, mismas
// condiciones). Desde la raíz del proyecto:
//
//   node infra/bench/scripts/comparar-activacion.mjs <build 6.3.3> <build 6.3.4> <carpeta con v1> <salida.json> [rondas]
//
// Cada build es una carpeta dist/…-instantaneas (con server/server.mjs). Alterna las dos en cada
// ronda (A B, B A, …) para repartir la deriva del entorno, siempre sobre una copia nueva de las
// instantáneas (v1 y una v2 con un título cambiado) y un estado vacío. Mide por separado:
//
//   ssr.sinCarga        render en el servidor de un artículo esquivando la caché (?r=N), en serie
//   ssr.bajoCarga       latencia de las peticiones sin caché de los clientes de carga
//   reserva.*           /interno/reservar (solo 6.3.4: la 6.3.3 no tiene época)
//   activacion.*        /interno/activar (alternando v1 y v2)
//   total.*             reserva + activación (lo que hace la API en P3)
//
// «bajoCarga»: 8 clientes en paralelo, la mitad sin caché, como en comprobar-activacion.mjs.
// Calcula p50, p95, p99, media, mínimo y máximo. Nunca imprime ni guarda contenido.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const [buildA, buildB, source, out, roundsArg] = process.argv.slice(2);
if (!buildA || !buildB || !source || !out) {
  console.error(
    'Uso: comparar-activacion.mjs <build 6.3.3> <build 6.3.4> <carpeta con v1> <salida.json> [rondas]',
  );
  process.exit(1);
}
const ROUNDS = Number(roundsArg ?? 3);
const SSR_SAMPLES = 60;
const IDLE_ACTIVATIONS = 20;
const LOAD_ACTIVATIONS = 8;
const ARTICLE = '/viajes/calas-de-mallorca';
const builds = [
  { name: '6.3.3', dir: resolve(buildA), epoch: false },
  { name: '6.3.4', dir: resolve(buildB), epoch: true },
];

// --- Estadística --------------------------------------------------------------------------------

function stats(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const pick = (p) => sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
  const round = (v) => Math.round(v * 10) / 10;
  return {
    n: sorted.length,
    p50: round(pick(50)),
    p95: round(pick(95)),
    p99: round(pick(99)),
    media: round(sorted.reduce((a, b) => a + b, 0) / sorted.length),
    min: round(sorted[0]),
    max: round(sorted.at(-1)),
  };
}

// --- Instantáneas de prueba ---------------------------------------------------------------------

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

function prepare(work) {
  const snapshots = join(work, 'instantaneas');
  mkdirSync(snapshots, { recursive: true });
  cpSync(join(source, 'v1'), join(snapshots, 'v1'), { recursive: true });
  const v2 = join(snapshots, 'v2');
  cpSync(join(source, 'v1'), v2, { recursive: true });
  const articles = JSON.parse(readFileSync(join(v2, 'articles.json'), 'utf8')).map((a) =>
    a.slug === 'calas-de-mallorca' ? { ...a, title: `${a.title} · v2` } : a,
  );
  writeFileSync(join(v2, 'articles.json'), JSON.stringify(articles, null, 2));
  const manifest = JSON.parse(readFileSync(join(v2, 'manifest.json'), 'utf8'));
  manifest.version = 'v2';
  manifest.files = manifest.files.map((f) => {
    const bytes = readFileSync(join(v2, ...f.path.split('/')));
    return { path: f.path, bytes: bytes.length, sha256: sha256(bytes) };
  });
  manifest.contentSha256 = sha256(
    Buffer.from(manifest.files.map((f) => `${f.path} ${f.sha256}\n`).join('')),
  );
  writeFileSync(join(v2, 'manifest.json'), JSON.stringify(manifest, null, 2));
  return snapshots;
}

// --- Servidor -----------------------------------------------------------------------------------

function start(build, port, internal, snapshots, state) {
  const child = spawn(process.execPath, [join(build.dir, 'server', 'server.mjs')], {
    env: {
      ...process.env,
      PORT: String(port),
      CONTENT_SNAPSHOTS_DIR: snapshots,
      CONTENT_STATE_DIR: state,
      WEB_INTERNAL_BIND: `127.0.0.1:${internal}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', (d) => (output += d));
  child.stderr.on('data', (d) => (output += d));
  const ready = new Promise((ok, fail) => {
    const timer = setTimeout(() => fail(new Error(`no arranca:\n${output}`)), 30_000);
    child.stdout.on('data', () => {
      if (output.includes('listening')) {
        clearTimeout(timer);
        ok();
      }
    });
  });
  return { child, ready };
}

const stop = (server) =>
  new Promise((ok) => {
    server.child.once('exit', ok);
    server.child.kill();
  });

async function timed(fn) {
  const t0 = performance.now();
  const result = await fn();
  return { ms: performance.now() - t0, result };
}

// --- Una ronda con un build ---------------------------------------------------------------------

async function round(build, port, internal) {
  const work = mkdtempSync(join(tmpdir(), 'comparar-'));
  const snapshots = prepare(work);
  const state = join(work, 'estado');
  const server = start(build, port, internal, snapshots, state);
  const page = (path) =>
    fetch(`http://localhost:${port}${path}`).then(async (r) => ({
      status: r.status,
      text: await r.text(),
    }));
  const post = (path, body) =>
    fetch(`http://127.0.0.1:${internal}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(async (r) => ({ status: r.status, body: await r.json() }));
  let epoch = 0;
  let current = 'v1';
  const samples = {
    ssrIdle: [],
    ssrLoad: [],
    reserveIdle: [],
    reserveLoad: [],
    activateIdle: [],
    activateLoad: [],
    totalIdle: [],
    totalLoad: [],
  };

  /** Una activación al otro v (como la API: reservar una época nueva y activar con ella). */
  async function activateNext(reserves, activates, totals) {
    const target = current === 'v1' ? 'v2' : 'v1';
    const t0 = performance.now();
    let body = { version: target };
    if (build.epoch) {
      epoch++;
      const reserved = await timed(() => post('/interno/reservar', { epoch }));
      if (reserved.result.status !== 200) throw new Error(`reservar: ${reserved.result.status}`);
      reserves.push(reserved.ms);
      body = { version: target, epoch };
    }
    const activated = await timed(() => post('/interno/activar', body));
    if (activated.result.status !== 200)
      throw new Error(`activar ${target}: ${activated.result.status}`);
    activates.push(activated.ms);
    totals.push(performance.now() - t0);
    current = target;
  }

  try {
    await server.ready;
    // Primera activación (no se mide: carga inicial) y calentamiento del SSR.
    if (build.epoch) {
      epoch++;
      await post('/interno/reservar', { epoch });
      await post('/interno/activar', { version: 'v1', epoch });
    } else {
      await post('/interno/activar', { version: 'v1' });
    }
    for (let i = 0; i < 10; i++) await page(`${ARTICLE}?calentar=${i}`);

    // 1. SSR sin carga.
    for (let i = 0; i < SSR_SAMPLES; i++) {
      samples.ssrIdle.push((await timed(() => page(`${ARTICLE}?r=${i}`))).ms);
    }
    // 2. Activación sin carga.
    for (let i = 0; i < IDLE_ACTIVATIONS; i++) {
      await activateNext(samples.reserveIdle, samples.activateIdle, samples.totalIdle);
    }
    // 3. Bajo carga: 8 clientes (4 sin caché) mientras se activa varias veces.
    let running = true;
    let n = 0;
    const worker = async (bypass, path) => {
      while (running) {
        const url = bypass ? `${path}?carga=${n++}` : path;
        const t = await timed(() => page(url).catch(() => null));
        if (bypass && t.result?.status === 200) samples.ssrLoad.push(t.ms);
      }
    };
    const workers = [0, 1, 2, 3, 4, 5, 6, 7].map((i) =>
      worker(i % 2 === 1, i % 4 < 2 ? '/' : ARTICLE),
    );
    await new Promise((r) => setTimeout(r, 1500));
    for (let i = 0; i < LOAD_ACTIVATIONS; i++) {
      await activateNext(samples.reserveLoad, samples.activateLoad, samples.totalLoad);
      await new Promise((r) => setTimeout(r, 300));
    }
    running = false;
    await Promise.all(workers);
    return samples;
  } finally {
    await stop(server);
    rmSync(work, { recursive: true, force: true });
  }
}

// --- Rondas alternas ----------------------------------------------------------------------------

const all = Object.fromEntries(
  builds.map((b) => [
    b.name,
    {
      ssrIdle: [],
      ssrLoad: [],
      reserveIdle: [],
      reserveLoad: [],
      activateIdle: [],
      activateLoad: [],
      totalIdle: [],
      totalLoad: [],
    },
  ]),
);
const perRound = [];
for (let r = 0; r < ROUNDS; r++) {
  const order = r % 2 === 0 ? builds : [...builds].reverse();
  for (const build of order) {
    const samples = await round(build, 4460, 4461);
    for (const [key, values] of Object.entries(samples)) all[build.name][key].push(...values);
    perRound.push({
      ronda: r + 1,
      build: build.name,
      activacionBajoCarga: stats(samples.activateLoad),
      ssrSinCarga: stats(samples.ssrIdle),
    });
    console.log(
      `ronda ${r + 1} · ${build.name}: activación bajo carga p50 ${stats(samples.activateLoad)?.p50} ms, SSR sin carga p50 ${stats(samples.ssrIdle)?.p50} ms`,
    );
  }
}

const summary = {};
for (const [name, samples] of Object.entries(all)) {
  summary[name] = {
    ssr: { sinCarga: stats(samples.ssrIdle), bajoCarga: stats(samples.ssrLoad) },
    reserva: { sinCarga: stats(samples.reserveIdle), bajoCarga: stats(samples.reserveLoad) },
    activacion: { sinCarga: stats(samples.activateIdle), bajoCarga: stats(samples.activateLoad) },
    total: { sinCarga: stats(samples.totalIdle), bajoCarga: stats(samples.totalLoad) },
  };
}
const result = {
  fecha: new Date().toISOString(),
  rondas: ROUNDS,
  condiciones: {
    clientesDeCarga: 8,
    sinCache: 4,
    activacionesSinCarga: IDLE_ACTIVATIONS,
    activacionesBajoCarga: LOAD_ACTIVATIONS,
    muestrasSsr: SSR_SAMPLES,
  },
  resumen: summary,
  porRonda: perRound,
};
writeFileSync(out, JSON.stringify(result, null, 2));
console.log(JSON.stringify(summary, null, 2));
