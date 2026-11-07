import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { Category, Tag } from '../../../content/models';
import { SECTIONS } from '../../../layout/navigation';

/**
 * Temas del artículo (sus etiquetas) y su sección. Las etiquetas aún no tienen página propia:
 * se muestran como texto, sin enlaces que no llevarían a ninguna parte. La sección sí tiene
 * ruta y se enlaza.
 */
@Component({
  selector: 'app-article-tags',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dl class="tags">
      @if (tags().length) {
        <div class="tags__row">
          <dt class="t-meta">Temas</dt>
          <dd>
            <ul class="tags__list">
              @for (tag of tags(); track tag.id) {
                <li>{{ tag.label }}</li>
              }
            </ul>
          </dd>
        </div>
      }
      @if (section(); as s) {
        <div class="tags__row">
          <dt class="t-meta">Sección</dt>
          <dd>
            <a class="tags__section" [routerLink]="s.path">
              {{ s.label }} <span aria-hidden="true">→</span>
            </a>
          </dd>
        </div>
      }
    </dl>
  `,
  styles: `
    :host {
      display: block;
    }

    .tags {
      display: grid;
      gap: var(--space-4);
      margin: 0;
    }

    .tags__row {
      display: grid;
      gap: var(--space-2);

      dt {
        color: var(--color-ink-muted);
      }

      dd {
        margin: 0;
      }

      @media (width >= 640px) {
        grid-template-columns: 8.5rem minmax(0, 1fr);
        gap: var(--space-5);
      }
    }

    // Temas en línea, separados por un punto medio: ni botones ni píldoras.
    .tags__list {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-3);
      padding: 0;
      margin: 0;
      font-family: var(--font-display);
      font-size: var(--text-lg);
      list-style: none;

      li:not(:last-child)::after {
        margin-inline-start: var(--space-3);
        color: var(--color-ink-muted);
        content: '·';
      }
    }

    .tags__section {
      font-family: var(--font-display);
      font-size: var(--text-lg);
      color: var(--accent);
      text-decoration: underline;
      text-decoration-thickness: 1px;
      text-underline-offset: 4px;

      &:focus-visible {
        outline: var(--focus-ring-width) solid var(--focus-ring-color);
        outline-offset: var(--focus-ring-offset);
      }
    }
  `,
})
export class ArticleTags {
  readonly tags = input.required<readonly Tag[]>();
  readonly category = input.required<Category>();

  /** La sección con su ruta real (las cinco secciones están en la navegación). */
  protected readonly section = computed(
    () => SECTIONS.find((s) => s.id === this.category().id) ?? null,
  );
}
