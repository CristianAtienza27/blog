import express, { type Request, type Response } from 'express';
import type { Server } from 'node:http';
import type { ContentActivator, EpochRefusal } from './activation';
import { VERSION } from './snapshot-store';

/**
 * Puerto interno de la web (fases 6.3.3 y 6.3.4, M2): lo usa la API para activar versiones.
 * Escucha solo en WEB_INTERNAL_BIND (la IP de la red interna API–web, 6.3.5), nunca en el puerto
 * público ni en 0.0.0.0, y no lo publica el proxy. Sin secretos nuevos (M2): su protección es la
 * red; el orden entre publicadores lo da la época (fencing, 6.3.4).
 *
 *   POST /interno/reservar                  {"epoch":N}
 *   POST /interno/activar                   {"version":"vN","epoch":N}
 *   POST /interno/recuperacion/iniciar      {}
 *   POST /interno/recuperacion/completar    {"epoch":N,"nonce":"…"}
 *   GET  /interno/estado
 *
 * Cuerpos JSON de como mucho 1 KB y exactamente con esos campos. Nunca recibe contenido ni rutas:
 * solo el nombre de una versión que ya existe en el volumen de solo lectura, que se vuelve a
 * comprobar entera. Cualquier otra petición recibe un 404. Funciona aunque la web no tenga versión
 * activa: es la forma de recuperarla.
 */
const MAX_BODY = 1024;

export function createInternalApp(activator: ContentActivator) {
  const app = express();
  app.disable('x-powered-by');
  app.use((req, res) => {
    void route(req, res, activator).catch((error: unknown) => {
      console.error('Puerto interno:', error instanceof Error ? error.message : error);
      if (!res.headersSent) json(res, 500, { resultado: 'error' });
      else res.end();
    });
  });
  return app;
}

const POST_ROUTES = new Set([
  '/interno/reservar',
  '/interno/activar',
  '/interno/recuperacion/iniciar',
  '/interno/recuperacion/completar',
]);

async function route(req: Request, res: Response, activator: ContentActivator) {
  // La ruta exacta, sin decodificar y sin parámetros de consulta.
  const path = req.originalUrl;
  if (path === '/interno/estado' && (req.method === 'GET' || req.method === 'HEAD')) {
    const status = activator.status();
    return json(res, 200, {
      listo: status.ready,
      activa: status.active,
      anterior: status.previous,
      contentSha256: status.contentSha256,
      activadaEn: status.activatedAt,
      incidencias: status.issues,
      epoca: status.epoch,
      bloqueada: status.blocked !== null,
      motivoBloqueo: status.blocked,
    });
  }
  if (!POST_ROUTES.has(path) || req.method !== 'POST') {
    return json(res, 404, { resultado: 'no encontrado' });
  }
  const type = (req.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase();
  if (type !== 'application/json') return json(res, 415, { resultado: 'tipo no admitido' });
  const body = await readBody(req);
  if (body === null) return json(res, 413, { resultado: 'cuerpo demasiado grande' });
  const invalid = () => json(res, 400, { resultado: 'petición no válida' });

  if (path === '/interno/reservar') {
    const request = parseRequest(body, ['epoch']);
    if (!request || !isEpoch(request['epoch'])) return invalid();
    const result = await activator.reserve(request['epoch']);
    if (result.outcome === 'reserved')
      return json(res, 200, { resultado: 'reservada', epoca: result.epoch });
    return refused(res, result);
  }
  if (path === '/interno/activar') {
    const request = parseRequest(body, ['version', 'epoch']);
    const version = request?.['version'];
    if (
      !request ||
      typeof version !== 'string' ||
      !VERSION.test(version) ||
      !isEpoch(request['epoch'])
    ) {
      return invalid();
    }
    const result = await activator.activate(version, request['epoch']);
    if (result.outcome === 'rejected') {
      return json(res, 422, { resultado: 'rechazada', version, incidencias: result.issues });
    }
    if ('pointer' in result) {
      return json(res, 200, {
        resultado: result.outcome === 'activated' ? 'activada' : 'sin cambios',
        ...result.pointer,
      });
    }
    return refused(res, result);
  }
  if (path === '/interno/recuperacion/iniciar') {
    const request = parseRequest(body, []);
    if (!request) return invalid();
    const result = await activator.beginRecovery();
    if (result.outcome === 'not-blocked') return json(res, 409, { resultado: 'no bloqueada' });
    return json(res, 200, { resultado: 'iniciada', nonce: result.nonce });
  }
  // /interno/recuperacion/completar
  const request = parseRequest(body, ['epoch', 'nonce']);
  if (!request || !isEpoch(request['epoch']) || typeof request['nonce'] !== 'string')
    return invalid();
  const result = await activator.completeRecovery(request['epoch'], request['nonce']);
  switch (result.outcome) {
    case 'recovered':
      return json(res, 200, { resultado: 'recuperada', epoca: result.epoch });
    case 'not-blocked':
      return json(res, 409, { resultado: 'no bloqueada' });
    case 'invalid-nonce':
      return json(res, 409, { resultado: 'nonce no válido' });
    default:
      return json(res, 409, {
        resultado: 'época no superior a la de activa.json',
        minimo: result.floor,
      });
  }
}

function refused(res: Response, result: EpochRefusal) {
  switch (result.outcome) {
    case 'blocked':
      return json(res, 423, { resultado: 'recuperación necesaria', motivo: result.reason });
    case 'stale':
      return json(res, 409, { resultado: 'época obsoleta', epoca: result.epoch });
    default:
      return json(res, 409, { resultado: 'época no reservada', epoca: result.epoch });
  }
}

/** Época: entero positivo exacto (las de la API empiezan en 1). */
function isEpoch(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 1;
}

/** Un objeto JSON con EXACTAMENTE esos campos (ni más ni menos), o null. */
function parseRequest(body: Buffer, keys: string[]): Record<string, unknown> | null {
  let value: unknown;
  try {
    value = JSON.parse(body.toString('utf8'));
  } catch {
    return null;
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const present = Object.keys(value).sort();
  if (present.join() !== [...keys].sort().join()) return null;
  return value as Record<string, unknown>;
}

/** El cuerpo entero, o null si supera el límite (se deja de leer en cuanto lo supera). */
function readBody(req: Request): Promise<Buffer | null> {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length'] ?? 0);
    if (declared > MAX_BODY) {
      req.resume();
      resolve(null);
      return;
    }
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        req.removeAllListeners('data');
        req.resume();
        resolve(null);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(size > MAX_BODY ? null : Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function json(res: Response, status: number, value: unknown) {
  res.status(status);
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(value));
}

/** «IP:puerto» (IPv4). Nunca 0.0.0.0: el puerto interno no escucha en todas las interfaces. */
export function parseBind(value: string): { host: string; port: number } {
  const match = /^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$/.exec(value);
  const port = Number(match?.[2]);
  if (!match || match[1] === '0.0.0.0' || port < 1 || port > 65535) {
    throw new Error('WEB_INTERNAL_BIND no válido: se espera «IP:puerto», sin 0.0.0.0');
  }
  return { host: match[1], port };
}

export function listenInternal(activator: ContentActivator, bind: string): Promise<Server> {
  const { host, port } = parseBind(bind);
  return new Promise((resolve, reject) => {
    const server = createInternalApp(activator).listen(port, host, () => resolve(server));
    server.once('error', reject);
  });
}
