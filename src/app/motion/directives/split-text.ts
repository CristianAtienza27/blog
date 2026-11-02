import { Directive, input } from '@angular/core';
import { isInViewport, useMotion } from '../use-motion';

export type SplitMode = 'lines' | 'words';

/**
 * Titulares que aparecen línea a línea (o palabra a palabra) desde una máscara.
 * Usa SplitText con autoSplit: se vuelve a dividir al cambiar el ancho o cargar fuentes.
 * SplitText añade aria-label al elemento: los lectores de pantalla leen el texto completo.
 *
 * Solo para texto estático (titulares): no usar en contenido que cambie después.
 * En la carga inicial no anima lo que ya está en pantalla; con reduced motion no divide nada.
 */
@Directive({ selector: '[appSplitText]' })
export class SplitText {
  readonly mode = input<SplitMode, SplitMode | ''>('lines', {
    alias: 'appSplitText',
    transform: (value) => value || 'lines',
  });

  constructor() {
    useMotion(({ api, el, reduce, entering, duration }) => {
      if (reduce || (!entering && isInViewport(el))) return;
      const { gsap, SplitText: Splitter } = api;
      const mode = this.mode();

      Splitter.create(el, {
        type: mode === 'words' ? 'words,lines' : 'lines',
        mask: 'lines',
        linesClass: 'split-line',
        autoSplit: true,
        // Al re-dividir, la animación se recrea y SplitText conserva su progreso.
        onSplit: (self) =>
          gsap.from(mode === 'words' ? self.words : self.lines, {
            yPercent: 115,
            duration: duration('cinematic'),
            ease: 'ca-expo',
            stagger: mode === 'words' ? 0.04 : 0.1,
            scrollTrigger: { trigger: el, start: 'top 88%', once: true },
          }),
      });
    });
  }
}
