import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Parallax } from '../../motion/directives/parallax';
import { Reveal } from '../../motion/directives/reveal';
import { ScrollZoom } from '../../motion/directives/scroll-zoom';
import { Link } from '../../shared/ui/link/link';
import { Media } from '../../shared/ui/media/media';
import { CTA_LABELS, articleMeta, articleUrl, placeArea } from '../article-meta';
import { PhotoCredit } from '../photo-credit/photo-credit';
import type { StoryData, StoryLayout } from '../story';

/**
 * Pieza editorial secundaria. Cada composición presenta la publicación de otra manera:
 * - diptych: díptico real, una foto horizontal (70 %) y otra vertical (30 %) juntas y con el
 *   mismo alto; el texto debajo, alineado con las columnas de las fotos.
 * - portrait: foto vertical con el texto al lado, centrado en altura.
 * - strip: franja panorámica a sangre con el titular sobre la imagen.
 *
 * Sin tarjetas: la jerarquía la dan el tamaño de las fotos, las alturas y la tipografía.
 */
@Component({
  selector: 'app-story',
  imports: [Link, Media, NgTemplateOutlet, Parallax, PhotoCredit, Reveal, RouterLink, ScrollZoom],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-layout]': 'layout()' },
  templateUrl: './story.html',
  styleUrl: './story.scss',
})
export class Story {
  readonly story = input.required<StoryData>();

  protected readonly layout = computed<StoryLayout>(() => this.story().layout ?? 'portrait');
  protected readonly titleId = computed(() => `story-${this.story().article.slug}`);
  protected readonly href = computed(() => articleUrl(this.story().article));
  protected readonly cta = computed(() => CTA_LABELS[this.story().article.format]);
  /**
   * «Mallorca · España · Guía · 1 min». La región solo si el artículo trata un único lugar:
   * con varios, la lista de lugares ya lo sitúa (y la región de la foto confundiría).
   */
  protected readonly meta = computed(() => {
    const { article, place } = this.story();
    const area = place && article.destinations.length <= 1 ? placeArea(place) : null;
    return [area, articleMeta(article)].filter(Boolean).join(' · ');
  });
  /** Lugares que trata, en una línea: «Lisboa · Florencia · Praga · Oporto». */
  protected readonly places = computed(() => {
    const places = this.story().article.destinations;
    return places.length > 1 ? places.map((place) => place.name).join(' · ') : null;
  });

  /**
   * Anchos reales de cada recorte (el ancho del marco multiplicado por lo que recorta
   * object-fit: cover y por la escala del parallax). Desde 1600 px la columna de contenido
   * deja de crecer: las fotos del díptico y del retrato, tampoco.
   */
  protected readonly sizes = {
    // Díptico, 70 % del ancho: 3:2 de una foto 4:3, llena el ancho.
    main: '(width >= 1600px) 47vw, (width >= 1200px) 63vw, (width >= 640px) 70vw, 100vw',
    // Díptico, 30 %: marco 9:14 (4:5 en móvil) de una foto 3:2, unas 2,4 veces su ancho.
    second: '(width >= 1600px) 49vw, (width >= 640px) 70vw, 137vw',
    portrait: '(width >= 1600px) 51vw, (width >= 900px) 68vw, 143vw',
    // Panorámica 3,3:1 en marcos de 32:9 (escritorio), 3:1 (tableta) y 2:1 (móvil).
    strip: '(width < 640px) 175vw, (width < 1200px) 115vw, 106vw',
  };
}
