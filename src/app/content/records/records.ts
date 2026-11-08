import type {
  ArticleFormat,
  ArticleSeo,
  Author,
  Category,
  ChapterBlock,
  CodeBlock,
  DividerBlock,
  FiguresLayout,
  FocalPoint,
  GalleryLayout,
  GeoPoint,
  ImageAlign,
  ImageLayout,
  ImageRatio,
  Media,
  ProjectStatus,
  QuoteBlock,
  SpecsBlock,
  Tag,
  TextBlock,
  TravelMode,
} from '../models';

/**
 * Modelos de almacenamiento: la forma de los JSON locales y el contrato de la futura API.
 * Las relaciones van como identificadores; el adaptador las resuelve a modelos de dominio.
 */

export type CategoryRecord = Category;
export type TagRecord = Tag;

export const ORIGINAL_FORMATS = ['jpeg', 'png', 'heic', 'heif', 'webp', 'avif', 'tiff'] as const;
export type OriginalFormat = (typeof ORIGINAL_FORMATS)[number];

/**
 * Original del que se generan las versiones optimizadas. Vive fuera del repositorio
 * (media-src/ en local, un contenedor privado de Azure Blob Storage en producción)
 * y nunca se publica. No guarda datos sensibles: el GPS no sale del archivo original.
 */
export interface MediaOriginal {
  /** Clave en el almacén de originales, derivada del id: «viajes-cadaques.jpg». */
  file: string;
  format: OriginalFormat;
  width: number;
  height: number;
  bytes: number;
  /** SHA-256 del original, para identificarlo y detectar duplicados. */
  sha256: string;
  /** Solo fuentes externas: URL directa del archivo, para recuperarlo. */
  url?: string;
  /**
   * Apple ProRAW (DNG) asociado, archivado sin modificar y sin revelar. Las versiones
   * públicas salen de la exportación (`file`), nunca del DNG.
   */
  raw?: { file: string; bytes: number; sha256: string };
}

export interface MediaRecord extends Media {
  original: MediaOriginal;
}

export interface AuthorRecord extends Omit<Author, 'avatar'> {
  avatar?: string;
}

export interface GalleryRecord {
  id: string;
  slug: string;
  title: string;
  description?: string;
  layout: GalleryLayout;
  items: { media: string; caption?: string }[];
  demo: boolean;
}

export interface DestinationRecord {
  id: string;
  slug: string;
  name: string;
  country: string;
  region?: string;
  location: GeoPoint;
  elevation?: number;
  summary: string;
  cover?: string;
  demo: boolean;
}

export interface TripStopRecord {
  order: number;
  destination: string;
  note?: string;
  distanceKm?: number;
  mode?: TravelMode;
}

export interface TripRecord {
  id: string;
  slug: string;
  title: string;
  summary: string;
  stops: TripStopRecord[];
  cover?: string;
  demo: boolean;
}

export interface ProjectRecord {
  id: string;
  slug: string;
  name: string;
  summary: string;
  status: ProjectStatus;
  year: number;
  stack: string[];
  repositoryUrl?: string;
  cover?: string;
  demo: boolean;
}

/** Metadatos del artículo (articles.json). El cuerpo vive en un archivo aparte. */
export interface ArticleRecord {
  id: string;
  slug: string;
  title: string;
  dek: string;
  excerpt: string;
  category: Category['id'];
  tags: string[];
  format: ArticleFormat;
  /** Fotografía de portada (id de media.json). Sin ella, el artículo se presenta sin foto. */
  cover?: string;
  author: string;
  /** Orden editorial dentro de la sección (entero positivo, único en la sección). */
  order: number;
  publishedAt: string;
  updatedAt?: string;
  readingMinutes: number;
  featured: boolean;
  demo: boolean;
  trip?: string;
  /** Destinos que trata además de las paradas de su itinerario (ids de destinations.json). */
  destinations?: string[];
  project?: string;
  seo?: ArticleSeo;
}

export interface ImageBlockRecord {
  type: 'image';
  media: string;
  layout: ImageLayout;
  caption?: string;
  ratio?: ImageRatio;
  align?: ImageAlign;
  focal?: FocalPoint;
}

export interface FigureItemRecord {
  media: string;
  ratio?: ImageRatio;
  focal?: FocalPoint;
  caption?: string;
}

/** Díptico (2), tríptico (3) o secuencia (2 o 3) de fotografías. */
export interface FiguresBlockRecord {
  type: 'figures';
  layout: FiguresLayout;
  items: FigureItemRecord[];
}

export interface GalleryBlockRecord {
  type: 'gallery';
  gallery: string;
}

/** Mapa a partir de un itinerario o de una lista de destinos. */
export interface MapBlockRecord {
  type: 'map';
  trip?: string;
  destinations?: string[];
  route: boolean;
  caption?: string;
}

export type ContentBlockRecord =
  | TextBlock
  | ImageBlockRecord
  | FiguresBlockRecord
  | GalleryBlockRecord
  | QuoteBlock
  | MapBlockRecord
  | CodeBlock
  | SpecsBlock
  | ChapterBlock
  | DividerBlock;

/** Cuerpo del artículo (articles/<slug>.json). */
export interface ArticleBodyRecord {
  slug: string;
  blocks: ContentBlockRecord[];
}

/** Colecciones de contenido con carga diferida. */
export interface ContentCollections {
  categories: CategoryRecord[];
  tags: TagRecord[];
  authors: AuthorRecord[];
  media: MediaRecord[];
  galleries: GalleryRecord[];
  destinations: DestinationRecord[];
  trips: TripRecord[];
  projects: ProjectRecord[];
  articles: ArticleRecord[];
}

export type CollectionName = keyof ContentCollections;

export const COLLECTION_NAMES: readonly CollectionName[] = [
  'categories',
  'tags',
  'authors',
  'media',
  'galleries',
  'destinations',
  'trips',
  'projects',
  'articles',
];
