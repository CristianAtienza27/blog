import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Eyebrow } from '../../../shared/ui/eyebrow/eyebrow';
import type { HeaderSection } from '../article-presentation';

/** Antetítulo del artículo: número y sección (enlace a su portada) y formato. */
@Component({
  selector: 'app-article-kicker',
  imports: [Eyebrow, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-eyebrow [index]="section().index">
      <a class="kicker__section" [routerLink]="section().path">{{ section().label }}</a>
      · {{ format() }}
    </app-eyebrow>
  `,
  styles: `
    :host {
      display: block;
    }

    .kicker__section {
      text-decoration: none;

      &:hover {
        text-decoration: underline;
        text-underline-offset: 0.3em;
      }
    }
  `,
})
export class ArticleKicker {
  readonly section = input.required<HeaderSection>();
  readonly format = input.required<string>();
}
