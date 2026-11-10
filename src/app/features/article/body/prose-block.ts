import {
  ChangeDetectionStrategy,
  Component,
  ViewEncapsulation,
  inject,
  input,
} from '@angular/core';
import { ViewportScroller } from '@angular/common';
import { Router } from '@angular/router';
import { RichHtml } from './rich-html';
import { internalHref } from './rich-text-render';

/**
 * Texto enriquecido de un artículo: párrafos, subtítulos, listas y enlaces.
 *
 * El HTML llega preparado (renderRichText) y se inserta saneado por Angular (RichHtml): al
 * hidratar se conserva el del servidor, sin volver a escribirlo.
 * Los enlaces internos navegan con el router (sin recargar la página); una ancla al capítulo
 * en el que ya se está vuelve a desplazarse hasta él.
 *
 * Sin encapsulación: los estilos alcanzan al HTML insertado, siempre bajo app-prose-block.
 */
@Component({
  selector: 'app-prose-block',
  imports: [RichHtml],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  host: { '(click)': 'follow($event)' },
  template: `<div class="prose" [appRichHtml]="html()"></div>`,
  styleUrl: './prose-block.scss',
})
export class ProseBlock {
  private readonly router = inject(Router);
  private readonly scroller = inject(ViewportScroller);

  /** HTML ya preparado con renderRichText. */
  readonly html = input.required<string>();

  protected follow(event: MouseEvent): void {
    const href = internalHref(event);
    if (!href) return;
    event.preventDefault();
    const fragment = href.split('#')[1];
    // El router ignora la misma URL: si ya estamos en esa ancla, se desplaza igualmente.
    if (fragment && this.router.url === href) this.scroller.scrollToAnchor(fragment);
    else void this.router.navigateByUrl(href);
  }
}
