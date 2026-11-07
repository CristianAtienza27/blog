import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';
import type { PlacePlan, StopPlan } from './place-plan';

/**
 * Lista ordenada de lugares (paradas de una ruta o lugares de un artículo), común a TripRoute y
 * PlaceList: número, nombre, zona, descripción breve y enlace a su capítulo. En una ruta, cada
 * parada lleva antes el tramo desde la anterior (solo si hay datos) y una línea las une.
 * El lugar del capítulo que se lee lleva aria-current y la marca «Leyendo» (no solo color).
 */
@Component({
  selector: 'app-place-items',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-route]': 'route() || null' },
  template: `
    <ol class="items">
      @for (item of items(); track item.destination) {
        @let current = item.destination === active();
        <li
          class="item"
          [class.is-active]="current"
          [attr.aria-current]="current ? (route() ? 'step' : 'location') : null"
        >
          @if (legOf(item); as leg) {
            <p class="item__leg t-meta">
              <span class="visually-hidden">Desde la parada anterior: </span>{{ leg }}
            </p>
          }
          <span class="item__number" aria-hidden="true">{{ item.number }}</span>
          <div class="item__body">
            <p class="item__name">
              {{ item.name }}
              @if (current) {
                <span class="item__now t-meta">Leyendo</span>
              }
            </p>
            <p class="item__area t-meta">{{ item.area }}</p>
            <p class="item__summary">{{ item.summary }}</p>
            @if (item.chapter; as chapter) {
              <a class="item__link t-meta" [href]="basePath() + '#' + chapter.id">
                {{ chapter.number ? 'Capítulo ' + chapter.number : 'Ir al capítulo' }}
                <span aria-hidden="true">→</span>
                <span class="visually-hidden">: {{ item.name }}</span>
              </a>
            }
          </div>
        </li>
      }
    </ol>
  `,
  styleUrl: './place-items.scss',
})
export class PlaceItems {
  readonly items = input.required<readonly (PlacePlan | StopPlan)[]>();
  /** Ruta del artículo: los enlaces a capítulos son anclas de esta página. */
  readonly basePath = input.required<string>();
  /** Destino del capítulo que se lee. */
  readonly active = input<string | null>(null);
  /** Paradas de una ruta (con tramos y línea) o lugares sueltos. */
  readonly route = input(false, { transform: booleanAttribute });

  protected legOf(item: PlacePlan | StopPlan): string | null {
    return 'leg' in item ? item.leg : null;
  }
}
