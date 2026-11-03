import { DemoFlag, GeoPoint } from './common';

/** `propia`: fotografía del autor. El resto son fuentes externas (material provisional). */
export const MEDIA_SOURCES = ['propia', 'wikimedia', 'unsplash', 'pexels'] as const;
export type MediaSource = (typeof MEDIA_SOURCES)[number];

export const MEDIA_LICENSES = [
  'derechos-reservados',
  'CC0-1.0',
  'PDM-1.0',
  'CC-BY-2.0',
  'CC-BY-3.0',
  'CC-BY-4.0',
  'CC-BY-SA-2.0',
  'CC-BY-SA-3.0',
  'CC-BY-SA-4.0',
  'Unsplash',
  'Pexels',
] as const;
export type MediaLicense = (typeof MEDIA_LICENSES)[number];

/**
 * Autoría y licencia: se muestran siempre junto a la imagen.
 * - Fotografía propia: autor de la revista, sin URL de origen.
 * - Fuente externa: URL de la obra obligatoria y licencia libre.
 */
export interface MediaCredit {
  author: string;
  source: MediaSource;
  /** Página de la obra en su fuente (no el archivo). Solo en fuentes externas. */
  sourceUrl?: string;
  license: MediaLicense;
  licenseUrl?: string;
}

/** Punto de interés de la imagen (0–1) para recortes con object-fit: cover. */
export interface FocalPoint {
  x: number;
  y: number;
}

/**
 * Lugar de la fotografía, escrito a mano. Nunca se rellena con el GPS del archivo:
 * las coordenadas solo se publican si se decide expresamente y con precisión reducida.
 */
export interface MediaLocation {
  /** Nombre visible: «Cadaqués», «Paso del Stelvio». */
  name: string;
  region?: string;
  /** Código ISO 3166-1 alfa-2. */
  country?: string;
  /** Máximo dos decimales (~1 km). */
  point?: GeoPoint;
}

/** Datos técnicos de la toma (sin información personal). */
export interface CameraInfo {
  make?: string;
  model?: string;
  lens?: string;
  /** Distancia focal en mm. */
  focalLength?: number;
  /** Número f. */
  aperture?: number;
  /** Tiempo de exposición: «1/500», «2». */
  exposure?: string;
  iso?: number;
}

/**
 * Fotografía optimizada (AVIF y WebP en varios anchos). `path` es relativo al almacén
 * público de medios (/media en local, un contenedor de Azure Blob Storage en producción):
 * el archivo final es `${path}-${ancho}.${formato}`.
 *
 * Los artículos solo guardan el `id`: sustituir una imagen provisional por una propia
 * consiste en reemplazar el registro manteniendo su `id`.
 */
export interface Media extends DemoFlag {
  id: string;
  path: string;
  width: number;
  height: number;
  /** Anchos generados, de menor a mayor. */
  widths: number[];
  /** Qué se ve, en una frase. */
  alt: string;
  /** Título de la fotografía (series, fotografía de autor). */
  title?: string;
  /** Pie editorial. */
  caption?: string;
  /** Descripción larga opcional. */
  description?: string;
  /** Fecha de la toma (AAAA-MM-DD), sin hora. */
  takenAt?: string;
  location?: MediaLocation;
  camera?: CameraInfo;
  focal: FocalPoint;
  /** Vista previa difuminada (data URL) mientras carga la imagen. */
  placeholder: string;
  credit: MediaCredit;
}

export type GalleryLayout = 'grid' | 'horizontal' | 'diptych';

export interface GalleryItem {
  media: Media;
  caption?: string;
}

/** Serie fotográfica reutilizable. */
export interface Gallery extends DemoFlag {
  id: string;
  slug: string;
  title: string;
  description?: string;
  layout: GalleryLayout;
  items: GalleryItem[];
}
