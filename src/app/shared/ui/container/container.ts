import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type ContainerSize = 'measure' | 'narrow' | 'content' | 'wide' | 'full';

/** Contenedor centrado con márgenes de página. Para maquetas que rompen la retícula usa .page-grid. */
@Component({
  selector: 'app-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-size]': 'size()' },
  template: '<ng-content />',
  styleUrl: './container.scss',
})
export class Container {
  readonly size = input<ContainerSize>('content');
}
