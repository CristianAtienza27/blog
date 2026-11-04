import type { ArticleFormat, ArticleSummary, DestinationRef } from '../content/models';
import { countryName } from './geo';

/** URL pública de un artículo: /<sección>/<slug>. */
export function articleUrl(article: Pick<ArticleSummary, 'slug' | 'category'>): string {
  return `/${article.category.id}/${article.slug}`;
}

export const FORMAT_LABELS: Record<ArticleFormat, string> = {
  reportaje: 'Reportaje',
  guia: 'Guía',
  'ensayo-fotografico': 'Ensayo fotográfico',
  ficha: 'Ficha',
  nota: 'Nota',
};

/** «Girona · España»: región (si existe) y país en español. */
export function placeArea(place: Pick<DestinationRef, 'region' | 'country'>): string {
  return [place.region, countryName(place.country)].filter(Boolean).join(' · ');
}

/** «Guía · 2 min»: formato y tiempo de lectura. */
export function articleMeta(article: Pick<ArticleSummary, 'format' | 'readingMinutes'>): string {
  return `${FORMAT_LABELS[article.format]} · ${article.readingMinutes} min`;
}

/**
 * Lugar principal de un artículo: el destino retratado en la portada si es uno de los suyos;
 * si no, el primero. null si el artículo no trata ningún destino.
 */
export function mainPlace(
  article: Pick<ArticleSummary, 'destinations'>,
  coverPlace?: string,
): DestinationRef | null {
  const places = article.destinations;
  return places.find((place) => place.id === coverPlace) ?? places[0] ?? null;
}

/** Texto del enlace a la publicación según su formato. */
export const CTA_LABELS: Record<ArticleFormat, string> = {
  reportaje: 'Leer el reportaje',
  guia: 'Ver la guía',
  'ensayo-fotografico': 'Ver la serie',
  ficha: 'Ver la ficha',
  nota: 'Leer la nota',
};
