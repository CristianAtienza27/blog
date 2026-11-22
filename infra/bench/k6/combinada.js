// Carga combinada (fase 6.1.4): carga objetivo de una revista personal, en modelo abierto.
//   - web: RATE peticiones/s (por defecto 20): 70 % prerenderizadas, 20 % estáticos, 10 % SSR
//     dinámico (página 404)
//   - api: API_RATE peticiones/s (por defecto 5) a /actuator/health
// Se ejecuta junto a pgbench y, si se pide, a una imagen procesándose (bench.mjs).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { handleSummaryTo, TIMEOUT } from './lib.js';

const SSR = __ENV.SSR || 'http://ssr:4000';
const API = __ENV.API || 'http://api:8080';
const data = JSON.parse(open('/out/rutas.json'));
const timeouts = new Counter('timeouts');
const duration = __ENV.DURATION || '180s';
const any = [{ threshold: 'max>=0' }];

export const options = {
  scenarios: {
    web: {
      executor: 'constant-arrival-rate',
      exec: 'web',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 60,
      maxVUs: 60,
    },
    api: {
      executor: 'constant-arrival-rate',
      exec: 'api',
      rate: Number(__ENV.API_RATE || 5),
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 20,
      maxVUs: 20,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '10s' }],
    'http_req_duration{tipo:prerender}': any,
    'http_req_duration{tipo:estatico}': any,
    'http_req_duration{tipo:dinamica}': any,
    'http_req_duration{tipo:health}': any,
  },
  discardResponseBodies: true,
};

http.setResponseCallback(http.expectedStatuses(200, 404));
const pick = (list) => list[Math.floor(Math.random() * list.length)];
let counter = 0;

function get(url, tipo, expected) {
  const res = http.get(url, { timeout: TIMEOUT, tags: { tipo } });
  if (res.error_code === 1050) timeouts.add(1);
  check(res, { [`${tipo} ${expected}`]: (r) => r.status === expected });
}

export function web() {
  const r = Math.random();
  if (r < 0.7) return get(`${SSR}${pick(data.prerender)}`, 'prerender', 200);
  if (r < 0.9) return get(`${SSR}${pick(data.estaticos)}`, 'estatico', 200);
  counter += 1;
  return get(`${SSR}/${pick(data.secciones)}/no-existe-${__VU}-${counter}`, 'dinamica', 404);
}

export function api() {
  get(`${API}/actuator/health`, 'health', 200);
}

export const handleSummary = handleSummaryTo;
