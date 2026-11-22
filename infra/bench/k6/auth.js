// Autenticación contra la API real para las mediciones de la 6.2.7: un cliente con cookies propias
// y un autenticador WebAuthn de pruebas (como TestAuthenticator de los tests de la API).
//
// - Cookies: las de la API son __Host- y Secure; el almacén de k6 no las envía por HTTP (la red del
//   proyecto no tiene TLS), así que cada cliente las guarda y las envía a mano.
// - CSRF: el token de la cookie __Host-XSRF-TOKEN vuelve en la cabecera X-XSRF-TOKEN.
// - Passkey: clave EC P-256 (WebCrypto de k6), atestación «none», sin contador (0), UP y UV;
//   firma ES256 en DER. RP ID y origen, los de la configuración por defecto de la API.
//
// La contraseña de prueba se lee de /cred/cms_admin_password (credencial de este entorno, fuera del
// repositorio, D5). Nunca se escribe en la consola ni en los resultados.
import http from 'k6/http';
import encoding from 'k6/encoding';

export const API = __ENV.API || 'http://api:8080';
export const LOGIN_EMAIL = 'bench-login@example.test';
export const PASSKEY_EMAIL = 'bench-passkey@example.test';
export const PASSWORD = open('/cred/cms_admin_password').replace(/\r?\n$/, '');
const RP_ID = 'localhost';
const ORIGIN = 'http://localhost:4200';

// --- Cliente HTTP con cookies propias ----------------------------------------------------------

export class Client {
  constructor() {
    this.cookies = {};
  }

  request(method, path, body, tipo, extra = {}) {
    const headers = { 'Content-Type': 'application/json', ...(extra.headers || {}) };
    const names = Object.keys(this.cookies);
    if (names.length) headers.Cookie = names.map((n) => `${n}=${this.cookies[n]}`).join('; ');
    if (method !== 'GET' && this.cookies['__Host-XSRF-TOKEN']) {
      headers['X-XSRF-TOKEN'] = this.cookies['__Host-XSRF-TOKEN'];
    }
    const res = http.request(method, `${API}${path}`, body === undefined ? null : body, {
      headers,
      // HTTP_TIMEOUT (6.2.7.4): en las pruebas de fallos una respuesta puede tardar hasta 45 s.
      timeout: extra.timeout || __ENV.HTTP_TIMEOUT || '10s',
      tags: { tipo },
      responseType: extra.responseType || 'text',
    });
    // Varias Set-Cookie de la misma cookie en una respuesta (borrar y volver a poner el token
    // CSRF): vale la última. Max-Age=0 llega como max_age negativo (Go).
    for (const [name, list] of Object.entries(res.cookies || {})) {
      const last = list[list.length - 1];
      if (!last || last.value === '' || last.max_age < 0) delete this.cookies[name];
      else this.cookies[name] = last.value;
    }
    return res;
  }

  csrf(tipo = 'csrf') {
    return this.request('GET', '/api/auth/csrf', undefined, tipo);
  }

  /** Inicio de sesión: pide el token CSRF solo si aún no hay cookie. */
  login(email, tipo = 'login') {
    if (!this.cookies['__Host-XSRF-TOKEN']) this.csrf('csrf');
    return this.request(
      'POST',
      '/api/auth/login',
      JSON.stringify({ email, password: PASSWORD }),
      tipo,
    );
  }
}

// --- Autenticador WebAuthn ----------------------------------------------------------------------

const b64 = (bytes) =>
  encoding.b64encode(bytes instanceof Uint8Array ? bytes.buffer : bytes, 'rawurl');
const unb64 = (text) => new Uint8Array(encoding.b64decode(text, 'rawurl'));
const ascii = (text) => {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i);
  return out;
};
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
};
const sha256 = async (bytes) => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));

/** Firma ECDSA de WebCrypto (r‖s, 64 bytes) a DER, como la envían los navegadores. */
function der(raw) {
  const int = (bytes) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = concat(new Uint8Array([0]), v);
    return concat(new Uint8Array([0x02, v.length]), v);
  };
  const body = concat(int(raw.slice(0, 32)), int(raw.slice(32)));
  return concat(new Uint8Array([0x30, body.length]), body);
}

