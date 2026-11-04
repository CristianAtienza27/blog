import { Directive, booleanAttribute, input, numberAttribute } from '@angular/core';
import { useMotion } from '../use-motion';

/**
 * Zoom controlado al entrar en pantalla: la fotografía se acerca ligeramente y se asienta
 * (escala 1 + intensidad → 1) a medida que el bloque sube hasta el centro de la pantalla.
 * Se aplica a un contenedor que incluya un <app-media> o a cualquier marco con overflow oculto.
 *
 * - `appScrollZoom` = intensidad (0–0,2; por defecto 0,08). En móvil, la mitad.
 * - `expand`: el marco pasa de los márgenes de la página a sangre (transición entre bloques).
 *   Solo en escritorio.
 *
 * Solo transform y clip-path, sin reflow. Sin efecto con reduced motion. No combinar con
 * appParallax sobre la misma imagen: ambos escalan su contenido.
 */
@Directive({ selector: '[appScrollZoom]' })
export class ScrollZoom {
  readonly zoom = input(0.08, {
    alias: 'appScrollZoom',
    transform: (value: unknown) => numberAttribute(value, 0.08),
  });
  readonly expand = input(false, { transform: booleanAttribute });

  constructor() {
    useMotion(({ api, el, reduce, mobile }) => {
      if (reduce) return;
      const { gsap } = api;
      const frame = el.querySelector<HTMLElement>('.media__frame') ?? el;
      const targets = Array.from(frame.children);
      const zoom = Math.min(Math.max(this.zoom(), 0), 0.2) * (mobile ? 0.5 : 1);
      const timeline = gsap.timeline({
        defaults: { ease: 'none' },
        scrollTrigger: { trigger: el, start: 'top bottom', end: 'center center', scrub: true },
      });
      if (targets.length && zoom > 0)
        timeline.fromTo(targets, { scale: 1 + zoom }, { scale: 1 }, 0);
      if (this.expand() && !mobile) {
        timeline.fromTo(
          el,
          { clipPath: 'inset(0% 4% 0% 4%)' },
          { clipPath: 'inset(0% 0% 0% 0%)' },
          0,
        );
      }
    });
  }
}
