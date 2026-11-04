import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import type { ArticleSummary, Destination, Media } from '../../content/models';
import { ArticleRepository, MAX_PAGE_SIZE } from '../../content/repositories/article-repository';
import { MediaRepository } from '../../content/repositories/media-repository';
import { TravelRepository } from '../../content/repositories/travel-repository';
import { mainPlace } from '../../editorial/article-meta';
import { buildDestinationIndex } from '../../editorial/destination-index';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import type { StoryData } from '../../editorial/story';
import { HOME_COMPOSITION } from './home.composition';
import type { IntroData } from './intro/home-intro';
import type { TravelData } from './travel/home-travel';

export interface HeroData {
  media: Media;
  /** Lugar de la fotografía (null si no existe en el contenido). */
  destination: Destination | null;
  kicker: string;
  tagline: readonly string[];
}

export interface HomeData {
  /** null si la fotografía no existe: la portada se muestra sin hero. */
  hero: HeroData | null;
  intro: IntroData;
  travel: TravelData;
}

/** Une un artículo con su lugar principal: el destino retratado en la portada, si lo trata. */
export function toStory(
  articles: readonly ArticleSummary[],
  destinations: readonly Destination[],
  slug: string,
): StoryData | null {
  const article = articles.find((candidate) => candidate.slug === slug);
  if (!article) return null;
  const portrayed = destinations.find((d) => d.cover?.id === article.cover.id);
  return { article, place: mainPlace(article, portrayed?.id) };
}

/**
 * Datos de la portada. Se resuelven antes de activar la ruta, también en el prerender;
 * en el navegador llegan por TransferState y la página no parpadea al hidratarse.
 */
export const homeResolver: ResolveFn<HomeData> = async () => {
  const articleRepository = inject(ArticleRepository);
  const mediaRepository = inject(MediaRepository);
  const travelRepository = inject(TravelRepository);
  const { hero, intro, travel } = HOME_COMPOSITION;
  const secondaryIds = travel.stories.flatMap((s) => ('secondary' in s ? [s.secondary] : []));
  // Una sola consulta de artículos y otra de destinos para toda la portada.
  const [heroMedia, heroPlace, introMedia, secondaryMedia, articles, destinations] =
    await Promise.all([
      mediaRepository.media(hero.media),
      travelRepository.destination(hero.destination),
      mediaRepository.media(intro.media),
      mediaRepository.mediaList(secondaryIds),
      articleRepository.list({ limit: MAX_PAGE_SIZE }),
      travelRepository.destinations(),
    ]);
  const stories = travel.stories.flatMap((composition): StoryData[] => {
    const story = toStory(articles.items, destinations, composition.article);
    if (!story) return [];
    const secondary =
      'secondary' in composition
        ? (secondaryMedia.find((m) => m.id === composition.secondary) ?? null)
        : null;
    return [{ ...story, layout: composition.layout, secondary }];
  });
  return {
    hero: heroMedia
      ? { media: heroMedia, destination: heroPlace, kicker: hero.kicker, tagline: hero.tagline }
      : null,
    intro: { ...intro, media: introMedia },
    travel: {
      feature: toStory(articles.items, destinations, travel.feature),
      stories,
      // Sin la foto del hero ni las retiradas como vista previa.
      index: buildDestinationIndex(destinations, articles.items, {
        excludeMedia: [hero.media, ...RETIRED_MEDIA],
        preferSection: 'viajes',
      }),
    },
  };
};
