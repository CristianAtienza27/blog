import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
} from '@angular/core';
import type { Article } from '../../../content/models';
import { articleUrl } from '../../../editorial/article-meta';
import { ArticleToc, tocEntries } from './article-toc';
import { bodyItems } from './body-items';
import { ChapterReading } from './chapter-reading';
import { ChapterHeading } from './chapter-heading';
import { DividerBlock } from './divider-block';
import { FigureBlock } from './figure-block';
import { FiguresBlock } from './figures-block';
import { GalleryBlock } from './gallery-block';
import { MapBlock } from './map-block';
import { ProseBlock } from './prose-block';
import { QuoteBlock } from './quote-block';
import { ReadingProgress } from './reading-progress';
import { SpecsBlock } from './specs-block';

/**
 * Cuerpo del artículo sobre la retícula editorial: el texto en la columna de lectura, los
 * números de capítulo en el margen izquierdo y citas y fichas más anchas (hasta el margen
 * derecho). El ritmo vertical lo marca el tipo de bloque.
 * Arranca el seguimiento del capítulo que se lee (ChapterReading), que usan los mapas y el
 * índice, y lleva la barra de progreso de lectura.
 */
@Component({
  selector: 'app-article-body',
  imports: [
    ArticleToc,
    ChapterHeading,
    DividerBlock,
    FigureBlock,
    FiguresBlock,
    GalleryBlock,
    MapBlock,
    ProseBlock,
    QuoteBlock,
    ReadingProgress,
    SpecsBlock,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Índice: con dos capítulos o más (desplegable en móvil; en el margen en escritorio). -->
    @if (toc().length > 1) {
      <app-article-toc [entries]="toc()" [basePath]="basePath()" />
    }
    <div class="body article-grid">
      @for (item of items(); track $index) {
        @switch (item.kind) {
          @case ('prose') {
            <app-prose-block [html]="item.html" />
          }
          @case ('chapter') {
            <app-chapter-heading [chapter]="item.block" />
          }
          @case ('quote') {
            <app-quote-block class="body__wide" [quote]="item.block" />
          }
          @case ('divider') {
            <app-divider-block [variant]="item.block.variant" />
          }
          @case ('figure') {
            <app-figure-block [figure]="item.block" />
          }
          @case ('figures') {
            <app-figures-block [figures]="item.block" />
          }
          @case ('map') {
            <app-map-block
              [map]="item.block"
              [chapters]="item.chapters"
              [basePath]="item.basePath"
              [level]="item.level"
            />
          }
          @case ('gallery') {
            <app-gallery-block [gallery]="item.gallery" [level]="item.level" />
          }
          @case ('specs') {
            <app-specs-block
              class="body__wide"
              [specs]="item.block"
              [level]="item.level"
              [headingId]="item.headingId"
            />
          }
        }
      }
    </div>
    <app-reading-progress />
  `,
  styleUrl: './article-body.scss',
})
export class ArticleBody {
  readonly article = input.required<Article>();

  protected readonly items = computed(() => bodyItems(this.article()));
  protected readonly toc = computed(() => tocEntries(this.article().blocks));
  protected readonly basePath = computed(() => articleUrl(this.article()));

  constructor() {
    // Solo en el navegador y con el cuerpo ya pintado (en SSR no hay capítulo activo).
    const reading = inject(ChapterReading);
    const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    let stop: (() => void) | null = null;
    afterNextRender(() => (stop = reading.start(host)));
    inject(DestroyRef).onDestroy(() => stop?.());
  }
}
