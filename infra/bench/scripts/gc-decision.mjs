#!/usr/bin/env node
/**
 * Elección del recolector (fase 6.2.7.3) con la regla aprobada, de forma determinista, a partir de
 * los resultados de las 3 rondas rotadas (<BENCH_ROOT>/resultados/*-6273-r<ronda>-<S|P|G>-*):
 *
 *   1. Condiciones duras, en TODAS las rondas: ningún estado inesperado (ni 5xx ni otros, salvo los
 *      429 esperados), ningún OOMKill ni reinicio, y el pico de memoria de la API (memory.peak del
 *      cgroup durante toda la ejecución) por debajo del 80 % del límite (614 MiB de 768 MiB).
 *   2. Entre las que las cumplen, la de menor p95 del inicio de sesión con 20 usuarios (mediana de
 *      las 3 rondas).
 *   3. Desempate: si varias quedan dentro del 15 % de la mejor p95, primero el menor pico de memoria
 *      y después la menor pausa máxima del recolector (los dos, el peor valor de las 3 rondas).
 *
 * Uso: node infra/bench/scripts/gc-decision.mjs [registro-de-las-rondas.log]
 */
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(import.meta.dirname, '../../..');
const ROOT = path.resolve(process.env.BENCH_ROOT ?? path.join(REPO, '../blog-bench'));
const DIR = path.join(ROOT, 'resultados');
const LIMIT_MIB = 768;
const PEAK_MAX = LIMIT_MIB * 0.8;
const MARGIN = 0.15;
const NAMES = { S: 'SerialGC', P: 'ParallelGC', G: 'G1' };

const dirs = fs.readdirSync(DIR).filter((d) => /-6273-r\d-[SPG]-/.test(d));
const read = (round, v, what) => {
  const d = dirs.find((x) => x.endsWith(`-6273-r${round}-${v}-${what}`));
  return d ? JSON.parse(fs.readFileSync(path.join(DIR, d, 'resultado.json'), 'utf8')) : null;
};
const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
};
const maxOf = (xs) => xs.reduce((m, x) => (x > m ? x : m), -Infinity);

// OOM y reinicios por ronda y variante, del registro de las rondas.
const log = process.argv[2] ? fs.readFileSync(process.argv[2], 'utf8') : '';
const oom = {};
let current = null;
for (const line of log.split('\n')) {
  const m = line.match(/===== ronda (\d) · ([SPG])/);
  if (m) current = `${m[1]}${m[2]}`;
  const o = line.match(/^OOM=(\w+) reinicios=(\d+)/);
  if (o && current) oom[current] = o[1] !== 'false' || Number(o[2]) > 0;
}

const unexpected = (carga) =>
  Object.entries(carga.estados ?? {})
    .filter(([k]) => /otros/.test(k))
    .reduce((n, [, v]) => n + v, 0);

const rows = {};
for (const v of ['S', 'P', 'G']) {
  const rounds = [];
  for (const r of [1, 2, 3]) {
    const login20 = read(r, v, 'login-20')?.[0];
    const login40 = read(r, v, 'login-40')?.[0];
    const me = read(r, v, 'me-10')?.[0];
    const csrf = read(r, v, 'csrf-40')?.[0];
    const stepup = read(r, v, 'stepup-10')?.[0];
    const nmt = read(r, v, 'nmt-tras-carga');
    const gc = read(r, v, 'gc');
    if (!login20 || !nmt || !gc) continue;
    const loads = [login20, login40, me, csrf, stepup].filter(Boolean);
    rounds.push({
      ronda: r,
      loginsPorSegundo: login20.carga.estados?.login_200 / 60,
      loginP95: login20.carga.duraciones?.login_200?.p95,
      loginP99: login20.carga.duraciones?.login_200?.p99,
      login40: {
        ok: login40?.carga.estados?.login_200 ?? 0,
        saturated: login40?.carga.estados?.login_429_saturated ?? 0,
      },
      meP95: me?.carga.porTipo?.me?.p95,
      mePorSegundo: me?.carga.porSegundo,
      csrfP95: csrf?.carga.porTipo?.csrf?.p95,
      stepupP95: stepup?.carga.porTipo?.stepup?.p95,
      stepups: stepup?.carga.estados?.stepup_204 ?? 0,
      inesperados: loads.reduce((n, l) => n + unexpected(l.carga), 0),
      erroresK6: loads.reduce((n, l) => n + (l.carga.errores ?? 0), 0),
      abortados: loads.filter((l) => l.carga.abortado).length,
      picoMiB: nmt.cgroupPicoMiB,
      heapComprometidoMiB: nmt.heapMiB,
      nmtTotalMiB: nmt.nmtTotalMiB,
      gcNativaMiB: nmt.gcMiB,
      metaspaceMiB: nmt.metaspaceMiB,
      pausaMaxMs: gc.maxMs,
      pausaP95Ms: gc.p95Ms,
      pausasCompletas: gc.completas,
      pausaTotalMs: gc.totalMs,
      oom: oom[`${r}${v}`] ?? null,
    });
  }
  const ok =
    rounds.length === 3 &&
    rounds.every((x) => x.inesperados === 0 && x.oom === false && x.picoMiB < PEAK_MAX);
  rows[v] = {
    recolector: NAMES[v],
    rondas: rounds,
    cumpleCondicionesDuras: ok,
    loginP95Mediana: rounds.length ? median(rounds.map((x) => x.loginP95)) : null,
    picoPeorMiB: rounds.length ? maxOf(rounds.map((x) => x.picoMiB)) : null,
    pausaMaxPeorMs: rounds.length ? maxOf(rounds.map((x) => x.pausaMaxMs)) : null,
  };
}

const eligible = Object.entries(rows).filter(([, r]) => r.cumpleCondicionesDuras);
let decision;
if (!eligible.length) {
  decision = {
    elegido: null,
    motivo: 'ninguna variante cumple las condiciones duras: no se aplica nada',
  };
} else {
  const best = Math.min(...eligible.map(([, r]) => r.loginP95Mediana));
  const tied = eligible.filter(([, r]) => r.loginP95Mediana <= best * (1 + MARGIN));
  tied.sort(([, a], [, b]) => a.picoPeorMiB - b.picoPeorMiB || a.pausaMaxPeorMs - b.pausaMaxPeorMs);
  const [chosen] = tied[0];
  decision = {
    elegido: NAMES[chosen],
    mejorP95Ms: best,
    dentroDelMargen: tied.map(([v]) => NAMES[v]),
    motivo:
      tied.length === 1
        ? 'la menor p95 del inicio de sesión, fuera del margen del 15 % de las demás'
        : 'empate dentro del 15 % en p95: decide el menor pico de memoria y, si sigue, la menor pausa máxima',
    criterio6: { p95MenorDe1500ms: rows[chosen].rondas.every((x) => x.loginP95 < 1500) },
  };
}
const out = {
  limiteMiB: LIMIT_MIB,
  picoMaximoMiB: PEAK_MAX,
  margen: MARGIN,
  variantes: rows,
  decision,
};
console.log(JSON.stringify(out, null, 2));
