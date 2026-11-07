import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { FiguresBlock as FiguresData } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';
import { Media } from '../../../shared/ui/media/media';
import { FigureCaption } from './figure-caption';
import { figuresPlan } from './figure-layout';

/**
 * Composición de dos o tres fotografías dentro del relato.
 * - Díptico y tríptico: fila a la misma altura, cada foto tan ancha como su proporción
 *   (la fila se escala sin recortar más). En móvil se apilan: la primera a sangre y las
 *   demás desplazadas a un lado y a otro.
 * - Secuencia: en escalera y numeradas, para leerlas en orden.
 * Cada fotografía lleva su propio pie y crédito.
 */
@Component({
  selector: 'app-figures-block',
  imports: [FigureCaption, Media, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-layout]': 'plan().layout' },
  template: `
    @let p = plan();
    <div class="figs" [style.--figs-columns]="p.columns">
      @for (item of p.items; track $index) {
        <figure class="figs__item">
          <div class="figs__photo" appReveal="mask">
            <app-media
              [image]="item.media"
              [ratio]="item.ratio"
              [cropped]="item.cropped"
              [sizes]="item.sizes"
              [showCredit]="false"
            />
          </div>
          <figcaption class="figs__caption">
            <app-figure-caption
              [media]="item.media"
              [text]="item.caption"
              [location]="item.location"
              [index]="p.layout === 'sequence' ? item.index : null"
            />
          </figcaption>
        </figure>
      }
    </div>
  `,
  styleUrl: './figures-block.scss',
})
export class FiguresBlock {
  readonly figures = input.required<FiguresData>();

  protected readonly plan = computed(() => figuresPlan(this.figures()));
}
