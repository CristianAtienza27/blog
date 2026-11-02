import { DestroyRef, ElementRef, afterNextRender, inject } from '@angular/core';
import { MotionApi, MotionDuration, MotionService } from './motion';

export interface MotionContext {
  api: MotionApi;
  el: HTMLElement;
  /** prefers-reduced-motion: reduce. */
  reduce: boolean;
  /** Viewport por debajo de md (900px): animaciones más contenidas. */
  mobile: boolean;
  /** true si la página llegó por navegación en el navegador (no en la carga inicial). */
  entering: boolean;
  duration: (name: MotionDuration) => number;
}

/** Puede devolver una función de limpieza adicional (clases, listeners…). */
export type MotionSetup = (context: MotionContext) => void | (() => void);

/**
 * Base común de las directivas de movimiento. Debe llamarse en el constructor.
 * - Solo navegador: se ejecuta tras el render, nunca en SSR.
 * - Usa gsap.matchMedia(): al cambiar reduce/mobile se revierte y se vuelve a montar.
 * - Al destruirse revierte todo: tweens, ScrollTriggers y SplitText creados dentro.
 */
export function useMotion(setup: MotionSetup): void {
  const motion = inject(MotionService);
  const el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  let matchMedia: gsap.MatchMedia | null = null;
  let destroyed = false;

  afterNextRender(() => {
    void motion.load().then((api) => {
      if (!api || destroyed) return;
      const entering = motion.clientNavigation();
      matchMedia = api.gsap.matchMedia();
      matchMedia.add(
        {
          motion: '(prefers-reduced-motion: no-preference)',
          reduce: '(prefers-reduced-motion: reduce)',
          mobile: '(width < 900px)',
        },
        (context) => {
          const { reduce = false, mobile = false } = context.conditions ?? {};
          return setup({ api, el, reduce, mobile, entering, duration: (n) => motion.duration(n) });
        },
        el,
      );
    });
  });

  inject(DestroyRef).onDestroy(() => {
    destroyed = true;
    matchMedia?.revert();
    matchMedia = null;
  });
}

/** El elemento está (al menos en parte) dentro del viewport. */
export function isInViewport(el: Element): boolean {
  const rect = el.getBoundingClientRect();
  return rect.top < window.innerHeight && rect.bottom > 0;
}
