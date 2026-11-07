import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Destination } from '../../../content/models';
import { PlaceItems } from './place-items';
import { ChapterIndex, placesPlan } from './place-plan';

/**
 * Lugares relacionados con el artículo: nombre, región y país, descripción y enlace al
 * capítulo que trata cada uno (los destinos aún no tienen página propia). Sin coordenadas ni
 * datos que no existan en el contenido.
 */
@Component({
  selector: 'app-place-list',
  imports: [PlaceItems],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="places" aria-label="Lugares del artículo">
      <header class="places__head">
        <p class="places__kicker t-meta">{{ count() }}</p>
        @if (title(); as t) {
          @if (level() === 2) {
            <h2 class="places__title">{{ t }}</h2>
          } @else {
            <h3 class="places__title">{{ t }}</h3>
          }
        }
      </header>
      <app-place-items [items]="items()" [basePath]="basePath()" [active]="active()" />
    </section>
  `,
  styles: `
    .places {
      display: grid;
      gap: var(--space-4);
    }

    .places__head {
      display: grid;
      gap: var(--space-2);
    }

    .places__kicker {
      color: var(--accent);
    }

    .places__title {
      font-size: var(--text-xl);
      line-height: var(--leading-tight);
      text-wrap: balance;
    }
  `,
})
export class PlaceList {
  readonly places = input.required<readonly Destination[]>();
  readonly chapters = input<ChapterIndex>({});
  readonly basePath = input.required<string>();
  /** Destino del capítulo que se lee. */
  readonly active = input<string | null>(null);
  /** Título opcional (el pie del mapa, por ejemplo). */
  readonly title = input<string | null>(null);
  readonly level = input<2 | 3>(2);

  protected readonly items = computed(() => placesPlan(this.places(), this.chapters()));
  protected readonly count = computed(() =>
    this.places().length === 1 ? 'Un lugar' : `${this.places().length} lugares`,
  );
}
