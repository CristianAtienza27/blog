import { DemoFlag } from './common';
import { ContentBlock } from './content-block';
import { Media } from './media';
import { Author } from './author';
import { Project } from './project';
import { Category, Tag } from './taxonomy';
import { DestinationRef, Trip } from './travel';

export const ARTICLE_FORMATS = [
  'reportaje',
  'guia',
  'ensayo-fotografico',
  'ficha',
  'nota',
] as const;
export type ArticleFormat = (typeof ARTICLE_FORMATS)[number];

/** Lo necesario para tarjetas y listados (sin el cuerpo). */
export interface ArticleSummary extends DemoFlag {
  id: string;
  slug: string;
  title: string;
  /** Subtítulo o entradilla. */
  dek: string;
  excerpt: string;
  category: Category;
  tags: Tag[];
  format: ArticleFormat;
  /** Fotografía de portada. Opcional: sin ella, cabecera y tarjetas se componen sin foto. */
  cover?: Media;
  author: Author;
  /**
   * Posición en el orden editorial de su sección (1, 2, 3…; única en la sección). Decide la
   * navegación anterior/siguiente y los listados que no piden un orden cronológico.
   */
  order: number;
  /** Fecha ISO (AAAA-MM-DD). En contenido demo no es una fecha real de publicación. */
  publishedAt: string;
  updatedAt?: string;
  readingMinutes: number;
  featured: boolean;
  /** Lugares que trata: paradas del itinerario y destinos propios, sin repetir. */
  destinations: DestinationRef[];
}

export interface ArticleSeo {
  description?: string;
}

export interface Article extends ArticleSummary {
  blocks: ContentBlock[];
  trip?: Trip;
  project?: Project;
  seo?: ArticleSeo;
}
