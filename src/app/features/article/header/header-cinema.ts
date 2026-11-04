import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PhotoCredit } from '../../../editorial/photo-credit/photo-credit';
import { SplitText } from '../../../motion/directives/split-text';
import { Media } from '../../../shared/ui/media/media';
import type { ArticleHeaderData } from '../article-presentation';
import { ArticleFacts } from './article-facts';
import { ArticleKicker } from './article-kicker';

/**
 * Cabecera «cine»: fotografía a sangre con el titular encima, en modo oscuro.
 * Escritorio: fotograma a 88svh con todo el texto sobre la foto.
 * Móvil: foto 4:5 con antetítulo y titular sobre ella; entradilla, datos y crédito debajo.
 */
@Component({
  selector: 'app-header-cinema',
  imports: [ArticleFacts, ArticleKicker, Media, PhotoCredit, SplitText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './header-cinema.html',
  styleUrl: './header-cinema.scss',
})
export class HeaderCinema {
  readonly data = input.required<ArticleHeaderData>();
}
