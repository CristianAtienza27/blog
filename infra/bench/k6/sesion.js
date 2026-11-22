// Coste de la cadena de seguridad y de la sesión JDBC (6.2.7, prueba C y §6), en la misma
// ejecución que las referencias:
//   MODE=me      GET /api/auth/me con sesión (P1): Spring Security, carga de la sesión de Spring
//                Session JDBC (fila y atributos, deserialización), filtro del sello H7 (una lectura
//                por clave primaria) y actualización del último acceso
//   MODE=csrf    GET /api/auth/csrf sin sesión: cadena de seguridad sin sesión ni base de datos
//   MODE=health  GET /actuator/health: el suelo de 6.1.4 (Tomcat, servlet, pool, isValid())
// Cada usuario virtual inicia sesión una vez (fuera de la medición, etiqueta «preparacion»; se repite
// si recibe 429).
import { check } from 'k6';
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { options as base, handleSummaryTo } from './lib.js';
import { Client, LOGIN_EMAIL } from './auth.js';

// Un 429 al iniciar sesión (preparación) no es un error de la medición.
http.setResponseCallback(http.expectedStatuses(200, 429));

export const options = base({ porTipo: ['preparacion', 'me', 'csrf', 'health'] });
const MODE = __ENV.MODE || 'me';
const other = new Counter('estado_otros');
let client;
let ready = false;

export default function () {
  client ??= new Client();
  // Con sesión: el inicio de sesión se repite hasta conseguirla (un 429 «saturated» del semáforo de
  // Argon2id es esperado cuando muchos usuarios entran a la vez) y no cuenta en la medición.
  if (MODE === 'me' && !ready) {
    const login = client.login(__ENV.EMAIL || LOGIN_EMAIL, 'preparacion');
    ready = login.status === 200;
    check(login, { 'sesión iniciada': () => ready });
    return;
  }
  const path =
    MODE === 'me' ? '/api/auth/me' : MODE === 'csrf' ? '/api/auth/csrf' : '/actuator/health';
  const res = client.request('GET', path, undefined, MODE);
  if (res.status !== 200) other.add(1);
}

export const handleSummary = handleSummaryTo;
