import {
  InjectionToken,
  REQUEST_CONTEXT,
  TransferState,
  inject,
  provideEnvironmentInitializer,
  type EnvironmentProviders,
  type Provider,
} from '@angular/core';
import { writeResponseToNodeResponse } from '@angular/ssr/node';
import type { Express, NextFunction, Request, Response } from 'express';
import { SECTIONS } from '../../layout/navigation';
import { CONTENT_SOURCE } from '../json/content-source';
import { ContentActivator } from './activation';
import { HtmlCache } from './html-cache';
import { listenInternal } from './internal-server';
import { snapshotHandler } from './snapshot-http';
import {
  currentSnapshot,
  peekCurrentSnapshot,
  servedSnapshot,
  type LoadedSnapshot,
} from './snapshot-store';
import { SNAPSHOT_VERSION_KEY } from './snapshot-version';

/**
 * Modo de contenido del build de instantáneas (fases 6.3.2 y 6.3.3): sustituye a
 * content-mode.server.ts.
 *
 *   CONTENT_SNAPSHOTS_DIR   carpeta de instantáneas (solo lectura)
 *   CONTENT_STATE_DIR       carpeta estado/ con activa.json (la única en la que escribe la web)
 *   WEB_INTERNAL_BIND       «IP:puerto» del puerto interno de activación (sin ella, no se abre)
 *
 * Al arrancar se lee activa.json. Sin versión activa válida, la web arranca igual: readiness DOWN,
 * 503 en las páginas y /content/, y el puerto interno y la salud funcionando para recuperarla.
 * Nunca recurre al contenido del build, que este build no incluye.
 */

/** La portada y los artículos se renderizan en el servidor desde la instantánea (N6). */
export const SNAPSHOT_MODE = true;

interface ContentRequestContext {
  contentSnapshot?: LoadedSnapshot;
}

/**
 * Contexto de una petición (REQUEST_CONTEXT): la versión se fija en cuanto llega la petición, antes
 * de que empiece el SSR. Si la versión activa cambia mientras se renderiza, la página sigue entera
 * con la suya (y la caché la guarda con esa misma versión).
 */
export function contentRequestContext(snapshot = currentSnapshot()): ContentRequestContext {
  return { contentSnapshot: snapshot };
}

/** La versión que usa esta petición. */
const PINNED_SNAPSHOT = new InjectionToken<LoadedSnapshot | undefined>('PINNED_SNAPSHOT');

/**
 * Cada petición del SSR es una aplicación nueva con la versión de su contexto (o, sin él, la
 * activa al crearse): una página nunca mezcla dos versiones. La versión se transfiere al
 * navegador, que pide después esa misma.
 */
export function contentServerProviders(): (Provider | EnvironmentProviders)[] {
  return [
    {
      provide: PINNED_SNAPSHOT,
      useFactory: () =>
        (inject(REQUEST_CONTEXT, { optional: true }) as ContentRequestContext | null)
          ?.contentSnapshot ?? peekCurrentSnapshot(),
    },
    // Al prerenderizar las páginas estáticas en el build no hay versión: no la transfieren y el
    // navegador pregunta cuál se sirve. Esas páginas no consultan contenido en el servidor.
    provideEnvironmentInitializer(() => {
      const snapshot = inject(PINNED_SNAPSHOT);
      if (snapshot) inject(TransferState).set(SNAPSHOT_VERSION_KEY, snapshot.version);
    }),
    {
      provide: CONTENT_SOURCE,
      useFactory: () => {
        const snapshot = inject(PINNED_SNAPSHOT);
        if (!snapshot) throw new Error('No hay ninguna versión activa');
        return snapshot.source;
      },
    },
  ];
}

// --- Servidor ------------------------------------------------------------------------------------

let activator: ContentActivator | undefined;
const htmlCache = new HtmlCache();

/** La caché de HTML del proceso (para las comprobaciones de privacidad). */
export function contentHtmlCache(): HtmlCache {
  return htmlCache;
}

const NO_STORE = 'no-store';
const SECTION_PATHS = SECTIONS.map((section) => section.path.slice(1)).join('|');
/** La portada y los artículos, tal como llegan (sin decodificar ni parámetros de consulta). */
const CONTENT_ROUTE = new RegExp(`^/(?:(?:${SECTION_PATHS})/[a-z0-9]+(?:-[a-z0-9]+)*)?$`);

