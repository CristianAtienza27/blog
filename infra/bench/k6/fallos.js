// Carga mixta para las pruebas de fallos y reinicios (6.2.7.4, R1–R8). Registra CADA petición como
// una línea «EV {json}» con su hora (ts, epoch ms), duración, estado HTTP y X-Correlation-Id, para
// clasificar después cada operación (completada, auditada con el resultado perdido, nunca auditada):
//   login   LOGIN_RATE inicios de sesión por segundo con el usuario EMAIL (Argon2id, auditoría)
//   me      ME_VUS usuarios: cada uno inicia sesión UNA vez y consulta /me; nunca vuelve a iniciar
//           sesión salvo con 401 (así se ve si su sesión sobrevive a un reinicio)
//   stepup  STEPUP_VUS usuarios en F con la passkey de PASSKEY_EMAIL (registrada en setup), en bucle
//   sonda   readiness y liveness, 1 por segundo
// Los fallos son el objetivo: sin umbrales ni corte automático. Requiere API_IP_FAILURES elevado.
import http from 'k6/http';
import { sleep } from 'k6';
import { Authenticator, Client } from './auth.js';

const DURATION = __ENV.DURATION || '150s';
const EMAIL = __ENV.EMAIL;
const PASSKEY_EMAIL = __ENV.PASSKEY_EMAIL;
const API = __ENV.API || 'http://api:8080';
const TIMEOUT = '60s';

export const options = {
  setupTimeout: '60s',
  discardResponseBodies: false,
  scenarios: {
    login: {
      executor: 'constant-arrival-rate',
      exec: 'login',
      rate: Number(__ENV.LOGIN_RATE || 10),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 40,
      maxVUs: 80,
    },
    me: {
      executor: 'constant-vus',
      exec: 'me',
      vus: Number(__ENV.ME_VUS || 5),
      duration: DURATION,
    },
    stepup: {
      executor: 'constant-vus',
      exec: 'stepup',
      vus: Number(__ENV.STEPUP_VUS || 5),
      duration: DURATION,
    },
    sonda: {
      executor: 'constant-arrival-rate',
      exec: 'sonda',
      rate: 1,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 70,
      maxVUs: 70,
    },
  },
};

const ev = (data) => console.log(`EV ${JSON.stringify(data)}`);
const cid = (res) =>
  (res.headers && (res.headers['X-Correlation-Id'] || res.headers['x-correlation-id'])) || null;
const code = (res) =>
  res.status >= 400 ? (String(res.body).match(/"code":"(\w+)"/) ?? [])[1] : undefined;

function record(k, start, res, extra = {}) {
  ev({
    k,
    ts: start,
    ms: Date.now() - start,
    http: res.status,
    cid: cid(res),
    codigo: code(res),
    error: res.error_code || null,
    vu: __VU,
    ...extra,
  });
}

export async function setup() {
  const c = new Client();
  const setupStart = Date.now();
  const login = c.login(PASSKEY_EMAIL, 'preparacion');
  // El inicio de sesión de la preparación también se audita: se registra para clasificarlo.
  record('setup_login', setupStart, login);
  if (!String(login.body).includes('PASSKEY_ENROLLMENT_REQUIRED'))
    throw new Error(`P1 esperado: ${login.status}`);
  const a = await Authenticator.create();
  const reg = c.request('POST', '/api/auth/passkeys/registration/options', null, 'preparacion');
  const done = c.request(
    'POST',
    '/api/auth/passkeys/registration',
    await a.register(reg.body),
    'preparacion',
  );
  if (done.status !== 201) throw new Error(`registro: ${done.status}`);
  return { authenticator: await a.export() };
}

let client;
export function login() {
  client ??= new Client();
  const start = Date.now();
  record('login', start, client.login(EMAIL, 'login'));
}

let meClient;
let meReady = false;
let relogins = 0;
export function me() {
  meClient ??= new Client();
  if (!meReady) {
    const start = Date.now();
    const res = meClient.login(EMAIL, 'me_login');
    record('me_login', start, res, { relogin: relogins });
    meReady = res.status === 200;
    if (!meReady) sleep(0.5);
    return;
  }
  const start = Date.now();
  const res = meClient.request('GET', '/api/auth/me', undefined, 'me', { timeout: TIMEOUT });
  record('me', start, res);
  if (res.status === 401) {
    meReady = false;
    relogins++;
  }
  sleep(0.1);
}

let pk;
export async function stepup(data) {
  pk ??= { c: new Client(), a: await Authenticator.restore(data.authenticator), full: false };
  if (!pk.full) {
    const start = Date.now();
    const login = pk.c.login(PASSKEY_EMAIL, 'stepup_login');
    // Siempre registrado (también con éxito): su auditoría se clasifica como las demás.
    record('stepup_login', start, login);
    if (login.status !== 200) return sleep(0.5);
    const opt = pk.c.request(
      'POST',
      '/api/auth/passkeys/authentication/options',
      null,
      'f_opciones',
    );
    if (opt.status !== 200) return (record('f_opciones', start, opt), sleep(0.5));
    const full = pk.c.request(
      'POST',
      '/api/auth/passkeys/authentication',
      await pk.a.assertion(opt.body),
      'f',
    );
    record('f', start, full);
    pk.full = full.status === 200;
    return;
  }
  const start = Date.now();
  const opt = pk.c.request('POST', '/api/auth/step-up/options', null, 'stepup_opciones', {
    timeout: TIMEOUT,
  });
  if (opt.status !== 200) {
    record('stepup_opciones', start, opt);
    if (opt.status === 401 || opt.status === 403) pk.full = false;
    return sleep(0.2);
  }
  const res = pk.c.request('POST', '/api/auth/step-up', await pk.a.assertion(opt.body), 'stepup', {
    timeout: TIMEOUT,
  });
  record('stepup', start, res);
  if (res.status === 401) pk.full = false;
}

export function sonda() {
  for (const [k, path] of [
    ['readiness', '/actuator/health/readiness'],
    ['liveness', '/actuator/health/liveness'],
  ]) {
    const start = Date.now();
    const res = http.get(`${API}${path}`, { timeout: '20s', tags: { tipo: k } });
    record(k, start, res);
  }
}

export function handleSummary() {
  return {};
}
