import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import type { Destination, MapBlock as MapData } from '../../../content/models';
import { ChapterReading } from './chapter-reading';
import { mapKind } from './map-layout';
import { PlaceList } from './place-list';
import type { ChapterIndex } from './place-plan';
import { RouteMap } from './route-map';
import { TripRoute } from './trip-route';

/** Aviso del pie: el mapa orienta, no guía. */
export function mapNotice(kind: 'geographic' | 'schematic', route: boolean): string {
  if (kind === 'schematic') {
    return 'Esquema sin escala: el orden es real, las posiciones no. No sirve para navegar.';
  }
  const lines = route ? ' Las líneas unen las paradas en orden; no siguen las carreteras.' : '';
  return `Mapa orientativo, no a escala: posiciones aproximadas.${lines} No sirve para navegar.`;
}

/**
 * Mapa del relato con su itinerario (si sale de uno) o con los lugares que trata.
 * - Escritorio (md): mapa y ruta juntos, en columnas asimétricas (7 + 5).
 * - Móvil: el mapa a lo ancho de la pantalla y la ruta debajo, en vertical.
 * Al leer un capítulo que trata un lugar, ese lugar se destaca en el mapa y en la lista.
 */
@Component({
  selector: 'app-map-block',
  imports: [PlaceList, RouteMap, TripRoute],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let m = map();
    <figure class="mapblock__figure">
      <app-route-map [points]="m.points" [route]="m.route" [title]="title()" [active]="active()" />
      <figcaption class="mapblock__caption">
        @if (m.caption) {
          <p class="mapblock__text">{{ m.caption }}</p>
        }
        @if (m.route && m.points.length > 1) {
          <ul class="mapblock__legend t-meta" aria-label="Leyenda">
            <li><span class="mapblock__swatch" aria-hidden="true"></span>Recorrido en orden</li>
            <li><span class="mapblock__stop" aria-hidden="true">01</span>Parada</li>
          </ul>
        }
        <p class="mapblock__note t-meta">{{ notice() }}</p>
      </figcaption>
    </figure>
    @if (m.trip; as trip) {
      <app-trip-route
        class="mapblock__side"
        [trip]="trip"
        [chapters]="chapters()"
        [basePath]="basePath()"
        [active]="active()"
        [level]="level()"
      />
    } @else if (places().length) {
      <app-place-list
        class="mapblock__side"
        [places]="places()"
        [chapters]="chapters()"
        [basePath]="basePath()"
        [active]="active()"
        [level]="level()"
      />
    }
  `,
  styleUrl: './map-block.scss',
})
export class MapBlock {
  readonly map = input.required<MapData>();
  /** Destino → capítulo que lo trata. */
  readonly chapters = input<ChapterIndex>({});
  /** Ruta del artículo (los enlaces a capítulos son anclas de esta página). */
  readonly basePath = input.required<string>();
  readonly level = input<2 | 3>(2);

  /** Capítulo que se lee (fuera de un artículo, ninguno). */
  private readonly reading = inject(ChapterReading);

  protected readonly title = computed(() => {
    const m = this.map();
    return m.trip?.title ?? m.caption ?? 'Mapa de los lugares del artículo';
  });
  protected readonly places = computed(() =>
    this.map().points.flatMap((p): Destination[] => (p.destination ? [p.destination] : [])),
  );
  protected readonly notice = computed(() =>
    mapNotice(mapKind(this.map().points), this.map().route),
  );
  /** Destino cuyo capítulo se está leyendo. */
  protected readonly active = computed(() => {
    const chapter = this.reading.active();
    if (!chapter) return null;
    const entry = Object.entries(this.chapters()).find(([, ref]) => ref.id === chapter);
    return entry?.[0] ?? null;
  });
}
