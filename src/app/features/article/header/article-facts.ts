import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Tag } from '../../../shared/ui/tag/tag';
import type { ArticleFact } from '../article-presentation';

/**
 * Metadatos de la cabecera en mono (lectura, recorrido, lugares, fechas) y la etiqueta de
 * contenido de demostración. Los nombres de cada dato solo los oyen los lectores de pantalla.
 *
 * Móvil: un dato por línea. Desde md, `layout`: «inline», en una línea con separadores;
 * «stack», apilados (columnas estrechas); «end», apilados y a la derecha (columna de margen).
 */
@Component({
  selector: 'app-article-facts',
  imports: [Tag],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-layout]': 'layout()' },
  template: `
    <dl class="facts t-meta">
      @for (fact of facts(); track fact.label) {
        <div class="facts__item">
          <dt class="visually-hidden">{{ fact.label }}</dt>
          <dd>
            @if (fact.datetime) {
              <time [attr.datetime]="fact.datetime">{{ fact.value }}</time>
            } @else {
              {{ fact.value }}
            }
          </dd>
        </div>
      }
    </dl>
    @if (demo()) {
      <app-tag tone="tint">Contenido de demostración</app-tag>
    }
  `,
  styles: `
    @use 'abstracts' as *;

    :host {
      display: grid;
      gap: var(--space-4);
      justify-items: start;
    }

    .facts {
      display: grid;
      gap: var(--space-1);
      color: inherit;
    }

    @include mq(md) {
      :host([data-layout='inline']) .facts {
        display: flex;
        flex-wrap: wrap;
      }

      :host([data-layout='inline']) .facts__item + .facts__item::before {
        padding-inline: 1ch;

        // Separador visual: sin texto alternativo, los lectores de pantalla no lo leen.
        content: '·' / '';
      }

      :host([data-layout='end']),
      :host([data-layout='end']) .facts {
        justify-items: end;
        text-align: end;
      }
    }

    .facts__item {
      display: flex;
    }
  `,
})
export class ArticleFacts {
  readonly facts = input.required<ArticleFact[]>();
  readonly demo = input(false);
  readonly layout = input<'inline' | 'stack' | 'end'>('inline');
}
