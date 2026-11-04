import type { Article, Media, SectionId } from '../../content/models';
import { FORMAT_LABELS, placeArea } from '../../editorial/article-meta';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import { SECTIONS } from '../../layout/navigation';

/**
 * Cabeceras de artículo:
 * - cinema: foto a sangre con el titular encima (reportajes, itinerarios, ensayos).
 * - split: titular y foto en columnas asimétricas (guías).
 * - plate: titular grande y foto ancha debajo (fichas, relatos).
 * - compact: cabecera breve (notas y tecnología).
 */
export const HEADER_VARIANTS = ['cinema', 'split', 'plate', 'compact'] as const;
export type HeaderVariant = (typeof HEADER_VARIANTS)[number];

/**
 * Tamaño mínimo de una foto a sangre para verse nítida en pantallas de densidad 2×. Con menos,
 * la cabecera cambia a una composición en la que la foto se muestra más pequeña.
 */
export const FULL_BLEED_MIN = { width: 2400, height: 1300 } as const;

/** Proporción desde la que una foto se trata como panorámica (se muestra sin recortar). */
const PANORAMA = 2;

export interface ArticleFact {
  /** Nombre del dato para lectores de pantalla («Lectura», «Recorrido»). */
  label: string;
  value: string;
  /** Solo en fechas: valor ISO para <time>. */
  datetime?: string;
}

export interface HeaderSection {
  id: SectionId;
  index: number;
  label: string;
  path: string;
}

export interface HeaderCover {
  media: Media;
  /** Atributo sizes según el recorte de cada composición. */
  sizes: string;
  /** Proporción del marco en escritorio (plate y compact). */
  ratio: string;
  /** compact: la foto ocupa solo parte del ancho porque no tiene resolución para más. */
  narrow: boolean;
}

/** Lo que necesita la cabecera, ya resuelto: el componente solo compone. */
export interface ArticleHeaderData {
  variant: HeaderVariant;
  title: string;
  dek: string;
  section: HeaderSection;
  format: string;
  cover: HeaderCover | null;
  facts: ArticleFact[];
  demo: boolean;
}

export interface ArticlePageData {
  article: Article;
  header: ArticleHeaderData;
}

/** Composición propia de cada formato. */
function preferredVariant(article: Article): HeaderVariant {
  if (article.category.id === 'tecnologia') return 'compact';
  switch (article.format) {
    case 'nota':
      return 'compact';
    case 'ficha':
      return 'plate';
    case 'guia':
      return article.trip ? 'cinema' : 'split';
    case 'ensayo-fotografico':
      return 'cinema';
    case 'reportaje':
      return article.category.id === 'experiencias' ? 'plate' : 'cinema';
  }
}

const ratioOf = (media: Media) => media.width / media.height;

/** La variante del formato, salvo que la foto no tenga resolución para mostrarse a sangre. */
export function headerVariant(article: Article, cover: Media | null): HeaderVariant {
  if (!cover) return 'compact';
  const preferred = preferredVariant(article);
  const sharp = cover.width >= FULL_BLEED_MIN.width;
  if (preferred === 'cinema' && !(sharp && cover.height >= FULL_BLEED_MIN.height)) {
    // Una panorámica pierde altura a pantalla completa: se muestra entera y ancha.
    return sharp && ratioOf(cover) >= PANORAMA ? 'plate' : 'split';
  }
  if (preferred === 'plate' && !sharp) return 'split';
  return preferred;
}

/** Factor de recorte: cuánto más ancha debe ser la foto que su marco para cubrirlo. */
export function cropFactor(photoRatio: number, frameRatio: number): number {
  return Math.max(1, photoRatio / frameRatio);
}

const vw = (value: number) => `${Math.ceil(value)}vw`;
const px = (value: number) => `${Math.ceil(value)}px`;

/** Proporción del marco en escritorio: la propia si es ancha; si no, un recorte apaisado. */
function frameRatio(variant: HeaderVariant, media: Media): number {
  const own = ratioOf(media);
  if (variant === 'plate') return own >= 1.7 ? own : 16 / 9;
  if (variant === 'compact') return own >= PANORAMA ? own : 21 / 9;
  return variant === 'split' ? 4 / 3 : 16 / 9;
}

/**
 * sizes de cada composición (escritorio desde md, 900 px). Tiene en cuenta el recorte:
 * una foto de 3:2 en un marco de 4:5 necesita casi el doble de ancho que el marco.
 */
