// API: GET /actuator/health (mismo Tomcat que la API; comprobación de PostgreSQL con isValid()).
// Es el SUELO de coste del servidor de la API, no un endpoint de contenido (no existen todavía).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { options as base, handleSummaryTo, TIMEOUT } from './lib.js';

export const options = base();
const timeouts = new Counter('timeouts');
const URL = `${__ENV.API || 'http://api:8080'}/actuator/health`;

export default function () {
  const res = http.get(URL, { timeout: TIMEOUT, tags: { tipo: 'health' } });
  if (res.error_code === 1050) timeouts.add(1);
  check(res, { 'UP (200)': (r) => r.status === 200 });
}

export const handleSummary = handleSummaryTo;
