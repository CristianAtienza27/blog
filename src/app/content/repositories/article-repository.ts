import type { Article, ArticleFormat, ArticleSummary, Page, SectionId } from '../models';

export const DEFAULT_PAGE_SIZE = 12;
export const MAX_PAGE_SIZE = 50;

/** Filtros de listado. Las relaciones se indican por slug, como en las URL públicas. */
export interface ArticleQuery {
  category?: SectionId;
  tag?: string;
  format?: ArticleFormat;
  trip?: string;
  destination?: string;
  project?: string;
  featured?: boolean;
  /** Orden cronológico expreso. Sin él, el orden editorial (sección y `order`). */
  sort?: 'newest' | 'oldest';
  offset?: number;
  limit?: number;
}

/**
 * Acceso a los artículos. Los componentes dependen solo de esta clase:
 * el adaptador (JSON local o API REST) se elige en provideContent().
 */
export abstract class ArticleRepository {
  abstract list(query?: ArticleQuery): Promise<Page<ArticleSummary>>;
  abstract bySlug(slug: string): Promise<Article | null>;
  /** Búsqueda por texto, combinable con los filtros. Ordena por relevancia. */
  abstract search(term: string, query?: ArticleQuery): Promise<Page<ArticleSummary>>;
  /** Artículos relacionados: misma sección, etiquetas y destinos compartidos. */
  abstract related(slug: string, limit?: number): Promise<ArticleSummary[]>;
  /** Todos los slugs (para prerenderizar las páginas de artículo). */
  abstract slugs(): Promise<string[]>;
}