export function coverSizes(variant: HeaderVariant, media: Media, narrow = false): string {
  const r = ratioOf(media);
  const desktop = cropFactor(r, frameRatio(variant, media));
  switch (variant) {
    case 'cinema':
      return `(width < 900px) ${vw(100 * cropFactor(r, 4 / 5))}, ${vw(100 * desktop)}`;
    case 'split':
      // Desde la séptima columna hasta el borde: la mitad de la pantalla.
      return `(width < 900px) ${vw(100 * cropFactor(r, 4 / 5))}, ${vw(50 * desktop)}`;
    case 'plate':
      return `(width < 900px) ${vw(100 * cropFactor(r, 3 / 2))}, (width >= 1568px) ${px(1440 * desktop)}, ${vw(92 * desktop)}`;
    case 'compact': {
      const mobile = `(width < 900px) ${vw(90 * cropFactor(r, 16 / 9))}`;
      return narrow
        ? `${mobile}, (width >= 1328px) ${px(800 * desktop)}, ${vw(61 * desktop)}`
        : `${mobile}, (width >= 1328px) ${px(1200 * desktop)}, ${vw(92 * desktop)}`;
    }
  }
}

function headerCover(variant: HeaderVariant, media: Media | null): HeaderCover | null {
  if (!media) return null;
  const narrow = variant === 'compact' && media.width < FULL_BLEED_MIN.width;
  const ratio = frameRatio(variant, media);
  return {
    media,
    sizes: coverSizes(variant, media, narrow),
    ratio: ratio === ratioOf(media) ? `${media.width} / ${media.height}` : ratio.toFixed(4),
    narrow,
  };
}

const DATE = new Intl.DateTimeFormat('es-ES', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
});

/** «18 de septiembre de 2026» a partir de AAAA-MM-DD. */
export function formatDate(iso: string): string {
  return DATE.format(new Date(`${iso}T00:00:00Z`));
}

/**
 * Metadatos de la cabecera: lectura, lugares o recorrido y, solo en contenido definitivo,
 * fechas (en el de demostración no son reales y no se muestran).
 */
export function articleFacts(article: Article): ArticleFact[] {
  const facts: ArticleFact[] = [
    { label: 'Lectura', value: `${article.readingMinutes} min de lectura` },
  ];
  const stops = article.trip?.stops ?? [];
  const places = article.destinations;
  if (stops.length > 1) {
    const first = stops[0].destination.name;
    const last = stops[stops.length - 1].destination.name;
    facts.push({ label: 'Recorrido', value: `${stops.length} paradas: de ${first} a ${last}` });
    const km = stops.reduce((total, stop) => total + (stop.distanceKm ?? 0), 0);
    if (km > 0) facts.push({ label: 'Distancia', value: `${km} km aprox.` });
  } else if (places.length === 1) {
    facts.push({ label: 'Lugar', value: [places[0].name, placeArea(places[0])].join(' · ') });
  } else if (places.length > 1) {
    facts.push({ label: 'Lugares', value: places.map((place) => place.name).join(' · ') });
  }
  if (!article.demo) {
    facts.push({
      label: 'Publicado',
      value: formatDate(article.publishedAt),
      datetime: article.publishedAt,
    });
    if (article.updatedAt && article.updatedAt !== article.publishedAt) {
      facts.push({
        label: 'Actualizado',
        value: `Actualizado el ${formatDate(article.updatedAt)}`,
        datetime: article.updatedAt,
      });
    }
  }
  return facts;
}

/** Portada utilizable: nunca una fotografía retirada (el artículo queda sin foto). */
export function articleCover(article: Pick<Article, 'cover'>): Media | null {
  return RETIRED_MEDIA.includes(article.cover.id) ? null : article.cover;
}

function headerSection(id: SectionId): HeaderSection {
  const section = SECTIONS.find((candidate) => candidate.id === id);
  return {
    id,
    index: section?.index ?? 0,
    label: section?.label ?? id,
    path: section?.path ?? `/${id}`,
  };
}

/** Datos de la página de un artículo: el artículo completo y su cabecera. */
export function articlePresentation(article: Article): ArticlePageData {
  const media = articleCover(article);
  const variant = headerVariant(article, media);
  return {
    article,
    header: {
      variant,
      title: article.title,
      dek: article.dek,
      section: headerSection(article.category.id),
      format: FORMAT_LABELS[article.format],
      cover: headerCover(variant, media),
      facts: articleFacts(article),
      demo: article.demo,
    },
  };
}
