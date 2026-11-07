import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Article } from '../../../content/models';
import { bodyItems } from './body-items';
import { ChapterHeading } from './chapter-heading';
import { DividerBlock } from './divider-block';
import { FigureBlock } from './figure-block';
import { FiguresBlock } from './figures-block';
import { ProseBlock } from './prose-block';
import { QuoteBlock } from './quote-block';
import { SpecsBlock } from './specs-block';

/**
 * Cuerpo del artículo sobre la retícula editorial: el texto en la columna de lectura, los
 * números de capítulo en el margen izquierdo y citas y fichas más anchas (hasta el margen
 * derecho). El ritmo vertical lo marca el tipo de bloque.
 */
@Component({
  selector: 'app-article-body',
  imports: [
    ChapterHeading,
    DividerBlock,
    FigureBlock,
    FiguresBlock,
    ProseBlock,
    QuoteBlock,
    SpecsBlock,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
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
  `,
  styleUrl: './article-body.scss',
})
export class ArticleBody {
  readonly article = input.required<Article>();

  protected readonly items = computed(() => bodyItems(this.article()));
}
