import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { MapPoint } from '../../../content/models';
import { MAP_WIDTH, mapLayout } from './map-layout';

/**
 * Cartografía propia en SVG, sin teselas ni servicios externos: retícula de coordenadas,
 * puntos numerados y, si es una ruta, la línea que los une en orden (recta: no sigue
 * carreteras). El SVG lleva título y descripción para lectores de pantalla; los números y
 * las etiquetas van en HTML encima, con un tamaño legible a cualquier ancho.
 * El punto activo (el capítulo que se lee) se distingue por tamaño, anillo y peso, no solo
 * por el color.
 */
@Component({
  selector: 'app-route-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-kind]': 'layout().kind' },
  template: `
    @let m = layout();
    <div
      class="map"
      [style.aspect-ratio]="width + ' / ' + m.height"
      [style.--ratio]="width / m.height"
    >
      <svg class="map__svg" [attr.viewBox]="'0 0 ' + width + ' ' + m.height" role="img">
        <title>{{ title() }}</title>
        <desc>{{ m.description }}</desc>
        @for (line of m.grid; track $index) {
          @if (line.axis === 'lat') {
            <line
              class="map__grid"
              x1="0"
              [attr.x2]="width"
              [attr.y1]="line.at * m.height"
              [attr.y2]="line.at * m.height"
            />
          } @else {
            <line
              class="map__grid"
              [attr.x1]="line.at * width"
              [attr.x2]="line.at * width"
              y1="0"
              [attr.y2]="m.height"
            />
          }
        }
        @if (m.path; as d) {
          <path class="map__route" [attr.d]="d" />
        }
        @for (marker of m.markers; track $index) {
          <circle
            class="map__dot"
            [class.is-active]="isActive(marker.destination)"
            [attr.cx]="marker.x * width"
            [attr.cy]="marker.y * m.height"
            r="5"
          />
        }
      </svg>

      <div class="map__overlay" aria-hidden="true">
        @for (line of m.grid; track $index) {
          <span
            class="map__coord t-meta"
            [attr.data-axis]="line.axis"
            [attr.data-anchor]="line.anchor"
            [attr.data-row]="line.row"
            [attr.data-wide-anchor]="line.wide.anchor"
            [attr.data-wide-row]="line.wide.row"
            [style.--at.%]="line.at * 100"
            >{{ line.label }}</span
          >
        }
        @for (marker of m.markers; track $index) {
          <span
            class="map__marker"
            [class.is-active]="isActive(marker.destination)"
            [attr.data-side]="marker.side"
            [style.--room]="marker.room * 100"
            [style.left.%]="marker.x * 100"
            [style.top.%]="marker.y * 100"
          >
            <span class="map__badge">{{ marker.number }}</span>
            <span class="map__label">{{ marker.label }}</span>
          </span>
        }
        <span
          class="map__north t-meta"
          [attr.data-anchor]="m.north.anchor"
          [attr.data-row]="m.north.row"
          [attr.data-wide-anchor]="m.northWide.anchor"
          [attr.data-wide-row]="m.northWide.row"
          >N ↑</span
        >
        @if (m.kind === 'schematic') {
          <span class="map__tag t-meta">Esquema · posiciones no geográficas</span>
        }
      </div>
    </div>
  `,
  styleUrl: './route-map.scss',
})
export class RouteMap {
  readonly points = input.required<readonly MapPoint[]>();
  readonly route = input(false);
  /** Nombre del mapa (título del SVG). */
  readonly title = input.required<string>();
  /** Destino destacado (el del capítulo que se lee). */
  readonly active = input<string | null>(null);

  protected readonly width = MAP_WIDTH;
  protected readonly layout = computed(() => mapLayout(this.points(), this.route()));

  protected isActive(destination: string | null): boolean {
    return destination !== null && destination === this.active();
  }
}
