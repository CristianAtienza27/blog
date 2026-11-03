import { InjectionToken } from '@angular/core';
import type { ArticleBodyRecord, CollectionName, ContentCollections } from '../records/records';
import { SLUG } from '../records/validate';

/** Origen de los registros. Los tests lo sustituyen por datos en memoria. */
export interface ContentSource {
  collection<K extends CollectionName>(name: K): Promise<ContentCollections[K]>;
  body(slug: string): Promise<ArticleBodyRecord | null>;
}

type JsonModule = Promise<{ default: unknown }>;

/**
 * Cada colección es un chunk independiente que solo se descarga cuando se pide.
 * Es el único lugar de la aplicación que importa los archivos de src/content.
 */
const COLLECTIONS: Record<CollectionName, () => JsonModule> = {
  categories: () => import('../../../content/categories.json'),
  tags: () => import('../../../content/tags.json'),
  authors: () => import('../../../content/authors.json'),
  media: () => import('../../../content/media.json'),
  galleries: () => import('../../../content/galleries.json'),
  destinations: () => import('../../../content/destinations.json'),
  trips: () => import('../../../content/trips.json'),
  projects: () => import('../../../content/projects.json'),
  articles: () => import('../../../content/articles.json'),
};

/** Archivos JSON locales incluidos en el build (src/content). */
export const jsonFileSource: ContentSource = {
  async collection(name) {
    const module = await COLLECTIONS[name]();
    return module.default as ContentCollections[typeof name];
  },

  async body(slug) {
    // El slug forma parte de la ruta del import: solo se aceptan slugs válidos.
    if (!SLUG.test(slug)) return null;
    try {
      const module = (await import(`../../../content/articles/${slug}.json`)) as {
        default: unknown;
      };
      return module.default as ArticleBodyRecord;
    } catch {
      return null;
    }
  },
};

export const CONTENT_SOURCE = new InjectionToken<ContentSource>('CONTENT_SOURCE', {
  providedIn: 'root',
  factory: () => jsonFileSource,
});
