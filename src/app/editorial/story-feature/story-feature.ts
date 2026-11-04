import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/directives/reveal';
import { ScrollZoom } from '../../motion/directives/scroll-zoom';
import { Link } from '../../shared/ui/link/link';
import { Media } from '../../shared/ui/media/media';
import { articleMeta, articleUrl, placeArea } from '../article-meta';
import { PhotoCredit } from '../photo-credit/photo-credit';
import type { StoryData } from '../story';

/**
 * Historia principal: fotografía panorámica a sangre con la información integrada sobre ella
 * (lugar, título, entradilla, metadatos y enlace). En móvil, foto vertical y texto debajo.
 * Al entrar en pantalla, el marco se abre de los márgenes a sangre y la foto se asienta.
 */
@Component({
  selector: 'app-story-feature',
  imports: [Link, Media, PhotoCredit, Reveal, RouterLink, ScrollZoom],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './story-feature.html',
  styleUrl: './story-feature.scss',
})
export class StoryFeature {
  readonly story = input.required<StoryData>();
  /** Texto del enlace a la publicación. */
  readonly cta = input('Descubrir la publicación');

  /**
   * Foto 3:2 recortada: en móvil el marco es 4:5 (la foto mide 1,9 veces su ancho); desde
   * tableta llena el ancho. Margen para el zoom de entrada.
   */
  protected readonly sizes = '(width < 640px) 200vw, 110vw';

  protected readonly href = computed(() => articleUrl(this.story().article));
  protected readonly area = computed(() => {
    const place = this.story().place;
    return place ? placeArea(place) : this.story().article.category.name;
  });
  protected readonly meta = computed(() => {
    const { article } = this.story();
    const places = article.destinations;
    const route =
      places.length > 1
        ? `${places.length} paradas: de ${places[0].name} a ${places[places.length - 1].name}`
        : null;
    return [articleMeta(article), route].filter(Boolean).join(' · ');
  });
}
