import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PhotoCredit } from '../../../editorial/photo-credit/photo-credit';
import { SplitText } from '../../../motion/directives/split-text';
import { Media } from '../../../shared/ui/media/media';
import type { ArticleHeaderData } from '../article-presentation';
import { ArticleFacts } from './article-facts';
import { ArticleKicker } from './article-kicker';

/**
 * Cabecera breve (notas y tecnología): titular contenido y foto dentro de los márgenes,
 * panorámica si lo es. Sin resolución para todo el ancho, la foto ocupa ocho columnas.
 * Sin foto utilizable (por ejemplo, una retirada), queda solo el texto.
 */
@Component({
  selector: 'app-header-compact',
  imports: [ArticleFacts, ArticleKicker, Media, PhotoCredit, SplitText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let d = data();
    <header class="compact article-grid article-grid--content">
      <app-article-kicker class="compact__kicker" [section]="d.section" [format]="d.format" />
      <h1 id="article-title" class="compact__title" appSplitText>{{ d.title }}</h1>
      <p class="compact__dek t-lead">{{ d.dek }}</p>
      <app-article-facts class="compact__facts" layout="end" [facts]="d.facts" [demo]="d.demo" />
      @if (d.cover; as cover) {
        <div
          class="compact__photo"
          [class.compact__photo--narrow]="cover.narrow"
          [style.--cover-ratio]="cover.ratio"
        >
          <app-media
            [image]="cover.media"
            fill
            flush
            priority
            [sizes]="cover.sizes"
            [showCredit]="false"
          />
        </div>
        <app-photo-credit
          class="compact__credit photo-credit--keep"
          [class.compact__credit--narrow]="cover.narrow"
          [media]="cover.media"
        />
      }
    </header>
  `,
  styleUrl: './header-compact.scss',
})
export class HeaderCompact {
  readonly data = input.required<ArticleHeaderData>();
}
