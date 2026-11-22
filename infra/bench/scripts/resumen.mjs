#!/usr/bin/env node
/**
 * Resume los resultados de bench.mjs en tablas Markdown (fase 6.1.4).
 *
 *   node infra/bench/scripts/resumen.mjs [carpeta de resultados] [filtro]
 *
 * Una fila por nivel de carga: rendimiento, latencias, errores y recursos de cada servicio.
 * Para las repeticiones (nombres «repN-…»), añade la mediana y la dispersión ((máx-mín)/mediana).
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(
  process.argv[2] ?? path.join(import.meta.dirname, '../../../../blog-bench/resultados'),
);
const filter = process.argv[3] ?? '';
const dirs = fs
  .readdirSync(root)
  .filter((d) => d.includes(filter))
  .sort();

const fmt = (v, d = 1) =>
  v === null || v === undefined || Number.isNaN(v) ? '–' : Number(v).toFixed(d);
const resources = (r) =>
  Object.entries(r?.servicios ?? {})
    .map(([name, s]) => `${name} ${fmt(s.memoriaMiB.max)} MiB / ${fmt(s.cpuPct.media, 0)} %`)
    .join('; ');

const rows = [];
for (const dir of dirs) {
  const file = path.join(root, dir, 'resultado.json');
  if (!fs.existsSync(file)) continue;
  const result = JSON.parse(fs.readFileSync(file, 'utf8'));
  const name = dir.replace(/^\d{8}-\d{6}-/, '');
  if (!Array.isArray(result)) continue;
  for (const level of result) {
    const c = level.carga;
    if (!c) continue; // resultados sin carga (p. ej. jvm-limites)
    if (c.tps !== undefined) {
      rows.push({
        name,
        nivel: `${c.clientes} cli`,
        ps: c.tps,
        p50: c.latenciaMs.total.p50,
        p95: c.latenciaMs.total.p95,
        p99: c.latenciaMs.total.p99,
        err: c.fallidas,
        to: 0,
        rec: resources(level.recursos),
      });
    } else {
      rows.push({
        name,
        nivel: c.modelo,
        ps: c.porSegundo,
        p50: c.latenciaMs?.p50,
        p95: c.latenciaMs?.p95,
        p99: c.latenciaMs?.p99,
        err: c.errores,
        to: c.timeouts,
        rec: resources(level.recursos),
      });
    }
  }
}

console.log(
  '| Escenario | Nivel | pet/s | p50 ms | p95 ms | p99 ms | Errores | Timeouts | Memoria máx. / CPU media |',
);
console.log('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |');
for (const r of rows) {
  console.log(
    `| ${r.name} | ${r.nivel} | ${fmt(r.ps)} | ${fmt(r.p50, 2)} | ${fmt(r.p95, 2)} | ${fmt(r.p99, 2)} | ${r.err} | ${r.to} | ${r.rec} |`,
  );
}

// Repeticiones: mediana y dispersión por escenario.
const groups = {};
for (const r of rows.filter((x) => /^rep\d-/.test(x.name))) {
  (groups[r.name.replace(/^rep\d-/, '')] ??= []).push(r);
}
if (Object.keys(groups).length) {
  const median = (v) => [...v].sort((a, b) => a - b)[Math.floor(v.length / 2)];
  const spread = (v) => ((Math.max(...v) - Math.min(...v)) / median(v)) * 100;
  console.log(
    '\n| Escenario (3 repeticiones) | pet/s (mediana) | dispersión pet/s | p95 (mediana) | dispersión p95 |',
  );
  console.log('| --- | ---: | ---: | ---: | ---: |');
  for (const [name, list] of Object.entries(groups)) {
    const ps = list.map((x) => x.ps);
    const p95 = list.map((x) => x.p95);
    console.log(
      `| ${name} | ${fmt(median(ps))} | ${fmt(spread(ps))} % | ${fmt(median(p95), 2)} | ${fmt(spread(p95))} % |`,
    );
  }
}
