// Comportamiento de la API con PostgreSQL pausado (docker pause, fuera de este script).
//   sonda:     1 petición/s a /actuator/health (la misma de la 6.1.4: comparable con la línea base)
//   liveness:  1 petición/s a /actuator/health/liveness (6.2.7.2: no debe depender de PostgreSQL)
//   readiness: 1 petición/s a /actuator/health/readiness (6.2.7.2: incluye PostgreSQL)
//   carga:     CARGA_VUS usuarios sin pausa (agotamiento del pool): /actuator/health, o
//              CARGA_MODO=me, /api/auth/me con sesión (Spring Session, filtro del sello)
//   pool:      cada segundo, métricas de Hikari por /actuator/metrics (desde la 6.2.2 exigen sesión:
//              bench.mjs lee además las líneas de Hikari del registro de la API)
// Cada evento se escribe como una línea «EV {json}» en la consola; bench lo procesa.
import http from 'k6/http';
import { Client, LOGIN_EMAIL } from './auth.js';

const API = __ENV.API || 'http://api:8080';
const duration = __ENV.DURATION || '150s';
const T0 = Date.now();
const LOAD_MODE = __ENV.CARGA_MODO || 'health';
const probe = (exec) => ({
  executor: 'constant-arrival-rate',
  exec,
  rate: 1,
  timeUnit: '1s',
  duration,
  preAllocatedVUs: 120,
  maxVUs: 120,
});

export const options = {
  scenarios: {
    sonda: probe('sonda'),
    liveness: probe('liveness'),
    readiness: probe('readiness'),
    pool: { ...probe('pool'), preAllocatedVUs: 10, maxVUs: 10 },
    ...(Number(__ENV.CARGA_VUS || 0) > 0
      ? {
          carga: {
            executor: 'constant-vus',
            exec: 'carga',
            vus: Number(__ENV.CARGA_VUS),
            duration,
          },
        }
      : {}),
  },
  discardResponseBodies: false,
};

const timeout = __ENV.TIMEOUT || '90s';
const ev = (data) => console.log(`EV ${JSON.stringify(data)}`);

function probeEvent(kind, path) {
  const start = Date.now();
  const res = http.get(`${API}${path}`, { timeout, tags: { tipo: kind } });
  let status = null;
  try {
    status = JSON.parse(res.body).status;
  } catch {
    status = null;
  }
  ev({
    k: kind,
    t: (start - T0) / 1000,
    ms: Date.now() - start,
    http: res.status,
    estado: status,
    error: res.error_code || null,
  });
}

export function sonda() {
  probeEvent('sonda', '/actuator/health');
}

export function liveness() {
  probeEvent('liveness', '/actuator/health/liveness');
}

export function readiness() {
  probeEvent('readiness', '/actuator/health/readiness');
}

// Carga con sesión (CARGA_MODO=me): cada usuario virtual inicia sesión una vez.
let client;

export function carga() {
  if (LOAD_MODE === 'me' && !client) {
    const c = new Client();
    if (c.login(LOGIN_EMAIL, 'preparacion').status !== 200) return;
    client = c;
  }
  const start = Date.now();
  const res =
    LOAD_MODE === 'me'
      ? client.request('GET', '/api/auth/me', undefined, 'carga', { timeout })
      : http.get(`${API}/actuator/health`, { timeout, tags: { tipo: 'carga' } });
  ev({
    k: 'carga',
    t: (start - T0) / 1000,
    ms: Date.now() - start,
    http: res.status,
    codigo: res.status === 503 ? (String(res.body).match(/"code":"(\w+)"/) ?? [])[1] : undefined,
    error: res.error_code || null,
  });
}

const metric = (name) => {
  const res = http.get(`${API}/actuator/metrics/${name}`, {
    timeout: '5s',
    tags: { tipo: 'pool' },
  });
  try {
    return JSON.parse(res.body).measurements[0].value;
  } catch {
    return null;
  }
};

export function pool() {
  const t = (Date.now() - T0) / 1000;
  ev({
    k: 'pool',
    t,
    activas: metric('hikaricp.connections.active'),
    inactivas: metric('hikaricp.connections.idle'),
    pendientes: metric('hikaricp.connections.pending'),
    total: metric('hikaricp.connections'),
    timeouts: metric('hikaricp.connections.timeout'),
  });
}

export function handleSummary() {
  return {};
}
