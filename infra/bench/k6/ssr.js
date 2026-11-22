// SSR (fase 6.1.4). MODE elige qué se mide, siempre por separado:
//   prerender  A: las rutas prerenderizadas actuales (HTML generado en el build)
//   dinamica   B: renderizado en cada petición de las rutas que hoy lo usan (`**`): slugs
//              inexistentes que devuelven la página «no encontrada» (404). NO es un artículo
//   articulos  B2: artículos reales renderizados en cada petición con CONTENIDO EMPAQUETADO
//              (copia temporal fuera del repositorio, sin PostgreSQL ni API)
//   estaticos  JS, CSS e imágenes (/media) servidos por express.static
//   mezcla     70 % prerender, 20 % estáticos, 10 % dinámica
// Las rutas salen de /out/rutas.json, que genera bench.mjs a partir del build.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { options as base, handleSummaryTo, TIMEOUT } from './lib.js';

const MODE = __ENV.MODE || 'prerender';
const BASE = __ENV.SSR || 'http://ssr:4000';
const data = JSON.parse(open('/out/rutas.json'));
const timeouts = new Counter('timeouts');

const typed = (tipo) => [{ threshold: 'max>=0' }];
export const options = base({
  thresholds: {
    ...base().thresholds,
    'http_req_duration{tipo:prerender}': typed(),
    'http_req_duration{tipo:dinamica}': typed(),
    'http_req_duration{tipo:articulo}': typed(),
    'http_req_duration{tipo:estatico}': typed(),
  },
});

// 404 es la respuesta correcta de la página «no encontrada».
http.setResponseCallback(http.expectedStatuses(200, 404));

const pick = (list) => list[Math.floor(Math.random() * list.length)];
let counter = 0;

function get(path, tipo, expected) {
  const res = http.get(`${BASE}${path}`, { timeout: TIMEOUT, tags: { tipo } });
  if (res.error_code === 1050) timeouts.add(1);
  check(res, { [`${tipo} ${expected}`]: (r) => r.status === expected });
}

function dynamic() {
  counter += 1;
  const section = pick(data.secciones);
  get(`/${section}/no-existe-${__VU}-${counter}`, 'dinamica', 404);
}

export default function () {
  if (MODE === 'prerender') return get(pick(data.prerender), 'prerender', 200);
  if (MODE === 'dinamica') return dynamic();
  if (MODE === 'articulos') return get(pick(data.articulos), 'articulo', 200);
  if (MODE === 'estaticos') return get(pick(data.estaticos), 'estatico', 200);
  const r = Math.random();
  if (r < 0.7) return get(pick(data.prerender), 'prerender', 200);
  if (r < 0.9) return get(pick(data.estaticos), 'estatico', 200);
  return dynamic();
}

export const handleSummary = handleSummaryTo;
