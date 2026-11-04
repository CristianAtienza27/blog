import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { ArticleHeaderData } from '../article-presentation';
import { HeaderCinema } from './header-cinema';
import { HeaderCompact } from './header-compact';
import { HeaderPlate } from './header-plate';
import { HeaderSplit } from './header-split';

/**
 * Cabecera de un artículo. La variante viene resuelta (formato y resolución de la foto);
 * cada una es un componente pequeño con sus propios estilos.
 */
@Component({
  selector: 'app-article-header',
  imports: [HeaderCinema, HeaderCompact, HeaderPlate, HeaderSplit],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-variant]': 'data().variant' },
  template: `
    @switch (data().variant) {
      @case ('cinema') {
        <app-header-cinema [data]="data()" />
      }
      @case ('split') {
        <app-header-split [data]="data()" />
      }
      @case ('plate') {
        <app-header-plate [data]="data()" />
      }
      @default {
        <app-header-compact [data]="data()" />
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
  `,
})
export class ArticleHeader {
  readonly data = input.required<ArticleHeaderData>();
}
