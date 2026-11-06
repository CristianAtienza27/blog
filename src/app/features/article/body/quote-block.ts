import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { QuoteBlock as QuoteData } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';

/**
 * Cita.
 * - pull: frase destacada en Fraunces cursiva, más ancha que el texto (invade el margen
 *   derecho en escritorio) y con una línea de acento encima. Corta la lectura lo justo.
 * - block: cita dentro de la columna de lectura, con sangría y filete lateral.
 */
@Component({
  selector: 'app-quote-block',
  imports: [Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-variant]': 'quote().variant' },
  template: `
    @let q = quote();
    <figure class="quote" appReveal>
      <blockquote class="quote__text">
        <p>{{ q.text }}</p>
      </blockquote>
      @if (q.cite) {
        <figcaption class="quote__cite t-meta">{{ q.cite }}</figcaption>
      }
    </figure>
  `,
  styleUrl: './quote-block.scss',
})
export class QuoteBlock {
  readonly quote = input.required<QuoteData>();
}
