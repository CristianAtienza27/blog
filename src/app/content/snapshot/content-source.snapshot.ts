import { InjectionToken, TransferState, inject } from '@angular/core';
import {
  COLLECTION_NAMES,
  type ArticleBodyRecord,
  type CollectionName,
  type ContentCollections,
} from '../records/records';
import { SLUG } from '../records/validate';
import { SNAPSHOT_VERSION, SNAPSHOT_VERSION_KEY, SNAPSHOT_VERSION_URL } from './snapshot-version';

/**
 * Sustituye a json/content-source.ts en el build de instantáneas (fase 6.3.2, fileReplacements):
 * este build no importa src/content, así que ningún bundle lleva contenido. En el navegador, el
 * contenido llega por HTTP desde /content/vN/ (la proyección pública, sin `original`), con la
 * versión que fijó el servidor (TransferState) o, en una página prerenderizada, la que se sirve.
 * En el servidor, CONTENT_SOURCE lo proporciona el almacén (content-mode.server.snapshot.ts).
 */

/** Origen de los registros. Los tests lo sustituyen por datos en memoria. */
export interface ContentSource {
  collection<K extends CollectionName>(name: K): Promise<ContentCollections[K]>;
  body(slug: string): Promise<ArticleBodyRecord | null>;
}

type Fetch = (url: string, init?: RequestInit) => Promise<Response>;

/** Lo que hace el navegador cuando su versión se ha retirado (los tests lo sustituyen). */
export type RetiredVersionHandler = (version: string) => void;

const RELOAD_MARK = 'contenido-recargado:';

/**
 * Una versión retirada (410): la página se recarga UNA vez para obtener la activa. La marca en
 * sessionStorage evita un bucle si, por lo que sea, la versión recargada también estuviera retirada.
 * Sin sessionStorage no se puede evitar un bucle, así que no se recarga.
 */
export function createReloadOnce(
  storage: () => Pick<Storage, 'getItem' | 'setItem'>,
  reload: () => void,
): RetiredVersionHandler {
  return (version) => {
    try {
      const store = storage();
      if (store.getItem(RELOAD_MARK + version)) return;
      store.setItem(RELOAD_MARK + version, '1');
    } catch {
      return;
    }
    reload();
  };
}

export const reloadOnce: RetiredVersionHandler = createReloadOnce(
  () => sessionStorage,
  () => location.reload(),
);

export function snapshotBrowserSource(
  state: TransferState,
  fetcher: Fetch = (url, init) => fetch(url, init),
  onRetired: RetiredVersionHandler = reloadOnce,
): ContentSource {
  let version: Promise<string> | undefined;
  const bodies = new Map<string, Promise<ArticleBodyRecord | null>>();

  const currentVersion = (): Promise<string> => {
    const transferred = state.get(SNAPSHOT_VERSION_KEY, null);
    if (transferred && SNAPSHOT_VERSION.test(transferred)) return Promise.resolve(transferred);
    version ??= fetcher(SNAPSHOT_VERSION_URL, { cache: 'no-store' })
      .then(async (response) => {
        const value = response.ok
          ? ((await response.json()) as { version?: unknown }).version
          : null;
        if (typeof value !== 'string' || !SNAPSHOT_VERSION.test(value)) {
          throw new Error('No se puede obtener la versión del contenido');
        }
        return value;
      })
      .catch((error: unknown) => {
        version = undefined; // permite reintentar
        throw error;
      });
    return version;
  };

  return {
    async collection(name) {
      if (!COLLECTION_NAMES.includes(name)) throw new Error(`Colección desconocida: «${name}»`);
      const current = await currentVersion();
      const response = await fetcher(`/content/${current}/${name}.json`);
      if (response.status === 410) retired(current);
      if (!response.ok)
        throw new Error(`No se puede cargar la colección «${name}» (${response.status})`);
      return (await response.json()) as ContentCollections[typeof name];
    },

    body(slug) {
      if (!SLUG.test(slug)) return Promise.resolve(null);
      // Varias consultas de la misma página piden el mismo cuerpo: una sola descarga (como el
      // import() del build JSON, que el navegador también descarga una vez).
      let request = bodies.get(slug);
      if (!request) {
        request = loadBody(slug);
        request.catch(() => bodies.delete(slug)); // permite reintentar
        if (bodies.size >= MAX_BODIES) bodies.delete(bodies.keys().next().value as string);
        bodies.set(slug, request);
      }
      return request;
    },
  };

  function retired(current: string): never {
    onRetired(current);
    throw new Error(`La versión ${current} del contenido se ha retirado`);
  }

  async function loadBody(slug: string): Promise<ArticleBodyRecord | null> {
    const current = await currentVersion();
    const response = await fetcher(`/content/${current}/articles/${slug}.json`);
    if (response.status === 410) retired(current);
    if (response.status === 404) return null;
    if (!response.ok)
      throw new Error(`No se puede cargar el artículo «${slug}» (${response.status})`);
    return (await response.json()) as ArticleBodyRecord;
  }
}

/** Cuerpos recientes que se guardan en el navegador. */
const MAX_BODIES = 32;

export const CONTENT_SOURCE = new InjectionToken<ContentSource>('CONTENT_SOURCE', {
  providedIn: 'root',
  factory: () => snapshotBrowserSource(inject(TransferState)),
});
