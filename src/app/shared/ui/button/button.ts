import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';

export type ButtonVariant = 'solid' | 'outline' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

/**
 * Botón editorial. Se aplica sobre <button> o <a> para conservar la semántica nativa.
 * <a app-button variant="outline" arrow href="…">Leer el relato</a>
 */
@Component({
  selector: 'button[app-button], a[app-button]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-variant]': 'variant()',
    '[attr.data-size]': 'size()',
  },
  template: `
    <span class="label"><ng-content /></span>
    @if (arrow()) {
      <svg class="arrow" viewBox="0 0 24 12" aria-hidden="true">
        <path d="M0 6h22M17 1l5 5-5 5" />
      </svg>
    }
  `,
  styleUrl: './button.scss',
})
export class Button {
  readonly variant = input<ButtonVariant>('solid');
  readonly size = input<ButtonSize>('md');
  readonly arrow = input(false, { transform: booleanAttribute });
}
