import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { Media } from '../../../content/models';
import { PhotoCredit } from '../../../editorial/photo-credit/photo-credit';

/**
 * Pie de una fotografía del relato: número (en secuencias), texto, lugar si la foto lo
 * tiene y crédito con autor, procedencia y licencia. Va dentro de un <figcaption>.
 */
@Component({
  selector: 'app-figure-caption',
  imports: [PhotoCredit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (index(); as i) {
      <span class="caption__index t-meta" aria-hidden="true">{{ i }}</span>
    }
    @if (text(); as t) {
      <span class="caption__text">{{ t }}</span>
    }
    @if (location(); as l) {
      <span class="caption__place t-meta">{{ l }}</span>
    }
    <app-photo-credit class="caption__credit photo-credit--keep" [media]="media()" />
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--space-1);
      align-content: start;
    }

    .caption__index {
      color: var(--accent);
    }

    .caption__text {
      font-size: var(--text-sm);
      line-height: var(--leading-normal);
      color: var(--color-ink-muted);
      text-wrap: pretty;
    }

    .caption__credit {
      font-size: var(--text-2xs);
    }
  `,
})
export class FigureCaption {
  readonly media = input.required<Media>();
  readonly text = input<string | null>(null);
  readonly location = input<string | null>(null);
  readonly index = input<string | null>(null);
}
