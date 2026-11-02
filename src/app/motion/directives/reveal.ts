import { Directive, input, numberAttribute } from '@angular/core';
import { isInViewport, useMotion } from '../use-motion';

export type RevealVariant = 'fade-up' | 'fade' | 'mask' | 'stagger';

/**
 * Aparición al entrar en pantalla.
 * - fade-up: sube y aparece (por defecto).
 * - fade: solo opacidad.
 * - mask: se descubre de abajo arriba con recorte; ideal para fotografías.
 * - stagger: los hijos directos aparecen en cascada.
 *
 * Sin parpadeos: en la carga inicial no se oculta lo que ya está en pantalla.
 * Con reduced motion no se anima nada.
 */
@Directive({ selector: '[appReveal]' })
export class Reveal {
  readonly variant = input<RevealVariant, RevealVariant | ''>('fade-up', {
    alias: 'appReveal',
    transform: (value) => value || 'fade-up',
  });
  readonly revealDelay = input(0, { transform: numberAttribute });

  constructor() {
    useMotion(({ api, el, reduce, mobile, entering, duration }) => {
      if (reduce || (!entering && isInViewport(el))) return;
      const { gsap } = api;
      const variant = this.variant();
      const distance = mobile ? 24 : 48;
      const scrollTrigger = { trigger: el, start: 'top 88%', once: true };

      if (variant === 'mask') {
        gsap.fromTo(
          el,
          { clipPath: 'inset(100% 0% 0% 0%)' },
          {
            clipPath: 'inset(0% 0% 0% 0%)',
            duration: duration('cinematic'),
            ease: 'ca-expo',
            delay: this.revealDelay(),
            scrollTrigger,
            clearProps: 'clipPath',
          },
        );
        return;
      }

      const targets = variant === 'stagger' ? Array.from(el.children) : el;
      gsap.fromTo(
        targets,
        { autoAlpha: 0, y: variant === 'fade' ? 0 : distance },
        {
          autoAlpha: 1,
          y: 0,
          duration: duration('slow') + 0.2,
          ease: 'ca-out',
          delay: this.revealDelay(),
          stagger: variant === 'stagger' ? 0.09 : 0,
          scrollTrigger,
          clearProps: 'transform,opacity,visibility',
        },
      );
    });
  }
}
