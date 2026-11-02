import { Directive } from '@angular/core';
import { useMotion } from '../use-motion';

/**
 * Galería horizontal. Estructura: <section appHorizontal><div data-horizontal-track>…</div></section>
 *
 * - Base (SSR, móvil, reduced motion): carrusel nativo con scroll-snap, sin JavaScript.
 * - Escritorio con movimiento: la sección se fija y el scroll vertical desplaza la pista.
 */
@Directive({
  selector: '[appHorizontal]',
  host: { class: 'horizontal' },
})
export class Horizontal {
  constructor() {
    useMotion(({ api, el, reduce, mobile }) => {
      if (reduce || mobile) return;
      const track = el.querySelector<HTMLElement>('[data-horizontal-track]');
      if (!track) return;

      const distance = () => Math.max(0, track.scrollWidth - el.clientWidth);
      if (distance() <= 0) return;

      el.classList.add('is-pinned');
      api.gsap.to(track, {
        x: () => -distance(),
        ease: 'none',
        scrollTrigger: {
          trigger: el,
          start: 'center center',
          end: () => `+=${distance()}`,
          pin: true,
          scrub: 0.6,
          invalidateOnRefresh: true,
        },
      });

      return () => el.classList.remove('is-pinned');
    });
  }
}
