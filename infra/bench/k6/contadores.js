// Contadores de intentos frente a reinicios (6.2.7.4, R5). Un solo usuario virtual durante toda la
// prueba (misma IP: la pareja cuenta + IP es la que se bloquea): 5 contraseñas incorrectas bloquean
// la pareja (valor real, 5) y después, cada 2 s, un intento CON LA CONTRASEÑA CORRECTA, que debe
// seguir recibiendo 429 con Retry-After mientras dure el bloqueo, también tras matar la API y tras
// reiniciar PostgreSQL (fuera de este script). La contraseña incorrecta no es un secreto.
import { sleep } from 'k6';
import { Client } from './auth.js';

const EMAIL = __ENV.EMAIL;
export const options = { vus: 1, iterations: 1, setupTimeout: '10s' };
const ev = (data) => console.log(`EV ${JSON.stringify(data)}`);

export default function () {
  const c = new Client();
  c.csrf();
  for (let i = 0; i < 5; i++) {
    const res = c.request(
      'POST',
      '/api/auth/login',
      JSON.stringify({ email: EMAIL, password: 'contraseña-incorrecta-de-prueba' }),
      'fallo',
      {},
    );
    ev({ k: 'fallo', ts: Date.now(), http: res.status });
  }
  const end = Date.now() + Number(__ENV.SECONDS || 120) * 1000;
  while (Date.now() < end) {
    const start = Date.now();
    const res = c.login(EMAIL, 'correcta');
    ev({
      k: 'correcta',
      ts: start,
      ms: Date.now() - start,
      http: res.status,
      retryAfter: res.headers['Retry-After'] ? Number(res.headers['Retry-After']) : null,
      codigo: res.status >= 400 ? (String(res.body).match(/"code":"(\w+)"/) ?? [])[1] : undefined,
      error: res.error_code || null,
    });
    sleep(2);
  }
}

export function handleSummary() {
  return {};
}
