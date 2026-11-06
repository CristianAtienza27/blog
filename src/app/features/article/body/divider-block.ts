import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { DividerVariant } from '../../../content/models';

/**
 * Separador entre partes del relato (<hr>: cambio de tema para los lectores de pantalla).
 * - line: filete fino a lo ancho de la columna.
 * - ornament: tres puntos centrados en el color del acento.
 * - pause: solo aire.
 */
@Component({
  selector: 'app-divider-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-variant]': 'variant()' },
  template: `<hr class="divider" />`,
  styles: `
    :host {
      display: block;
    }

    .divider {
      block-size: var(--border-width);
      margin: 0;
      background-color: var(--color-line);
      border: 0;
    }

    :host([data-variant='ornament']) .divider {
      block-size: 6px;
      background: radial-gradient(circle, var(--accent) 2.5px, transparent 3px) center / 1.5rem 100%
        space;
      inline-size: 4.5rem;
      margin-inline: auto;
    }

    :host([data-variant='pause']) .divider {
      block-size: var(--space-6);
      background: none;
    }
  `,
})
export class DividerBlock {
  readonly variant = input.required<DividerVariant>();
}
