import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/directives/reveal';
import { Link } from '../../shared/ui/link/link';
import { NotFound } from '../not-found/not-found';
import type { ArticlePageData } from './article-presentation';
import { ARTICLE_BODY_READY } from './article-seo';
import { ArticleHeader } from './header/article-header';

/**
 * Página de un artículo (/<sección>/<slug>): cabecera según su formato y, debajo, el cuerpo.
 * Mientras el cuerpo no se muestra (fase 5.2), un resumen y el aviso de texto en preparación.
 * Sin artículo, 404.
 */
@Component({
  selector: 'app-article-page',
  imports: [ArticleHeader, Link, NotFound, Reveal, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (page(); as p) {
      <article
        class="article"
        [attr.data-section]="p.header.section.id"
        aria-labelledby="article-title"
      >
        <app-article-header [data]="p.header" />
        @if (!bodyReady) {
          <section class="article__pending article-grid" aria-label="Resumen del artículo">
            <div class="article__summary" appReveal>
              <p class="t-body">{{ p.article.excerpt }}</p>
              <p class="t-meta">Texto completo en preparación</p>
              <a app-link variant="arrow" [routerLink]="p.header.section.path">
                Volver a {{ p.header.section.label }}
              </a>
            </div>
          </section>
        }
      </article>
    } @else {
      <app-not-found />
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .article__pending {
      padding-block: var(--space-6) var(--section-m);
    }

    .article__summary {
      display: grid;
      gap: var(--space-5);
      justify-items: start;
      max-inline-size: var(--width-measure);
      padding-block-start: var(--space-6);
      border-block-start: var(--border-width) solid var(--color-line);
    }
  `,
})
export class ArticlePage {
  /** Del resolver de la ruta (withComponentInputBinding). */
  readonly page = input<ArticlePageData | null>(null);

  protected readonly bodyReady = ARTICLE_BODY_READY;
}
