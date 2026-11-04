import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PhotoCredit } from '../../../editorial/photo-credit/photo-credit';
import { SplitText } from '../../../motion/directives/split-text';
import { Media } from '../../../shared/ui/media/media';
import type { ArticleHeaderData } from '../article-presentation';
import { ArticleFacts } from './article-facts';
import { ArticleKicker } from './article-kicker';

/**
 * Cabecera de lámina: titular grande arriba, entradilla y datos en dos columnas y, debajo, la
 * foto a lo ancho (con su proporción si es apaisada o panorámica). Móvil: foto a sangre 3:2.
 */
@Component({
  selector: 'app-header-plate',
  imports: [ArticleFacts, ArticleKicker, Media, PhotoCredit, SplitText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let d = data();
    <header class="plate article-grid article-grid--content">
      <app-article-kicker class="plate__kicker" [section]="d.section" [format]="d.format" />
      <h1 id="article-title" class="plate__title" appSplitText>{{ d.title }}</h1>
      <p class="plate__dek t-lead">{{ d.dek }}</p>
      <app-article-facts class="plate__facts" layout="end" [facts]="d.facts" [demo]="d.demo" />
      @if (d.cover; as cover) {
        <div class="plate__photo" [style.--cover-ratio]="cover.ratio">
          <app-media
            [image]="cover.media"
            fill
            flush
            priority
            [sizes]="cover.sizes"
            [showCredit]="false"
          />
        </div>
        <app-photo-credit class="plate__credit photo-credit--keep" [media]="cover.media" />
      }
    </header>
  `,
  styleUrl: './header-plate.scss',
})
export class HeaderPlate {
  readonly data = input.required<ArticleHeaderData>();
}
