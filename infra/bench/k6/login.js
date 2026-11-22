// Inicio de sesión con contraseña (6.2.7, prueba B y criterios 5 y 6 del plan de la 6.2): cada
// usuario virtual inicia sesión una y otra vez (modelo cerrado, VUS) o a un ritmo fijo (RATE), con
// el usuario de prueba bench-login, o EMAIL (6.2.7.3: uno nuevo por ejecución; siempre P1). Cada
// inicio de sesión calcula un Argon2id completo; con su cookie anterior, la API crea una sesión
// nueva y borra la anterior.
//
// 429 es comportamiento esperado (semáforo de Argon2id lleno, «saturated»; o limitación,
// «rate_limited»): no cuenta como error, pero se cuenta aparte. Cualquier otro estado, sí.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { options as base, handleSummaryTo } from './lib.js';
import { Client, LOGIN_EMAIL } from './auth.js';

http.setResponseCallback(http.expectedStatuses(200, 429));
export const options = base({ porTipo: ['csrf', 'login'] });

const ok = new Counter('estado_login_200');
const saturated = new Counter('estado_login_429_saturated');
const limited = new Counter('estado_login_429_rate_limited');
const other = new Counter('estado_login_otros');
const okDuration = new Trend('ms_login_200', true);
let client;

export default function () {
  client ??= new Client();
  const res = client.login(__ENV.EMAIL || LOGIN_EMAIL);
  if (res.status === 200) {
    ok.add(1);
    okDuration.add(res.timings.duration);
  } else if (res.status === 429) {
    (String(res.body).includes('"saturated"') ? saturated : limited).add(1);
  } else {
    other.add(1);
  }
}

export const handleSummary = handleSummaryTo;