function clientData(type, challenge) {
  return ascii(JSON.stringify({ type, challenge, origin: ORIGIN }));
}

/** Datos del autenticador: hash del RP ID, flags UP+UV (+AT al registrar), contador 0. */
async function authenticatorData(attested, credentialId, cose) {
  const flags = 0x01 | 0x04 | (attested ? 0x40 : 0);
  const head = concat(await sha256(ascii(RP_ID)), new Uint8Array([flags, 0, 0, 0, 0]));
  if (!attested) return head;
  return concat(
    head,
    new Uint8Array(16),
    new Uint8Array([0, credentialId.length]),
    credentialId,
    cose,
  );
}

/** Clave COSE EC2 (ES256) en CBOR: {1: 2, 3: -7, -1: 1, -2: x, -3: y}. */
function coseKey(rawPublic) {
  const x = rawPublic.slice(1, 33);
  const y = rawPublic.slice(33, 65);
  return concat(
    new Uint8Array([0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]),
    x,
    new Uint8Array([0x22, 0x58, 0x20]),
    y,
  );
}

/** Objeto de atestación «none» en CBOR: {"fmt": "none", "attStmt": {}, "authData": …}. */
function attestationObject(authData) {
  const len =
    authData.length < 256
      ? new Uint8Array([0x58, authData.length])
      : new Uint8Array([0x59, authData.length >> 8, authData.length & 0xff]);
  return concat(
    new Uint8Array([0xa3, 0x63]),
    ascii('fmt'),
    new Uint8Array([0x64]),
    ascii('none'),
    new Uint8Array([0x67]),
    ascii('attStmt'),
    new Uint8Array([0xa0, 0x68]),
    ascii('authData'),
    len,
    authData,
  );
}

function credentialJson(credentialId, response) {
  return {
    id: b64(credentialId),
    rawId: b64(credentialId),
    type: 'public-key',
    response,
    clientExtensionResults: {},
    authenticatorAttachment: 'platform',
  };
}

export class Authenticator {
  /** Exportable para compartirlo entre usuarios virtuales (setup → VU). */
  static async create() {
    const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
      'sign',
      'verify',
    ]);
    const a = new Authenticator();
    a.privateKey = keys.privateKey;
    a.rawPublic = new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey));
    a.credentialId = crypto.getRandomValues(new Uint8Array(32));
    a.userHandle = null;
    return a;
  }

  async export() {
    return {
      jwk: await crypto.subtle.exportKey('jwk', this.privateKey),
      credentialId: b64(this.credentialId),
      userHandle: this.userHandle ? b64(this.userHandle) : null,
    };
  }

  static async restore(data) {
    const a = new Authenticator();
    a.privateKey = await crypto.subtle.importKey(
      'jwk',
      data.jwk,
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['sign'],
    );
    a.credentialId = unb64(data.credentialId);
    a.userHandle = data.userHandle ? unb64(data.userHandle) : null;
    return a;
  }

  /** Cuerpo de POST /api/auth/passkeys/registration a partir de las opciones del servidor. */
  async register(optionsJson) {
    const options = JSON.parse(optionsJson);
    this.userHandle = unb64(options.user.id);
    const data = clientData('webauthn.create', options.challenge);
    const authData = await authenticatorData(true, this.credentialId, coseKey(this.rawPublic));
    return JSON.stringify({
      credential: credentialJson(this.credentialId, {
        clientDataJSON: b64(data),
        attestationObject: b64(attestationObject(authData)),
        transports: ['internal'],
      }),
      label: 'k6',
    });
  }

  /** Cuerpo de una aserción (autenticación o step-up). */
  async assertion(optionsJson) {
    const options = JSON.parse(optionsJson);
    const data = clientData('webauthn.get', options.challenge);
    const authData = await authenticatorData(false);
    const signed = concat(authData, await sha256(data));
    const raw = new Uint8Array(
      await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, this.privateKey, signed),
    );
    const response = {
      clientDataJSON: b64(data),
      authenticatorData: b64(authData),
      signature: b64(der(raw)),
    };
    if (this.userHandle) response.userHandle = b64(this.userHandle);
    return JSON.stringify({ credential: credentialJson(this.credentialId, response) });
  }
}
