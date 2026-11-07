import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  input,
  model,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Media } from '../../../shared/ui/media/media';
import { FigureCaption } from './figure-caption';
import type { GalleryItemPlan } from './gallery-layout';

/** Hueco vertical que dejan la barra y el pie del visor (para el atributo sizes). */
const CHROME_HEIGHT = '11rem';
/** Desplazamiento horizontal mínimo de un gesto para pasar de foto. */
const SWIPE = 48;

/** Ancho al que se verá la foto: el menor entre la pantalla y lo que permite el alto. */
export function viewerSizes(width: number, height: number): string {
  return `min(100vw, calc((100vh - ${CHROME_HEIGHT}) * ${(width / height).toFixed(3)}))`;
}

/**
 * Visor de una galería: <dialog> modal, oscuro en cualquier tema.
 * - La foto, entera y lo más grande posible (se ajusta al hueco libre con su proporción).
 * - Anterior y siguiente (en bucle): botones, flechas del teclado, Inicio/Fin y gesto lateral.
 * - Esc o «Cerrar» lo cierran. El fondo queda inerte y sin scroll mientras está abierto
 *   (diálogo modal nativo y html:has(dialog:modal) en los estilos globales).
 * - Solo se pide la foto que se está viendo, al tamaño del visor.
 */
@Component({
  selector: 'app-gallery-viewer',
  imports: [FigureCaption, Media],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Delegado: las teclas llegan desde cualquier control del diálogo.
  host: { '(keydown)': 'onKeydown($event)' },
  template: `
    <dialog
      #dialog
      class="viewer scheme-dark"
      [attr.aria-label]="'Visor de la galería ' + title()"
      (cancel)="onCancel($event)"
      (close)="onClose()"
    >
      <div class="viewer__bar">
        <p class="viewer__count t-meta">
          <span class="visually-hidden">Fotografía </span>{{ (index() ?? 0) + 1 }}
          <span aria-hidden="true">/</span><span class="visually-hidden"> de</span>
          {{ items().length }}
        </p>
        <p class="viewer__title t-meta" aria-hidden="true">{{ title() }}</p>
        <button type="button" class="viewer__close" autofocus (click)="close()">
          Cerrar <span class="viewer__cross" aria-hidden="true"></span>
        </button>
      </div>

      <!-- Antes que la foto: tras «Cerrar», el tabulador llega a anterior y siguiente. -->
      <button
        type="button"
        class="viewer__nav viewer__nav--prev"
        aria-label="Fotografía anterior"
        (click)="step(-1)"
      >
        <span aria-hidden="true">←</span>
      </button>
      <button
        type="button"
        class="viewer__nav viewer__nav--next"
        aria-label="Fotografía siguiente"
        (click)="step(1)"
      >
        <span aria-hidden="true">→</span>
      </button>
      <!-- La foto se dibuja con el diálogo ya abierto: nunca mide 0 al cargar (si está en caché). -->
      @if (isOpen() ? current() : null; as item) {
        <figure class="viewer__figure">
          <div
            class="viewer__stage"
            (pointerdown)="onPointerDown($event)"
            (pointerup)="onPointerUp($event)"
          >
            @for (shown of [item]; track shown.media.id) {
              <div class="viewer__photo" [style.--ratio]="shown.ratio">
                <app-media
                  [image]="shown.media"
                  fill
                  flush
                  [blur]="false"
                  [sizes]="sizes()"
                  [showCredit]="false"
                />
              </div>
            }
          </div>
          <figcaption class="viewer__caption">
            <app-figure-caption
              [media]="item.media"
              [text]="item.caption"
              [location]="item.location"
            />
          </figcaption>
        </figure>
      }

      <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ announcement() }}</p>
    </dialog>
  `,
  styleUrl: './gallery-viewer.scss',
})
export class GalleryViewer {
  readonly title = input.required<string>();
  readonly items = input.required<readonly GalleryItemPlan[]>();
  /** Foto abierta (null: visor cerrado). */
  readonly index = model<number | null>(null);
  /** El visor se ha cerrado (para devolver el foco a quien lo abrió). */
  readonly closed = output<void>();

  protected readonly current = computed(() => {
    const at = this.index();
    return at === null ? null : (this.items()[at] ?? null);
  });
  protected readonly sizes = computed(() => {
    const item = this.current();
    return item ? viewerSizes(item.media.width, item.media.height) : '100vw';
  });
  /** Se anuncia al pasar de foto (no al abrir: entonces se lee el diálogo). */
  protected readonly announcement = signal('');
  /** El <dialog> está abierto (tras showModal). */
  protected readonly isOpen = signal(false);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private startX: number | null = null;

  constructor() {
    // Abre o cierra el <dialog> según el índice (solo en el navegador: tras el render).
    afterRenderEffect(() => {
      const dialog = this.dialog().nativeElement;
      const open = this.index() !== null;
      if (open && !dialog.open) {
        this.announcement.set('');
        dialog.showModal();
        this.isOpen.set(true);
      } else if (!open && dialog.open) {
        dialog.close();
      }
    });
  }

  close(): void {
    this.index.set(null);
  }

  /** Avanza o retrocede en bucle: nunca hay un botón desactivado que pierda el foco. */
  step(delta: number): void {
    const at = this.index();
    const total = this.items().length;
    if (at === null || total === 0) return;
    const next = (at + delta + total) % total;
    this.go(next);
  }

  protected onCancel(event: Event): void {
    event.preventDefault();
    this.close();
  }

  /** Cierre nativo (o el nuestro): sincroniza el estado y avisa para devolver el foco. */
  protected onClose(): void {
    this.isOpen.set(false);
    if (this.index() !== null) this.index.set(null);
    this.closed.emit();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const total = this.items().length;
    const moves: Record<string, () => void> = {
      ArrowRight: () => this.step(1),
      ArrowLeft: () => this.step(-1),
      Home: () => this.go(0),
      End: () => this.go(total - 1),
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    move();
  }

  protected onPointerDown(event: PointerEvent): void {
    this.startX = event.pointerType === 'mouse' ? null : event.clientX;
  }

  protected onPointerUp(event: PointerEvent): void {
    if (this.startX === null) return;
    const dx = event.clientX - this.startX;
    this.startX = null;
    if (Math.abs(dx) >= SWIPE) this.step(dx < 0 ? 1 : -1);
  }

  private go(position: number): void {
    const item = this.items()[position];
    if (!item || position === this.index()) return;
    this.index.set(position);
    const text = item.caption ? `: ${item.caption}` : '';
    this.announcement.set(`Fotografía ${position + 1} de ${this.items().length}${text}`);
  }
}
