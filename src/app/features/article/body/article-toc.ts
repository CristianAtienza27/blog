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
import type { ContentBlock } from '../../../content/models';
import { ChapterReading } from './chapter-reading';

export interface TocEntry {
  id: string;
  /** «01», «02»… (o null si el capítulo no está numerado). */
  number: string | null;
  title: string;
}

/** Capítulos del artículo, en orden, para el índice. */
export function tocEntries(blocks: readonly ContentBlock[]): TocEntry[] {
  return blocks.flatMap((block) =>
    block.type === 'chapter'
      ? [
          {
            id: block.id,
            number: block.number === undefined ? null : String(block.number).padStart(2, '0'),
            title: block.title,
          },
        ]
      : [],
  );
}

/**
 * Piezas que pueden entrar en el margen izquierdo (fotos, composiciones, galerías y mapas):
 * mientras una cruza la franja del índice, el índice se aparta.
 */
const RAIL_PIECES = [
  'app-figure-block .fig__photo',
  'app-figure-block .fig__caption',
  'app-figures-block .figs__item',
  'app-gallery-block .gallery__head',
  'app-gallery-block .gallery__photo',
  'app-gallery-block .gallery__caption',
  'app-map-block .map',
  'app-map-block .mapblock__caption',
].join(', ');

/**
 * Índice del artículo («En este artículo»): capítulos numerados con enlace a su ancla y el
 * capítulo que se lee destacado (aria-current). No detecta nada por su cuenta: lee
 * ChapterReading, el mismo seguimiento que usan el mapa y el itinerario.
 * - Escritorio (lg): en el margen izquierdo, fijo bajo el header mientras dura el cuerpo. Se
 *   aparta cuando una foto, galería o mapa ocupa su franja.
 * - Móvil y tableta: desplegable nativo (<details>) antes del cuerpo; se cierra al elegir.
 */
@Component({
  selector: 'app-article-toc',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toc-inline article-grid">
      <details #details class="toc-inline__details">
        <summary class="toc-inline__summary">
          <span class="t-meta">En este artículo</span>
          <span class="toc-inline__count t-meta"
            ><span class="visually-hidden">: </span>{{ count() }}</span
          >
        </summary>
        <nav class="toc-inline__nav" aria-label="En este artículo">
          <ol class="toc__list">
            @for (entry of entries(); track entry.id) {
              <li class="toc__item">
                <a
                  class="toc__link"
                  [href]="basePath() + '#' + entry.id"
                  [attr.aria-current]="entry.id === reading.active() ? 'true' : null"
                  (click)="closeInline()"
                >
                  @if (entry.number) {
                    <span class="toc__number" aria-hidden="true">{{ entry.number }}</span>
                  }
                  <span class="toc__title">{{ entry.title }}</span>
                </a>
              </li>
            }
          </ol>
        </nav>
      </details>
    </div>

    <div class="toc-rail article-grid">
      <nav #rail class="toc-rail__nav" aria-label="En este artículo" [class.is-covered]="covered()">
        <p class="toc-rail__label t-meta" aria-hidden="true">En este artículo</p>
        <ol class="toc__list">
          @for (entry of entries(); track entry.id) {
            <li class="toc__item">
              <a
                class="toc__link"
                [href]="basePath() + '#' + entry.id"
                [attr.aria-current]="entry.id === reading.active() ? 'true' : null"
              >
                @if (entry.number) {
                  <span class="toc__number" aria-hidden="true">{{ entry.number }}</span>
                }
                <span class="toc__title">{{ entry.title }}</span>
              </a>
            </li>
          }
        </ol>
      </nav>
    </div>
  `,
  styleUrl: './article-toc.scss',
})
export class ArticleToc {
  readonly entries = input.required<readonly TocEntry[]>();
  /** Ruta del artículo: los enlaces son anclas de esta página. */
  readonly basePath = input.required<string>();

  protected readonly reading = inject(ChapterReading);
  protected readonly count = computed(() => `${this.entries().length} capítulos`);
  /** Una pieza ancha cruza ahora la franja del índice lateral. */
  protected readonly covered = signal(false);

  private readonly details = viewChild.required<ElementRef<HTMLDetailsElement>>('details');
  private readonly rail = viewChild.required<ElementRef<HTMLElement>>('rail');

  constructor() {
    const destroyRef = inject(DestroyRef);
    // Solo en el navegador: comprueba (por fotograma, al desplazarse) si una pieza ancha
    // se cruza con el índice lateral cuando este se ve (escritorio).
    afterNextRender(() => {
      const nav = this.rail().nativeElement;
      const view = nav.ownerDocument.defaultView;
      const page = nav.closest('app-article-body');
      if (!view || !page) return;
      let frame = 0;
      const check = () => {
        frame = 0;
        if (nav.offsetParent === null) return;
        const box = nav.getBoundingClientRect();
        const hit = Array.from(page.querySelectorAll(RAIL_PIECES)).some((piece) => {
          const r = piece.getBoundingClientRect();
          return (
            r.left < box.right && r.right > box.left && r.top < box.bottom && r.bottom > box.top
          );
        });
        this.covered.set(hit);
      };
      const schedule = () => {
        if (!frame) frame = view.requestAnimationFrame(check);
      };
      view.addEventListener('scroll', schedule, { passive: true });
      view.addEventListener('resize', schedule, { passive: true });
      schedule();
      destroyRef.onDestroy(() => {
        view.removeEventListener('scroll', schedule);
        view.removeEventListener('resize', schedule);
        if (frame) view.cancelAnimationFrame(frame);
      });
    });
  }

  /** Tras elegir un capítulo en el desplegable, se cierra (el enlace hace el resto). */
  protected closeInline(): void {
    this.details().nativeElement.open = false;
  }
}
