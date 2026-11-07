import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { ArticleSummary } from '../../../content/models';
import { Media } from '../../../shared/ui/media/media';
import { articleTeaser } from './closing';

/**
 * Anterior y siguiente en la sección, por orden de publicación. Dos mitades separadas por un
 * filete; si falta una, no se inventa: la otra ocupa su lado. Cada mitad es un único enlace.
 */
@Component({
  selector: 'app-article-navigation',
  imports: [Media, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (previousItem() || nextItem()) {
      <nav class="pager article-grid" [attr.aria-label]="'Más artículos de ' + sectionLabel()">
        @for (side of sides(); track side.rel) {
          <article class="pager__item" [attr.data-side]="side.rel">
            @if (side.item.cover; as cover) {
              <div class="pager__photo">
                <app-media
                  [image]="cover"
                  fill
                  flush
                  cropped
                  [sizes]="'(width < 640px) 28vw, 160px'"
                  [showCredit]="false"
                  [alt]="''"
                />
              </div>
            }
            <div class="pager__text">
              <p class="pager__dir t-meta">
                @if (side.rel === 'prev') {
                  <span aria-hidden="true">← </span>Anterior
                } @else {
                  Siguiente<span aria-hidden="true"> →</span>
                }
                <span class="pager__section"> · {{ side.item.section }}</span>
              </p>
              <a class="pager__link" [routerLink]="side.item.href" [attr.rel]="side.rel">
                <span class="visually-hidden"
                  >{{ side.rel === 'prev' ? 'Anterior' : 'Siguiente' }}:
                </span>
                {{ side.item.title }}
              </a>
            </div>
          </article>
        }
      </nav>
    }
  `,
  styleUrl: './article-navigation.scss',
})
export class ArticleNavigation {
  readonly previous = input<ArticleSummary | null>(null);
  readonly next = input<ArticleSummary | null>(null);

  protected readonly previousItem = computed(() => {
    const article = this.previous();
    return article ? articleTeaser(article) : null;
  });
  protected readonly nextItem = computed(() => {
    const article = this.next();
    return article ? articleTeaser(article) : null;
  });
  protected readonly sides = computed(() => {
    const previous = this.previousItem();
    const next = this.nextItem();
    return [
      ...(previous ? [{ rel: 'prev' as const, item: previous }] : []),
      ...(next ? [{ rel: 'next' as const, item: next }] : []),
    ];
  });
  protected readonly sectionLabel = computed(
    () => (this.previousItem() ?? this.nextItem())?.section ?? '',
  );
}
