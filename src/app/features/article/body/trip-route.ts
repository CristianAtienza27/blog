import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Trip } from '../../../content/models';
import { PlaceItems } from './place-items';
import { ChapterIndex, tripPlan } from './place-plan';

/**
 * Módulo de itinerario: nombre, número de paradas y, si existen en los datos, distancia total
 * y medio de transporte; después, las paradas en orden con su tramo, zona y descripción breve.
 * Reutilizable: solo necesita el itinerario, los capítulos que tratan cada parada y la ruta
 * del artículo (para enlazarlos).
 */
@Component({
  selector: 'app-trip-route',
  imports: [PlaceItems],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let p = plan();
    <section class="route" [attr.aria-label]="'Itinerario: ' + p.title">
      <header class="route__head">
        <p class="route__kicker t-meta">Itinerario</p>
        @if (level() === 2) {
          <h2 class="route__title">{{ p.title }}</h2>
        } @else {
          <h3 class="route__title">{{ p.title }}</h3>
        }
        <p class="route__facts t-meta">
          @for (fact of p.facts; track $index) {
            @if (!$first) {
              <span aria-hidden="true"> · </span>
            }
            <span>{{ fact }}</span>
          }
        </p>
        <p class="route__summary">{{ p.summary }}</p>
      </header>
      <app-place-items [items]="p.stops" [basePath]="basePath()" [active]="active()" route />
    </section>
  `,
  styles: `
    .route {
      display: grid;
      gap: var(--space-5);
    }

    .route__head {
      display: grid;
      gap: var(--space-2);
    }

    .route__kicker {
      color: var(--accent);
    }

    .route__title {
      font-size: var(--text-xl);
      line-height: var(--leading-tight);
      text-wrap: balance;
    }

    .route__facts,
    .route__summary {
      color: var(--color-ink-muted);
    }

    .route__summary {
      font-size: var(--text-sm);
      text-wrap: pretty;
    }
  `,
})
export class TripRoute {
  readonly trip = input.required<Trip>();
  readonly chapters = input<ChapterIndex>({});
  readonly basePath = input.required<string>();
  /** Destino del capítulo que se lee. */
  readonly active = input<string | null>(null);
  readonly level = input<2 | 3>(2);

  protected readonly plan = computed(() => tripPlan(this.trip(), this.chapters()));
}
