import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ChapterBlock } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';

/**
 * Inicio de capítulo: número grande, antetítulo y título. El título lleva el id del capítulo
 * (ancla) y tabindex="-1" para recibir el foco al llegar desde un enlace.
 *
 * Escritorio (lg): el número ocupa el margen izquierdo, a la altura del título.
 * Tableta y móvil: número, antetítulo y título apilados sobre la columna de lectura.
 */
@Component({
  selector: 'app-chapter-heading',
  imports: [Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let c = chapter();
    @if (number(); as n) {
      <span class="chapter__number" aria-hidden="true" appReveal="fade">{{ n }}</span>
    }
    <hgroup class="chapter__head">
      <!-- Sin animación de entrada: es el destino del ancla (oculto o desplazado no
           recibiría el foco ni quedaría donde se calculó el desplazamiento). -->
      <h2 class="chapter__title" [id]="c.id" tabindex="-1">
        @if (c.number) {
          <span class="visually-hidden">Capítulo {{ c.number }}. </span>
        }
        {{ c.title }}
      </h2>
      @if (c.kicker) {
        <p class="chapter__kicker t-meta" appReveal="fade">{{ c.kicker }}</p>
      }
    </hgroup>
  `,
  styleUrl: './chapter-heading.scss',
})
export class ChapterHeading {
  readonly chapter = input.required<ChapterBlock>();

  protected readonly number = computed(() => {
    const value = this.chapter().number;
    return value === undefined ? null : String(value).padStart(2, '0');
  });
}
