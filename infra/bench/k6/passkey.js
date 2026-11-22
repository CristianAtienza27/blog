// Segundo factor con passkey (6.2.7, prueba D y §4): el camino completo con el bloqueo por usuario
// de H7 (consumo del challenge, verificación ES256, contador, auditoría, dispositivo y sesión nueva).
//   MODE=f       cada iteración: contraseña (P2, Argon2id) + opciones + aserción → F
//   MODE=stepup  cada usuario llega a F una vez; después, step-up en bucle (sin Argon2id): aísla
//                el coste de la passkey y del bloqueo
// EMAIL: un usuario nuevo de cada ejecución (bench.mjs lo crea con crear-admin), porque la clave
// privada del autenticador solo existe en esta ejecución. setup() registra su passkey (P1 → P2 → F)
// y la comparte con todos los usuarios virtuales. Contador 0 (sin contador): ninguna aserción se
// rechaza por el orden de llegada.
//
// Requiere API_IP_FAILURES elevado (D6): las opciones WebAuthn cuentan en el ámbito de la IP.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { options as base, handleSummaryTo } from './lib.js';
import { Authenticator, Client } from './auth.js';

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 429));
export const options = base({
  setupTimeout: '60s',
  porTipo: ['login_p2', 'opciones', 'f', 'opciones_stepup', 'stepup'],
});
const MODE = __ENV.MODE || 'f';
const EMAIL = __ENV.EMAIL;
const okFull = new Counter('estado_f_200');
const okStepUp = new Counter('estado_stepup_204');
const saturated = new Counter('estado_429_saturated');
const limited = new Counter('estado_429_rate_limited');
const other = new Counter('estado_otros');
const flow = new Trend('ms_flujo_completo', true);

export async function setup() {
  if (!EMAIL) throw new Error('falta EMAIL');
  const c = new Client();
  const login = c.login(EMAIL, 'preparacion');
  if (!String(login.body).includes('PASSKEY_ENROLLMENT_REQUIRED')) {
    throw new Error(`el usuario debe empezar en P1: ${login.status}`);
  }
  const authenticator = await Authenticator.create();
  const options = c.request('POST', '/api/auth/passkeys/registration/options', null, 'preparacion');
  const registered = c.request(
    'POST',
    '/api/auth/passkeys/registration',
    await authenticator.register(options.body),
    'preparacion',
  );
  if (registered.status !== 201)
    throw new Error(`registro: ${registered.status} ${registered.body}`);
  const assertion = c.request(
    'POST',
    '/api/auth/passkeys/authentication/options',
    null,
    'preparacion',
  );
  const full = c.request(
    'POST',
    '/api/auth/passkeys/authentication',
    await authenticator.assertion(assertion.body),
    'preparacion',
  );
  if (!String(full.body).includes('AUTHENTICATED'))
    throw new Error(`F: ${full.status} ${full.body}`);
  return { authenticator: await authenticator.export() };
}

let state;

function count(res, okCounter) {
  if (res.status === 429) (String(res.body).includes('"saturated"') ? saturated : limited).add(1);
  else if (res.status >= 200 && res.status < 300) okCounter?.add(1);
  else other.add(1);
}

async function toFull(client, authenticator) {
  const start = Date.now();
  const login = client.login(EMAIL, 'login_p2');
  if (login.status !== 200) return (count(login), false);
  const options = client.request(
    'POST',
    '/api/auth/passkeys/authentication/options',
    null,
    'opciones',
  );
  if (options.status !== 200) return (count(options), false);
  const full = client.request(
    'POST',
    '/api/auth/passkeys/authentication',
    await authenticator.assertion(options.body),
    'f',
  );
  count(full, okFull);
  if (full.status === 200) flow.add(Date.now() - start);
  return full.status === 200;
}

export default async function (data) {
  if (!state)
    state = {
      client: new Client(),
      authenticator: await Authenticator.restore(data.authenticator),
      full: false,
    };
  if (MODE === 'f') {
    await toFull(state.client, state.authenticator);
    return;
  }
  if (!state.full) {
    state.full = await toFull(state.client, state.authenticator);
    return;
  }
  const options = state.client.request(
    'POST',
    '/api/auth/step-up/options',
    null,
    'opciones_stepup',
  );
  if (options.status !== 200) {
    count(options);
    if (options.status === 401 || options.status === 403) state.full = false;
    return;
  }
  const stepUp = state.client.request(
    'POST',
    '/api/auth/step-up',
    await state.authenticator.assertion(options.body),
    'stepup',
  );
  count(stepUp, okStepUp);
  if (stepUp.status === 401) state.full = false;
}

export const handleSummary = handleSummaryTo;
