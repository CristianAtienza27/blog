import { Directive, input, numberAttribute } from '@angular/core';
import { useMotion } from '../use-motion';

/**
 * Parallax fotográfico: la imagen se desplaza dentro de su marco mientras se hace scroll.
 * Se aplica a <app-media> (mueve el contenido de .media__frame) o a cualquier contenedor
 * con overflow oculto (mueve sus hijos). Solo transform: no provoca reflow.
 *
 * `appParallax` = intensidad (0.05–0.3, por defecto 0.15). En móvil se reduce a la mitad.
 * Con reduced motion queda estático.
 */
@Directive({ selector: '[appParallax]' })
export class Parallax {
  readonly strength = input(0.15, {
    alias: 'appParallax',
    transform: (value: unknown) => numberAttribute(value, 0.15),
  });

  constructor() {
    useMotion(({ api, el, reduce, mobile }) => {
      if (reduce) return;
      const frame = el.querySelector<HTMLElement>('.media__frame') ?? el;
      const targets = Array.from(frame.children);
      if (!targets.length) return;

      const strength = Math.min(Math.max(this.strength(), 0), 0.4) * (mobile ? 0.5 : 1);
      const travel = strength * 50;

      api.gsap.fromTo(
        targets,
        // La escala cubre el recorrido: nunca se ven bordes vacíos.
        { yPercent: -travel, scale: 1 + strength + 0.02 },
        {
          yPercent: travel,
          ease: 'none',
          scrollTrigger: { trigger: frame, start: 'top bottom', end: 'bottom top', scrub: true },
        },
      );
    });
  }
}
