import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  booleanAttribute,
  computed,
  inject,
  input,
} from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import type { Media as MediaAsset } from '../../../content/models';
import { MEDIA_BASE_URL, mediaSrcset } from '../../../core/media/media-delivery';
import { creditLine } from './media-credit';

export type MediaTone = 'dawn' | 'sea' | 'desert' | 'forest' | 'road' | 'night';

/**
 * Figura multimedia.
 *
 * Con `image` (un registro de Media) genera un <picture> optimizado:
 * - AVIF en un <source> y WebP en el <img> de NgOptimizedImage (carga diferida,
 *   srcset con los anchos generados, vista previa difuminada mientras carga).
 * - Recorte según el punto focal de la foto y crédito con autor, procedencia y licencia.
 * - `priority` (solo la imagen principal de la página): el `priority` de NgOptimizedImage
 *   (preload en el servidor, carga inmediata, fetchpriority alto) con un único formato,
 *   WebP. Con un <source> AVIF el preload del framework haría descargar dos formatos; en
 *   la imagen principal medida, WebP pesa lo mismo que AVIF (680 frente a 690 kB).
 *
 * También acepta contenido proyectado (<img>, <video>) o un fondo provisional (`tone`).
 *
 * <app-media [image]="foto" sizes="(width >= 900px) 50vw, 100vw" />
 */
@Component({
  selector: 'app-media',
  imports: [NgOptimizedImage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Sin encapsulación: necesita dar estilo al contenido proyectado (img, video).
  encapsulation: ViewEncapsulation.None,
  host: { '[attr.data-fill]': 'fill() || null' },
  template: `
    <figure class="media">
      <div class="media__frame" [style.aspect-ratio]="fill() ? null : aspectRatio()">
        @if (image(); as img) {
          <picture class="media__picture">
            @if (priority()) {
              <!-- Imagen principal: un solo formato, con el preload y la prioridad del framework;
                   sin vista previa difuminada (llega antes y el desenfoque a pantalla completa es caro). -->
              <img
                [ngSrc]="img.path"
                [ngSrcset]="widthDescriptors()"
                [loaderParams]="loaderParams()"
                fill
                priority
                [sizes]="sizes()"
                [alt]="alt() ?? img.alt"
                [style.object-position]="objectPosition()"
              />
            } @else {
              <source type="image/avif" [attr.srcset]="avifSrcset()" [attr.sizes]="sourceSizes()" />
              <img
                [ngSrc]="img.path"
                [ngSrcset]="widthDescriptors()"
                [loaderParams]="loaderParams()"
                fill
                [sizes]="sizes()"
                [alt]="alt() ?? img.alt"
                loading="lazy"
                [placeholder]="img.placeholder"
                [style.object-position]="objectPosition()"
              />
            }
          </picture>
        }
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
      @if (caption() || meta() || credit() || imageCredit()) {
        <figcaption class="media__caption">
          @if (caption(); as text) {
            <span class="media__text">{{ text }}</span>
          }
          @if (meta()) {
            <span class="t-meta">{{ meta() }}</span>
          }
          @if (credit()) {
            <span class="media__credit t-meta">{{ credit() }}</span>
          } @else if (imageCredit(); as c) {
            <span class="media__credit t-meta"
              >Foto: {{ c.author }}
              @if (c.source) {
                ·
                @if (c.sourceUrl) {
                  <a [href]="c.sourceUrl" rel="noopener" target="_blank">{{ c.source }}</a>
                } @else {
                  {{ c.source }}
                }
              }
              @if (c.license) {
                ·
                @if (c.licenseUrl) {
                  <a [href]="c.licenseUrl" rel="license noopener" target="_blank">{{
                    c.license
                  }}</a>
                } @else {
                  {{ c.license }}
                }
              }
              @if (c.adapted) {
                · adaptada
              }
            </span>
          }
        </figcaption>
      }
    </figure>
  `,
  styleUrl: './media.scss',
})
export class Media {
  private readonly base = inject(MEDIA_BASE_URL);

  /** Fotografía optimizada (registro de Media). */
  readonly image = input<MediaAsset>();
  /** Ancho con el que se mostrará (atributo sizes). */
  readonly sizes = input('100vw');
  /** Solo para la imagen principal de la página (LCP). */
  readonly priority = input(false, { transform: booleanAttribute });
  /** Sustituye el texto alternativo de la imagen. */
  readonly alt = input<string>();
  /** Muestra autor y licencia de la imagen. */
  readonly showCredit = input(true, { transform: booleanAttribute });

  /** Relación de aspecto CSS ('16/9', '4/5'…). Por defecto, la de la imagen o 3/2. */
  readonly ratio = input<string>();
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

  protected readonly aspectRatio = computed(() => {
    const image = this.image();
    return this.ratio() ?? (image ? `${image.width} / ${image.height}` : '3/2');
  });
  protected readonly widthDescriptors = computed(() =>
    (this.image()?.widths ?? []).map((w) => `${w}w`).join(', '),
  );
  protected readonly loaderParams = computed(() => ({ widths: this.image()?.widths ?? [] }));
  protected readonly avifSrcset = computed(() => {
    const image = this.image();
    return image ? mediaSrcset(this.base, image.path, image.widths, 'avif') : null;
  });
  /** Mismo valor que NgOptimizedImage pone en el <img> en carga diferida («auto» delante). */
  protected readonly sourceSizes = computed(() => `auto, ${this.sizes()}`);
  protected readonly objectPosition = computed(() => {
    const focal = this.image()?.focal;
    return focal ? `${Math.round(focal.x * 100)}% ${Math.round(focal.y * 100)}%` : null;
  });
  protected readonly imageCredit = computed(() => {
    const image = this.image();
    return image && this.showCredit() ? creditLine(image.credit) : null;
  });
}
