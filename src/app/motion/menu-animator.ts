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
 * Coreografía del menú overlay.
 * Apertura: la cortina cae desde arriba, las secciones suben línea a línea desde su máscara
 * y la fotografía se descubre de abajo arriba. Cierre: todo sube y la cortina se recoge.
 * Con reduced motion: fundido corto.
 */
@Injectable({ providedIn: 'root' })
export class MenuAnimator {
  private readonly motion = inject(MotionService);
  private timeline: gsap.core.Timeline | null = null;

  async open(dialog: HTMLDialogElement): Promise<void> {
    const api = await this.motion.load();
    if (!api || !dialog.open) return;
    const { gsap } = api;
    const q = gsap.utils.selector(dialog);
    const d = (name: Parameters<MotionService['duration']>[0]) => this.motion.duration(name);

    this.timeline?.kill();

    if (this.motion.reducedMotion()) {
      this.timeline = gsap
        .timeline()
        .fromTo(dialog, { autoAlpha: 0 }, { autoAlpha: 1, duration: d('fast') });
      return;
    }

    this.timeline = gsap
      .timeline({
        defaults: { ease: 'ca-expo' },
        // Al terminar se limpian los estilos en línea: los hover de CSS vuelven a funcionar.
        onComplete: () => gsap.set(q(ALL), { clearProps: 'all' }),
      })
      .fromTo(
        q(CURTAIN),
        { clipPath: COLLAPSED_TOP },
        { clipPath: OPEN, duration: d('cinematic') * 0.8 },
      )
      .fromTo(
        q(LINES),
        { yPercent: 115 },
        { yPercent: 0, duration: d('cinematic') * 0.75, stagger: 0.07 },
        0.28,
      )
      .fromTo(
        q(FADES),
        { autoAlpha: 0, y: 12 },
        { autoAlpha: 1, y: 0, duration: d('slow'), ease: 'ca-out', stagger: 0.03 },
        0.5,
      )
      .fromTo(
        q(PREVIEW),
        { clipPath: COLLAPSED_BOTTOM },
        { clipPath: OPEN, duration: d('cinematic') },
        0.35,
      );
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
