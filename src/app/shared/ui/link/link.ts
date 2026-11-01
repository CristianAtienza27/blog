import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type LinkVariant = 'inline' | 'quiet' | 'arrow';

/**
 * Enlace editorial.
 * - inline: subrayado fino permanente (dentro de texto).
 * - quiet: sin subrayado; aparece al pasar el ratón (navegación, listados).
 * - arrow: llamada a la acción en versalitas con flecha.
 */
@Component({
  selector: 'a[app-link]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-variant]': 'variant()' },
  template: `
    <ng-content />
    @if (variant() === 'arrow') {
      <svg class="arrow" viewBox="0 0 24 12" aria-hidden="true">
        <path d="M0 6h22M17 1l5 5-5 5" />
      </svg>
    }
  `,
  styleUrl: './link.scss',
})
export class Link {
  readonly variant = input<LinkVariant>('inline');
}
