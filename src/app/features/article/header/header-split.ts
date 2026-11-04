import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PhotoCredit } from '../../../editorial/photo-credit/photo-credit';
import { SplitText } from '../../../motion/directives/split-text';
import { Media } from '../../../shared/ui/media/media';
import type { ArticleHeaderData } from '../article-presentation';
import { ArticleFacts } from './article-facts';
import { ArticleKicker } from './article-kicker';

/**
 * Cabecera en dos columnas asimétricas: texto en las cinco primeras y foto desde la séptima
 * hasta el borde de la pantalla. Móvil: texto y, debajo, la foto a sangre en 4:5.
 * También es la alternativa de cine y plate cuando la foto no da para el ancho completo.
 */
@Component({
  selector: 'app-header-split',
  imports: [ArticleFacts, ArticleKicker, Media, PhotoCredit, SplitText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let d = data();
    <header class="split article-grid">
      <div class="split__copy">
        <app-article-kicker [section]="d.section" [format]="d.format" />
        <h1 id="article-title" class="split__title" appSplitText>{{ d.title }}</h1>
        <p class="t-lead">{{ d.dek }}</p>
        <app-article-facts layout="stack" [facts]="d.facts" [demo]="d.demo" />
      </div>
      @if (d.cover; as cover) {
        <div class="split__photo">
          <app-media
            [image]="cover.media"
            fill
            flush
            priority
            [sizes]="cover.sizes"
            [showCredit]="false"
          />
        </div>
        <app-photo-credit class="split__credit photo-credit--keep" [media]="cover.media" />
      }
    </header>
  `,
  styleUrl: './header-split.scss',
})
export class HeaderSplit {
  readonly data = input.required<ArticleHeaderData>();
}