function status(res: Response, code: number, body: unknown) {
  res.status(code);
  res.setHeader('Cache-Control', NO_STORE);
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

/**
 * Salud y /content/, antes que los archivos estáticos y el SSR. La salud solo dice el estado: nada
 * de versiones ni de incidencias (eso está en el puerto interno).
 */
export function registerContentRoutes(app: Express): void {
  app.get('/_salud/vida', (_req, res) => status(res, 200, { status: 'UP' }));
  app.get('/_salud/listo', (_req, res) => {
    const ready = Boolean(peekCurrentSnapshot());
    status(res, ready ? 200 : 503, { status: ready ? 'UP' : 'DOWN' });
  });
  app.use('/content', snapshotHandler({ active: peekCurrentSnapshot, find: servedSnapshot }));
}

/**
 * Renderizado de una página: sin versión activa, 503 (la salud, /content/ y los archivos estáticos
 * ya se han atendido antes). Con versión, se fija para toda la petición y, si es la portada o un
 * artículo pedido con GET sin parámetros, se sirve desde la caché o se guarda en ella.
 */
export function handleRender(
  req: Request,
  res: Response,
  next: NextFunction,
  render: (context: unknown) => Promise<globalThis.Response | null>,
): void {
  const snapshot = peekCurrentSnapshot();
  if (!snapshot) {
    res.status(503);
    res.setHeader('Retry-After', '5');
    res.setHeader('Cache-Control', NO_STORE);
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.end('Contenido no disponible');
    return;
  }
  const cacheable =
    req.method === 'GET' && !req.originalUrl.includes('?') && CONTENT_ROUTE.test(req.originalUrl);
  const key = HtmlCache.key(snapshot.version, req.originalUrl);
  if (cacheable) {
    const page = htmlCache.get(key);
    if (page) {
      res.status(page.status);
      for (const [name, value] of page.headers) res.setHeader(name, value);
      res.setHeader('X-Cache', 'HIT');
      res.end(page.body);
      return;
    }
  }
  render(contentRequestContext(snapshot))
    .then(async (response) => {
      if (!response) return next();
      // El HTML no se guarda en la CDN (M9): cada visita lo revalida con este servidor.
      const html = (response.headers.get('content-type') ?? '').startsWith('text/html');
      if (html) response.headers.set('Cache-Control', 'no-cache');
      if (!cacheable || response.status !== 200 || !html) {
        return writeResponseToNodeResponse(response, res);
      }
      const body = Buffer.from(await response.arrayBuffer());
      const headers = [...response.headers.entries()];
      htmlCache.set(key, { status: response.status, headers, body });
      res.status(response.status);
      for (const [name, value] of headers) res.setHeader(name, value);
      res.setHeader('X-Cache', 'MISS');
      res.end(body);
      return undefined;
    })
    .catch(next);
}

/**
 * Antes de escuchar: lee activa.json (sin versión válida, la web arranca en DOWN) y abre el puerto
 * interno. Solo falla si falta la configuración o el puerto interno no puede escuchar.
 */
export async function prepareContent(): Promise<void> {
  const snapshotsDir = process.env['CONTENT_SNAPSHOTS_DIR'];
  const stateDir = process.env['CONTENT_STATE_DIR'];
  if (!snapshotsDir || !stateDir) {
    throw new Error('Faltan CONTENT_SNAPSHOTS_DIR y CONTENT_STATE_DIR (build de instantáneas)');
  }
  activator = new ContentActivator(snapshotsDir, stateDir);
  const started = await activator.start();
  console.log(
    started.ready
      ? `Contenido: versión ${started.active} activa${started.previous ? `, ${started.previous} anterior` : ''}`
      : `Contenido: SIN VERSIÓN ACTIVA (${started.issues.join('; ')}); se puede activar por el puerto interno`,
  );
  const bind = process.env['WEB_INTERNAL_BIND'];
  if (bind) {
    await listenInternal(activator, bind);
    console.log(`Puerto interno de activación en ${bind}`);
  } else {
    console.log('Puerto interno de activación desactivado (sin WEB_INTERNAL_BIND)');
  }
}
