import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  booleanAttribute,
  input,
} from '@angular/core';

export type MediaTone = 'dawn' | 'sea' | 'desert' | 'forest' | 'road' | 'night';

/**
 * Figura multimedia: proyecta una <img>, <picture> o <video> y la recorta al ratio indicado.
 * Sin contenido y con `tone`, muestra un fondo provisional con grano de película.
 *
 * <app-media ratio="21/9" caption="…" meta="f/2.8 · 1/500 · ISO 100" credit="Foto: …">
 *   <img src="…" alt="…" />
 * </app-media>
 */
@Component({
  selector: 'app-media',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Sin encapsulación: necesita dar estilo al contenido proyectado (img, video).
  encapsulation: ViewEncapsulation.None,
  host: { '[attr.data-fill]': 'fill() || null' },
  template: `
    <figure class="media">
      <div class="media__frame" [style.aspect-ratio]="fill() ? null : ratio()">
        <ng-content />
        @if (tone(); as currentTone) {
          <div
            class="media__placeholder"
            role="img"
            [attr.data-tone]="currentTone"
            [attr.aria-label]="label()"
          ></div>
        }
      </div>
      @if (caption() || meta() || credit()) {
        <figcaption class="media__caption">
          @if (caption()) {
            <span class="media__text">{{ caption() }}</span>
          }
          @if (meta()) {
            <span class="t-meta">{{ meta() }}</span>
          }
          @if (credit()) {
            <span class="media__credit t-meta">{{ credit() }}</span>
          }
        </figcaption>
      }
    </figure>
  `,
  styleUrl: './media.scss',
})
export class Media {
  /** Relación de aspecto CSS: '16/9', '3/2', '4/5', '21/9', '1/1' o 'auto'. */
  readonly ratio = input('3/2');
  /** Ignora el ratio y ocupa la altura que le dé su contenedor (portadas a pantalla completa). */
  readonly fill = input(false, { transform: booleanAttribute });
  readonly caption = input<string>();
  /** Metadatos de campo en mono: lugar, coordenadas o datos de cámara. */
  readonly meta = input<string>();
  readonly credit = input<string>();
  /** Fondo provisional mientras no hay fotografía. */
  readonly tone = input<MediaTone>();
  /** Texto alternativo del fondo provisional. */
  readonly label = input('Imagen provisional');
}
