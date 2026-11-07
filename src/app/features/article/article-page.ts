import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  inject,
  input,
} from '@angular/core';
import { ViewportScroller } from '@angular/common';
import { NotFound } from '../not-found/not-found';
import type { ArticlePageData } from './article-presentation';
import { ArticleBody } from './body/article-body';
import { ArticleCredits } from './closing/article-credits';
import { ArticleNavigation } from './closing/article-navigation';
import { ArticleTags } from './closing/article-tags';
import type { ArticleClosing } from './closing/closing.resolver';
import { RelatedArticles } from './closing/related-articles';
import { ArticleHeader } from './header/article-header';

/** Aire entre el header del sitio y un capítulo al llegar a él por su ancla. */
const ANCHOR_GAP = 24;

/**
 * Página de un artículo (/<sección>/<slug>): cabecera según su formato, el cuerpo y el cierre
 * (créditos y temas dentro del artículo; después, relacionados y anterior/siguiente).
 * Sin artículo, 404.
 *
 * Anclas: el router se desplaza con ViewportScroller, que no tiene en cuenta scroll-margin.
 * Mientras se muestra un artículo, su desplazamiento deja libre la altura del header
 * (también al cargar la página con #capitulo); al salir se restablece.
 */
@Component({
  selector: 'app-article-page',
  imports: [
    ArticleBody,
    ArticleCredits,
    ArticleHeader,
    ArticleNavigation,
    ArticleTags,
    NotFound,
    RelatedArticles,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (page(); as p) {
      <article
        class="article"
        [attr.data-section]="p.header.section.id"
        aria-labelledby="article-title"
      >
        <app-article-header [data]="p.header" />
        <app-article-body [article]="p.article" />
        <footer class="article__footer article-grid">
          <div class="article__colophon">
            <app-article-credits [article]="p.article" />
            <app-article-tags [tags]="p.article.tags" [category]="p.article.category" />
          </div>
        </footer>
      </article>
      @if (closing(); as c) {
        <div class="article__continue" [attr.data-section]="p.header.section.id">
          @if (c.related.length) {
            <app-related-articles [articles]="c.related" />
          }
          <app-article-navigation [previous]="c.previous" [next]="c.next" />
        </div>
      }
    } @else {
      <app-not-found />
    }
  `,
  styles: `
    :host {
      display: block;
    }

    /* Colofón en la columna de lectura (en escritorio, hasta el margen derecho). */
    .article__footer {
      padding-block-end: var(--section-m);
    }

    .article__colophon {
      display: grid;
      grid-column: text;
      gap: var(--space-6);

      @media (width >= 1200px) {
        grid-column: text-start / aside-end;
      }
    }

    .article__continue {
      display: grid;
      gap: var(--section-m);
      padding-block-end: var(--section-m);
    }
  `,
})
export class ArticlePage {
  /** Del resolver de la ruta (withComponentInputBinding). */
  readonly page = input<ArticlePageData | null>(null);
  /** Relacionados y vecinos de la sección (resolver propio). */
  readonly closing = input<ArticleClosing | null>(null);

  constructor() {
    const scroller = inject(ViewportScroller);
    const document = inject(DOCUMENT);
    scroller.setOffset(() => {
      const header = document.querySelector<HTMLElement>('app-header');
      return [0, (header?.offsetHeight ?? 0) + ANCHOR_GAP];
    });
    inject(DestroyRef).onDestroy(() => scroller.setOffset([0, 0]));
  }
}
