import {
  DOCUMENT,
  Directive,
  ElementRef,
  PLATFORM_ID,
  Renderer2,
  SecurityContext,
  effect,
  inject,
  input,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';

/**
 * ¿El elemento ya muestra este HTML? Los dos lados pasan por el analizador del navegador, así
 * que da igual cómo llegaran escritas las comillas o las entidades.
 */
export function rendersSameHtml(el: Element, html: string, document: Document): boolean {
  if (!el.childNodes.length) return false;
  const parsed = document.createElement('template');
  parsed.innerHTML = html;
  return parsed.innerHTML === el.innerHTML;
}

/**
 * Inserta HTML saneado en el elemento, como [innerHTML], pero sin volver a escribirlo al
 * hidratar: si el HTML que llegó del servidor es el mismo, se conserva tal cual. Con
 * [innerHTML], Angular lo reasigna al hidratar y el navegador sustituye todos los nodos:
 * vuelve a pintar el texto (y Chrome cuenta ese párrafo repintado como un LCP nuevo).
 *
 * El saneado es el de Angular (DomSanitizer, nunca bypassSecurityTrustHtml) y la comparación
 * se hace con el HTML ya saneado: lo que haya en el DOM solo se conserva si es exactamente lo
 * que se habría escrito. En el servidor siempre se escribe.
 */
@Directive({ selector: '[appRichHtml]' })
export class RichHtml {
  readonly appRichHtml = input.required<string>();

  constructor() {
    const el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const renderer = inject(Renderer2);
    const sanitizer = inject(DomSanitizer);
    const document = inject(DOCUMENT);
    const browser = isPlatformBrowser(inject(PLATFORM_ID));

    effect(() => {
      const html = sanitizer.sanitize(SecurityContext.HTML, this.appRichHtml()) ?? '';
      // Al hidratar, el del servidor se conserva si es el mismo (no se vuelve a pintar).
      if (browser && rendersSameHtml(el, html, document)) return;
      renderer.setProperty(el, 'innerHTML', html);
    });
  }
}
