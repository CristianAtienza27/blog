import type { ArticleSummary, Destination, Media, SectionId } from '../content/models';
import { articleUrl, placeArea } from './article-meta';

/** Una línea del índice editorial de destinos. */
export interface DestinationIndexEntry {
  id: string;
  /** «01», «02»… */
  number: string;
  name: string;
  /** «Girona · España». */
  area: string;
  country: string;
  /** Publicación relacionada (null si ningún artículo trata el destino). */
  article: { title: string; href: string } | null;
  /** Vista previa al pasar el ratón o con el foco (null si no hay foto apta). */
  preview: Media | null;
}

export interface DestinationIndexOptions {
  /** Fotografías que no deben aparecer (la del hero, las retiradas). */
  excludeMedia?: readonly string[];
  /** Sección preferida cuando varios artículos tratan el mismo destino. */
  preferSection?: SectionId;
}

/**
 * Índice alfabético de destinos con su publicación relacionada: la más reciente de la sección
 * preferida o, si no hay, la más reciente de cualquier sección. `articles` llega ordenado
 * del más reciente al más antiguo (como lo devuelve ArticleRepository.list).
 */
export function buildDestinationIndex(
  destinations: readonly Destination[],
  articles: readonly ArticleSummary[],
  options: DestinationIndexOptions = {},
): DestinationIndexEntry[] {
  const exclude = new Set(options.excludeMedia ?? []);
  const preferred = [
    ...articles.filter((a) => a.category.id === options.preferSection),
    ...articles.filter((a) => a.category.id !== options.preferSection),
  ];
  return [...destinations]
    .sort((a, b) => a.name.localeCompare(b.name, 'es'))
    .map((destination, i) => {
      const article = preferred.find((a) => a.destinations.some((d) => d.id === destination.id));
      const cover = destination.cover;
      return {
        id: destination.id,
        number: String(i + 1).padStart(2, '0'),
        name: destination.name,
        area: placeArea(destination),
        country: destination.country,
        article: article ? { title: article.title, href: articleUrl(article) } : null,
        preview: cover && !exclude.has(cover.id) ? cover : null,
      };
    });
}
