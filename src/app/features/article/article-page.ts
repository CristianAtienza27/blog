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
import { ArticleHeader } from './header/article-header';

/** Aire entre el header del sitio y un capítulo al llegar a él por su ancla. */
const ANCHOR_GAP = 24;

/**
 * Página de un artículo (/<sección>/<slug>): cabecera según su formato y el cuerpo.
 * Sin artículo, 404.
 *
 * Anclas: el router se desplaza con ViewportScroller, que no tiene en cuenta scroll-margin.
 * Mientras se muestra un artículo, su desplazamiento deja libre la altura del header
 * (también al cargar la página con #capitulo); al salir se restablece.
 */
@Component({
  selector: 'app-article-page',
  imports: [ArticleBody, ArticleHeader, NotFound],
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
      </article>
    } @else {
      <app-not-found />
    }
  `,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class ArticlePage {
  /** Del resolver de la ruta (withComponentInputBinding). */
  readonly page = input<ArticlePageData | null>(null);

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
