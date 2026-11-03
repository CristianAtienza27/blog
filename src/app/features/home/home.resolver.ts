import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import type { Destination, Media } from '../../content/models';
import { MediaRepository } from '../../content/repositories/media-repository';
import { TravelRepository } from '../../content/repositories/travel-repository';
import { HOME_COMPOSITION } from './home.composition';

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
}

/**
 * Datos de la portada. Se resuelven antes de activar la ruta, también en el prerender;
 * en el navegador llegan por TransferState y la página no parpadea al hidratarse.
 */
export const homeResolver: ResolveFn<HomeData> = async () => {
  const mediaRepository = inject(MediaRepository);
  const travelRepository = inject(TravelRepository);
  const { hero } = HOME_COMPOSITION;
  const [media, destination] = await Promise.all([
    mediaRepository.media(hero.media),
    travelRepository.destination(hero.destination),
  ]);
  return {
    hero: media ? { media, destination, kicker: hero.kicker, tagline: hero.tagline } : null,
  };
};
