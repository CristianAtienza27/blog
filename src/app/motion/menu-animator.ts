import { Injectable, inject } from '@angular/core';
import { MotionService } from './motion';

const CURTAIN = '.menu__curtain';
const LINES = '[data-menu-line]';
const FADES = '[data-menu-fade]';
const PREVIEW = '[data-menu-preview]';
const ALL = [CURTAIN, LINES, FADES, PREVIEW].join(',');

const OPEN = 'inset(0% 0% 0% 0%)';
const COLLAPSED_TOP = 'inset(0% 0% 100% 0%)';
const COLLAPSED_BOTTOM = 'inset(100% 0% 0% 0%)';

/**
 * Coreografía del menú overlay, controlada desde un único timeline.
 *
 * Secuencia de apertura:
 * 1. `prepare()` fija los estados iniciales (cortina recogida, palabras bajo su máscara,
 *    fotografía oculta) ANTES de que el diálogo se muestre: el primer fotograma visible
 *    ya es el inicial y nunca aparece el estado final.
 * 2. `open()` anima desde el estado actual hasta el final. Si llega en mitad de un cierre,
 *    lo interrumpe y vuelve a abrir desde donde estaba, sin saltos.
 *
 * El cierre hace el recorrido inverso. Con reduced motion, un fundido corto.
 */
@Injectable({ providedIn: 'root' })
export class MenuAnimator {
  private readonly motion = inject(MotionService);
  private timeline: gsap.core.Timeline | null = null;

  /**
   * Carga el motor (si aún no lo está) y deja el menú en su estado inicial.
   * Devuelve false si no hay animación disponible: el menú se mostrará sin animar.
   */
  async prepare(dialog: HTMLDialogElement): Promise<boolean> {
    const api = this.motion.loaded ?? (await this.motion.load());
    if (!api) return false;
    const { gsap } = api;
    const q = gsap.utils.selector(dialog);
    this.timeline?.kill();
    this.timeline = null;
    // Solo opacidad (no visibility): el botón de cerrar debe poder recibir el autofocus
    // de showModal() aunque todavía no se vea.
    if (this.motion.reducedMotion()) {
      gsap.set(dialog, { opacity: 0 });
    } else {
      gsap.set(q(CURTAIN), { clipPath: COLLAPSED_TOP });
      gsap.set(q(LINES), { yPercent: 115 });
      gsap.set(q(FADES), { opacity: 0, y: 12 });
      gsap.set(q(PREVIEW), { clipPath: COLLAPSED_BOTTOM });
    }
    return true;
  }

  /** Anima hasta el estado abierto desde el estado actual (preparado o a medio cerrar). */
  open(dialog: HTMLDialogElement): void {
    const api = this.motion.loaded;
    if (!api) return;
    const { gsap } = api;
    const q = gsap.utils.selector(dialog);
    const d = (name: Parameters<MotionService['duration']>[0]) => this.motion.duration(name);

    // Interrumpe un cierre en curso: su promesa se resuelve como interrumpida.
    this.timeline?.kill();

    if (this.motion.reducedMotion()) {
      this.timeline = gsap.timeline().to(dialog, { autoAlpha: 1, duration: d('fast') });
      return;
    }

    this.timeline = gsap
      .timeline({
        defaults: { ease: 'ca-expo' },
        // Al terminar se limpian los estilos en línea: los hover de CSS vuelven a funcionar.
        onComplete: () => gsap.set(q(ALL), { clearProps: 'all' }),
      })
      .to(q(CURTAIN), { clipPath: OPEN, duration: d('cinematic') * 0.8 })
      .to(q(LINES), { yPercent: 0, duration: d('cinematic') * 0.75, stagger: 0.07 }, 0.28)
      .to(q(FADES), { autoAlpha: 1, y: 0, duration: d('slow'), ease: 'ca-out', stagger: 0.03 }, 0.5)
      .to(q(PREVIEW), { clipPath: OPEN, duration: d('cinematic') }, 0.35);
  }

  /** Resuelve true al terminar, o false si se interrumpe (por ejemplo, al reabrir). */
  async close(dialog: HTMLDialogElement): Promise<boolean> {
    const api = this.motion.loaded ?? (await this.motion.load());
    if (!api) return true;
    const { gsap } = api;
    const q = gsap.utils.selector(dialog);
    const d = (name: Parameters<MotionService['duration']>[0]) => this.motion.duration(name);

    this.timeline?.kill();

    const timeline = this.motion.reducedMotion()
      ? gsap.timeline().to(dialog, { autoAlpha: 0, duration: d('fast') })
      : gsap
          .timeline({ defaults: { ease: 'ca-in-out' } })
          .to(q(LINES), { yPercent: -115, duration: d('slow') * 0.7, stagger: 0.035 })
          .to(q(FADES), { autoAlpha: 0, duration: d('base') }, 0)
          .to(q(PREVIEW), { clipPath: COLLAPSED_TOP, duration: d('slow') }, 0)
          .to(q(CURTAIN), { clipPath: COLLAPSED_TOP, duration: d('slow') }, 0.2);
    this.timeline = timeline;

    return new Promise<boolean>((resolve) => {
      timeline.eventCallback('onComplete', () => resolve(true));
      timeline.eventCallback('onInterrupt', () => resolve(false));
    });
  }

  /** Detiene cualquier animación y elimina los estilos en línea (tras cerrar el diálogo). */
  reset(dialog: HTMLDialogElement): void {
    this.timeline?.kill();
    this.timeline = null;
    const api = this.motion.loaded;
    if (!api) return;
    api.gsap.set([dialog, ...api.gsap.utils.selector(dialog)(ALL)], { clearProps: 'all' });
  }

  dispose(): void {
    this.timeline?.kill();
    this.timeline = null;
  }
}
