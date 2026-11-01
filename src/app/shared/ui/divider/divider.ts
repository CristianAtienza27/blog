import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type DividerVariant = 'line' | 'strong' | 'label';

/** Separador horizontal. Con variant="label" muestra un texto centrado entre dos líneas. */
@Component({
  selector: 'app-divider',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'separator', '[attr.data-variant]': 'variant()' },
  template: `
    @if (variant() === 'label') {
      <span class="label"><ng-content /></span>
    }
  `,
  styleUrl: './divider.scss',
})
export class Divider {
  readonly variant = input<DividerVariant>('line');
}
