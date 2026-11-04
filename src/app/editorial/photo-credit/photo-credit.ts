import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Media } from '../../content/models';
import { creditLine } from '../../shared/ui/media/media-credit';

/**
 * Crédito de una fotografía colocado fuera de su <figure> (sobre la imagen o en un pie propio):
 * autor, procedencia y licencia enlazadas, y aviso de adaptación cuando la licencia lo exige.
 */
@Component({
  selector: 'app-photo-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 't-meta' },
  template: `
    @let c = line();
    Foto: {{ c.author }}
    @if (c.source) {
      ·
      @if (c.sourceUrl) {
        <a [href]="c.sourceUrl" rel="noopener" target="_blank">{{ c.source }}</a>
      } @else {
        {{ c.source }}
      }
    }
    @if (c.license) {
      ·
      @if (c.licenseUrl) {
        <a [href]="c.licenseUrl" rel="license noopener" target="_blank">{{ c.license }}</a>
      } @else {
        {{ c.license }}
      }
    }
    @if (c.adapted) {
      · adaptada
    }
  `,
  styles: `
    a {
      text-underline-offset: 2px;
    }

    // Opcional: los enlaces (procedencia y licencia) no se parten entre dos líneas.
    :host(.photo-credit--keep) a {
      white-space: nowrap;
    }
  `,
})
export class PhotoCredit {
  readonly media = input.required<Media>();

  protected readonly line = computed(() => creditLine(this.media().credit));
}
