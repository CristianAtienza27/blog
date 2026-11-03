import { GeoPoint } from './common';
import { Gallery, Media } from './media';

export type ImageLayout = 'content' | 'wide' | 'full';
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
  | GalleryBlock
  | QuoteBlock
  | MapBlock
  | CodeBlock
  | SpecsBlock
  | ChapterBlock
  | DividerBlock;

export type ContentBlockType = ContentBlock['type'];
