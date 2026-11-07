import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type { Gallery } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';
import { Media } from '../../../shared/ui/media/media';
import { FigureCaption } from './figure-caption';
import { galleryPlan } from './gallery-layout';
import { GalleryViewer } from './gallery-viewer';

/**
 * Galería del relato.
 * - Escritorio (md): composición asimétrica «2 + 1» que se repite (pareja a la misma altura y
 *   una foto grande desplazada hacia un borde, con su pie en el margen opuesto). Fotos sin recortar.
 * - Móvil y tableta: carrusel nativo (scroll-snap, sin JavaScript para desplazarse) con
 *   diapositivas 3:2 e indicador «1 / 3». Las flechas del teclado pasan de una foto a otra.
 * Cada foto abre el visor, que se descarga solo la primera vez que se usa (@defer). Al cerrarlo,
 * el foco vuelve a la foto que lo abrió.
 */
@Component({
  selector: 'app-gallery-block',
  imports: [FigureCaption, GalleryViewer, Media, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Delegado: las teclas llegan desde los botones de cada foto.
  host: { '(keydown)': 'onKeydown($event)' },
  template: `
    @let p = plan();
    <section class="gallery" [attr.aria-label]="'Galería: ' + p.title">
      <header class="gallery__head">
        @if (level() === 2) {
          <h2 class="gallery__title">{{ p.title }}</h2>
        } @else {
          <h3 class="gallery__title">{{ p.title }}</h3>
        }
        <p class="gallery__count t-meta">{{ p.count }}</p>
        @if (p.description; as d) {
          <p class="gallery__description">{{ d }}</p>
        }
      </header>

      <div #track class="gallery__track">
        @for (group of p.groups; track $index) {
          <div
            class="gallery__group"
            [attr.data-kind]="group.kind"
            [attr.data-align]="group.align"
            [style.--pair-columns]="group.columns"
          >
            @for (item of group.items; track item.position) {
              <figure class="gallery__item" [attr.data-position]="item.position">
                <div class="gallery__photo" appReveal="mask" [style.--ratio]="item.ratio">
                  <app-media
                    [image]="item.media"
                    fill
                    flush
                    cropped
                    [sizes]="item.sizes"
                    [showCredit]="false"
                  />
                  <button
                    type="button"
                    class="gallery__open"
                    aria-haspopup="dialog"
                    [attr.aria-label]="item.label"
                    (click)="open(item.position, $event)"
                  ></button>
                </div>
                <figcaption class="gallery__caption">
                  <app-figure-caption
                    [media]="item.media"
                    [text]="item.caption"
                    [location]="item.location"
                    [index]="item.number"
                  />
                </figcaption>
              </figure>
            }
          </div>
        }
      </div>
      <p class="gallery__position t-meta" aria-hidden="true">
        {{ current() + 1 }} / {{ p.items.length }}
      </p>

      <!-- El visor se descarga la primera vez que se abre una foto y después se queda. -->
      @defer (when openAt() !== null) {
        <app-gallery-viewer
          [title]="p.title"
          [items]="p.items"
          [(index)]="openAt"
          (closed)="restoreFocus()"
        />
      }
    </section>
  `,
  styleUrl: './gallery-block.scss',
})
export class GalleryBlock {
  readonly gallery = input.required<Gallery>();
  /** Nivel del título: h2 fuera de un capítulo, h3 dentro. */
  readonly level = input<2 | 3>(2);

  protected readonly plan = computed(() => galleryPlan(this.gallery()));
  /** Foto visible en el carrusel (móvil). */
  protected readonly current = signal(0);
  /** Foto abierta en el visor (null: cerrado). */
  protected readonly openAt = signal<number | null>(null);

  private readonly track = viewChild.required<ElementRef<HTMLElement>>('track');
  private readonly destroyRef = inject(DestroyRef);
  private opener: HTMLElement | null = null;

  constructor() {
    // Indicador del carrusel: la diapositiva más visible dentro de la pista.
    afterNextRender(() => {
      const track = this.track().nativeElement;
      if (typeof IntersectionObserver === 'undefined') return;
      const ratios = new Map<number, number>();
      const observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            const position = Number((entry.target as HTMLElement).dataset['position']);
            ratios.set(position, entry.intersectionRatio);
          }
          let best = this.current();
          let bestRatio = -1;
          for (const [position, ratio] of ratios) {
            if (ratio > bestRatio + 0.01) {
              best = position;
              bestRatio = ratio;
            }
          }
          this.current.set(best);
        },
        { root: track, threshold: [0, 0.25, 0.5, 0.75, 1] },
      );
      track.querySelectorAll('.gallery__item').forEach((item) => observer.observe(item));
      this.destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  protected open(position: number, event: Event): void {
    this.opener = event.currentTarget as HTMLElement;
    this.openAt.set(position);
  }

  /** Al cerrar el visor, el foco vuelve a la foto que lo abrió, sin mover la página. */
  protected restoreFocus(): void {
    this.opener?.focus({ preventScroll: true });
  }

  /** Flechas, Inicio y Fin: de una foto a otra (el carrusel se desplaza hasta ella). */
  protected onKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    if (!target.classList.contains('gallery__open') || event.altKey || event.ctrlKey) return;
    const buttons = Array.from(
      this.track().nativeElement.querySelectorAll<HTMLElement>('.gallery__open'),
    );
    const at = buttons.indexOf(target);
    const next = {
      ArrowRight: at + 1,
      ArrowLeft: at - 1,
      Home: 0,
      End: buttons.length - 1,
    }[event.key];
    if (next === undefined || next < 0 || next >= buttons.length) return;
    event.preventDefault();
    const button = buttons[next];
    button.focus({ preventScroll: true });
    // En el carrusel, la foto se engancha al margen (scroll-padding), como al deslizar.
    button.closest('.gallery__item')?.scrollIntoView({ block: 'nearest', inline: 'start' });
  }
}
