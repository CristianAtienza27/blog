import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import type { ArticleSummary } from '../../../content/models';
import { Media } from '../../../shared/ui/media/media';
import { cropFactor } from '../body/figure-layout';
import { articleTeaser } from './closing';

/** Ancho de la portada pedido para un marco 3:2 (más si la foto es panorámica y se recorta). */
const coverSizes = (base: string, ratio: number) => {
  const f = cropFactor(ratio, 1.5);
  return f === 1 ? base : base.replace(/(\d+)(vw|px)/g, (_, n, u) => `${Math.ceil(n * f)}${u}`);
};

/**
 * «Seguir leyendo»: el primero de los relacionados como pieza principal, con su portada
 * grande; los demás, al lado, más pequeños. Sin tarjetas: la jerarquía la dan el tamaño de la
 * foto y la tipografía. Cada pieza es un único enlace (el título, extendido a toda la pieza).
 */
@Component({
  selector: 'app-related-articles',
  imports: [Media, NgTemplateOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="related article-grid" aria-label="Seguir leyendo">
      <h2 class="related__heading">Seguir leyendo</h2>
      @if (lead(); as item) {
        <ng-container *ngTemplateOutlet="piece; context: { $implicit: item, lead: true }" />
      }
      @if (more().length) {
        <!-- Las demás, juntas en su columna: no se estiran a la altura de la principal. -->
        <div class="related__more">
          @for (item of more(); track item.href) {
            <ng-container *ngTemplateOutlet="piece; context: { $implicit: item, lead: false }" />
          }
        </div>
      }
    </section>

    <ng-template #piece let-item let-lead="lead">
      <article class="related__item" [class.related__item--lead]="lead">
        @if (item.cover; as cover) {
          <div class="related__photo">
            <app-media
              [image]="cover"
              fill
              flush
              cropped
              [sizes]="lead ? item.leadSizes : item.thumbSizes"
              [showCredit]="false"
              [alt]="''"
            />
          </div>
        }
        <div class="related__text">
          <p class="related__meta t-meta">
            {{ item.section }}
            @if (item.demo) {
              <span class="related__demo">· Demostración</span>
            }
          </p>
          <h3 class="related__title">
            <a class="related__link" [routerLink]="item.href">{{ item.title }}</a>
          </h3>
          @if (lead) {
            <p class="related__dek">{{ item.dek }}</p>
          }
        </div>
      </article>
    </ng-template>
  `,
  styleUrl: './related-articles.scss',
})
export class RelatedArticles {
  readonly articles = input.required<readonly ArticleSummary[]>();

  protected readonly items = computed(() =>
    this.articles().map((article) => {
      const teaser = articleTeaser(article);
      const ratio = teaser.cover ? teaser.cover.width / teaser.cover.height : 1.5;
      return {
        ...teaser,
        leadSizes: coverSizes('(width < 900px) 100vw, (width < 1200px) 60vw, 800px', ratio),
        thumbSizes: coverSizes('(width < 900px) 34vw, 200px', ratio),
      };
    }),
  );
  protected readonly lead = computed(() => this.items()[0] ?? null);
  protected readonly more = computed(() => this.items().slice(1));
}
