import { GeoPoint } from './common';
import { FocalPoint, Gallery, Media } from './media';

export type ImageLayout = 'content' | 'wide' | 'full';

/** Recortes admitidos en el cuerpo (relación de aspecto CSS). Sin recorte: la de la foto. */
export const IMAGE_RATIOS = ['4/5', '1/1', '3/2', '16/9', '21/9'] as const;
export type ImageRatio = (typeof IMAGE_RATIOS)[number];

/** Hacia qué margen rompe la columna de lectura una fotografía. */
export const IMAGE_ALIGNS = ['start', 'end'] as const;
export type ImageAlign = (typeof IMAGE_ALIGNS)[number];

/** Composiciones de dos o tres fotografías. */
export const FIGURES_LAYOUTS = ['diptych', 'triptych', 'sequence'] as const;
export type FiguresLayout = (typeof FIGURES_LAYOUTS)[number];
export type QuoteVariant = 'pull' | 'block';
export type DividerVariant = 'line' | 'ornament' | 'pause';

/** Texto enriquecido: HTML limitado a p, h3, em, strong, a, code, ul, ol, li y br. */
export interface TextBlock {
  type: 'text';
  html: string;
}

export interface ImageBlock {
  type: 'image';
  media: Media;
  layout: ImageLayout;
  /** Sustituye al pie de la imagen en este artículo. */
  caption?: string;
  /** Recorte en este artículo (por defecto, la proporción de la foto). */
  ratio?: ImageRatio;
  /** Rompe la columna hacia el margen izquierdo (start) o derecho (end). No con «full». */
  align?: ImageAlign;
  /** Sustituye al punto focal de la foto para este recorte. */
  focal?: FocalPoint;
}

/** Una fotografía de una composición, con su propio recorte, punto focal y pie. */
export interface FigureItem {
  media: Media;
  ratio?: ImageRatio;
  focal?: FocalPoint;
  /** Sustituye al pie de la imagen en esta composición. */
  caption?: string;
}

/**
 * Composición de fotografías dentro del relato.
 * - diptych: dos, juntas y a la misma altura.
 * - triptych: tres, juntas y a la misma altura.
 * - sequence: dos o tres en escalera, numeradas, para leerlas en orden.
 */
export interface FiguresBlock {
  type: 'figures';
  layout: FiguresLayout;
  items: FigureItem[];
}

export interface GalleryBlock {
  type: 'gallery';
  gallery: Gallery;
}

export interface QuoteBlock {
  type: 'quote';
  text: string;
  cite?: string;
  variant: QuoteVariant;
}

export interface MapPoint {
  label: string;
  location: GeoPoint;
  /** Destino de origen, si lo hay. */
  slug?: string;
}

/** Mapa propio en SVG a partir de coordenadas (sin teselas externas). */
export interface MapBlock {
  type: 'map';
  points: MapPoint[];
  /** Une los puntos en orden, como una ruta. */
  route: boolean;
  caption?: string;
}

export interface CodeBlock {
  type: 'code';
  language: string;
  code: string;
  filename?: string;
  caption?: string;
}

export interface SpecItem {
  label: string;
  value: string;
  unit?: string;
}

/**
 * Prefijo de los ids que la página genera para los títulos de las fichas («datos-3»).
 * Reservado: ningún capítulo puede usarlo como id.
 */
export const SPECS_ID_PREFIX = 'datos-';

/** Ficha de datos técnicos. */
export interface SpecsBlock {
  type: 'specs';
  title?: string;
  items: SpecItem[];
  /** Fuente de los datos o aviso (por ejemplo, «datos de ejemplo»). */
  note?: string;
}

/** Inicio de capítulo. `id` sirve de ancla para el índice. */
export interface ChapterBlock {
  type: 'chapter';
  id: string;
  number?: number;
  title: string;
  kicker?: string;
}

export interface DividerBlock {
  type: 'divider';
  variant: DividerVariant;
}

export type ContentBlock =
  | TextBlock
  | ImageBlock
  | FiguresBlock
  | GalleryBlock
  | QuoteBlock
  | MapBlock
  | CodeBlock
  | SpecsBlock
  | ChapterBlock
  | DividerBlock;

export type ContentBlockType = ContentBlock['type'];
