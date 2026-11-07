import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Article } from '../../../content/models';
import { creditLine } from '../../../shared/ui/media/media-credit';
import { creditsPlan } from './closing';

/**
 * Créditos del artículo, como el colofón de un reportaje: el texto (autor) separado de las
 * fotografías, que se agrupan por autor y licencia (un crédito, varias fotos, cada una con
 * el enlace a su fuente). Después, las referencias que cita el texto y las notas de
 * producción que se deducen del contenido. Sin fechas: en el contenido de demostración no son
 * reales.
 */
@Component({
  selector: 'app-article-credits',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let p = plan();
    <section class="credits" aria-label="Créditos">
      <h2 class="credits__title t-meta">Créditos</h2>
      <dl class="credits__list">
        <div class="credits__row">
          <dt class="t-meta">Texto</dt>
          <dd>
            <span class="credits__name">{{ p.author.name }}</span>
            <span class="credits__role">{{ p.author.role }}</span>
          </dd>
        </div>

        @if (p.photos.length) {
          <div class="credits__row">
            <dt class="t-meta">Fotografía</dt>
            <dd>
              <ul class="credits__photos">
                @for (group of groups(); track $index) {
                  <li>
                    <span class="credits__name">{{ group.line.author }}</span>
                    <span class="credits__works">
                      @for (photo of group.photos; track $index) {
                        @if (!$first) {
                          <span aria-hidden="true"> · </span>
                        }
                        @if (photo.href) {
                          <a [href]="photo.href" rel="noopener" target="_blank">{{
                            photo.label
                          }}</a>
                        } @else {
                          {{ photo.label }}
                        }
                        @if (photo.cover) {
                          <span class="credits__mark t-meta">(portada)</span>
                        }
                      }
                    </span>
                    <span class="credits__license t-meta">
                      @if (group.line.source) {
                        {{ group.line.source }} ·
                      }
                      @if (group.line.licenseUrl) {
                        <a [href]="group.line.licenseUrl" rel="license noopener" target="_blank">{{
                          group.line.license
                        }}</a>
                      } @else {
                        {{ group.line.license }}
                      }
                      @if (group.line.adapted) {
                        · adaptada
                      }
                    </span>
                  </li>
                }
              </ul>
            </dd>
          </div>
        }

        @if (p.references.length) {
          <div class="credits__row">
            <dt class="t-meta">Referencias</dt>
            <dd>
              <ul class="credits__refs">
                @for (ref of p.references; track ref.href) {
                  <li>
                    <a [href]="ref.href" rel="noopener" target="_blank">{{ ref.label }}</a>
                    <span class="credits__host t-meta">{{ ref.host }}</span>
                  </li>
                }
              </ul>
            </dd>
          </div>
        }

        @if (p.notes.length || p.demo) {
          <div class="credits__row">
            <dt class="t-meta">Producción</dt>
            <dd class="credits__notes">
              @if (p.demo) {
                <p>Contenido de demostración.</p>
              }
              @for (note of p.notes; track $index) {
                <p>{{ note }}</p>
              }
            </dd>
          </div>
        }
      </dl>
    </section>
  `,
  styleUrl: './article-credits.scss',
})
export class ArticleCredits {
  readonly article = input.required<Article>();

  protected readonly plan = computed(() => creditsPlan(this.article()));
  /** Cada grupo con su línea de crédito ya traducida (procedencia, licencia, adaptación). */
  protected readonly groups = computed(() =>
    this.plan().photos.map((group) => ({ ...group, line: creditLine(group.credit) })),
  );
}
