import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ImageBlock } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';
import { Media } from '../../../shared/ui/media/media';
import { FigureCaption } from './figure-caption';
import { figurePlan } from './figure-layout';

/**
 * Fotografía del relato. Rompe la columna de lectura según su colocación (columna, hacia un
 * margen, hasta el borde o a sangre) y lleva el pie en el margen contrario cuando hay sitio.
 * Carga diferida con AVIF/WebP (app-media); el hueco se reserva con la proporción del marco.
 */
@Component({
  selector: 'app-figure-block',
  imports: [FigureCaption, Media, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-placement]': 'plan().placement',
    '[attr.data-align]': 'plan().align',
    '[attr.data-orientation]': 'plan().orientation',
  },
  template: `
    @let p = plan();
    <figure class="fig">
      <div class="fig__photo" appReveal="mask">
        <app-media
          [image]="p.media"
          [ratio]="p.ratio ?? undefined"
          [cropped]="p.cropped"
          [blur]="p.blur"
          [sizes]="p.sizes"
          [showCredit]="false"
        />
      </div>
      <figcaption class="fig__caption">
        <app-figure-caption [media]="p.media" [text]="p.caption" [location]="p.location" />
      </figcaption>
    </figure>
  `,
  styleUrl: './figure-block.scss',
})
export class FigureBlock {
  readonly figure = input.required<ImageBlock>();

  protected readonly plan = computed(() => figurePlan(this.figure()));
}
