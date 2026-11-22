import type { Request, Response } from 'express';
import type { LoadedSnapshot } from './snapshot-store';
import { SNAPSHOT_VERSION_URL } from './snapshot-version';

/**
 * /content/ en el build de instantáneas: solo la proyección pública de las versiones que se
 * sirven (la activa y la anterior, 6.3.3). La ruta se comprueba SIN decodificar contra una lista
 * cerrada: solo minúsculas, cifras, guiones, «/» y «.json» en posiciones fijas. Cualquier «%», «\»,
 * carácter nulo, «..», mayúscula o nombre fuera del contrato no coincide y recibe un 404; nada de
 * /content/ llega a los archivos estáticos ni al SSR. El manifiesto no se sirve (contiene hashes).
 *
 * Una ruta bien formada de una versión que ya no se sirve recibe un 410: el navegador sabe así
 * que su versión se ha retirado y recarga la página (una vez).
 */
const CONTENT_PATH =
  /^\/content\/(v[1-9][0-9]{0,8})\/(?:([a-z]+)\.json|articles\/([a-z0-9]+(?:-[a-z0-9]+)*)\.json)$/;

const IMMUTABLE = 'public, max-age=31536000, immutable';

export interface ServedVersions {
  /** La versión activa, o undefined si no hay ninguna. */
  active(): LoadedSnapshot | undefined;
  /** Una versión que se sirve (la activa o la anterior), o undefined. */
  find(version: string): LoadedSnapshot | undefined;
}

export function snapshotHandler(versions: ServedVersions) {
  return (req: Request, res: Response) => {
    void handle(req, res, versions).catch((error: unknown) => {
      // Por ejemplo, un cuerpo alterado en el disco después de cargar: nunca se sirve.
      console.error(
        'Error al servir el contenido:',
        error instanceof Error ? error.message : error,
      );
      if (res.headersSent) {
        res.end();
        return;
      }
      plain(res, 500, 'Error');
    });
  };
}

async function handle(req: Request, res: Response, versions: ServedVersions) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.setHeader('Allow', 'GET, HEAD');
    return plain(res, 405, 'Método no permitido');
  }
  // originalUrl conserva la ruta tal como llegó, sin decodificar.
  const path = req.originalUrl.split('?')[0];

  if (path === SNAPSHOT_VERSION_URL) {
    const active = versions.active();
    if (!active) {
      res.setHeader('Retry-After', '5');
      return plain(res, 503, 'Sin versión activa');
    }
    return send(req, res, Buffer.from(JSON.stringify({ version: active.version })), 'no-cache');
  }
  const match = CONTENT_PATH.exec(path);
  if (!match) return plain(res, 404, 'No encontrado');
  const snapshot = versions.find(match[1]);
  if (!snapshot) return plain(res, 410, 'Versión retirada');
  const [, , collection, slug] = match;
  const bytes = collection
    ? snapshot.publicCollection(collection)
    : await snapshot.publicBody(slug);
  if (!bytes) return plain(res, 404, 'No encontrado');
  return send(req, res, bytes, IMMUTABLE);
}

function send(req: Request, res: Response, bytes: Buffer, cacheControl: string) {
  res.status(200);
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Content-Length', String(bytes.length));
  res.setHeader('Cache-Control', cacheControl);
  res.end(req.method === 'HEAD' ? undefined : bytes);
}

function plain(res: Response, status: number, text: string) {
  res.status(status);
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'text/plain; charset=utf-8');
  res.end(text);
}
