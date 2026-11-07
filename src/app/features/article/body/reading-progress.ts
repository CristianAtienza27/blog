import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
} from '@angular/core';

/** Progreso (0–1) de la lectura del cuerpo: 0 con su inicio arriba, 1 con su final abajo. */
export function readingProgress(bodyTop: number, bodyHeight: number, viewport: number): number {
  const travel = bodyHeight - viewport;
  if (travel <= 0) return bodyTop <= 0 ? 1 : 0;
  return Math.min(1, Math.max(0, -bodyTop / travel));
}

/**
 * Barra de lectura: una línea fina con el acento de la sección en el borde superior de la
 * pantalla. Mide el cuerpo del artículo (no la página entera): empieza al llegar al texto y
 * se completa al terminarlo.
 * - Con CSS (animation-timeline): la línea de tiempo de vista del cuerpo, sin JavaScript.
 * - Si el navegador no la admite: se calcula por fotograma al desplazarse.
 * Decorativa para lectores de pantalla (el índice ya indica el capítulo).
 */
@Component({
  selector: 'app-reading-progress',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `<span class="progress__bar"></span>`,
  styleUrl: './reading-progress.scss',
})
export class ReadingProgress {
  constructor() {
    const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const view = host.ownerDocument.defaultView;
      if (!view || view.CSS?.supports?.('animation-timeline: view()')) return;
      const body = host.parentElement?.querySelector<HTMLElement>('.body');
      if (!body) return;
      host.classList.add('progress--script');
      let frame = 0;
      const update = () => {
        frame = 0;
        const box = body.getBoundingClientRect();
        host.style.setProperty(
          '--progress',
          String(readingProgress(box.top, box.height, view.innerHeight)),
        );
      };
      const schedule = () => {
        if (!frame) frame = view.requestAnimationFrame(update);
      };
      view.addEventListener('scroll', schedule, { passive: true });
      view.addEventListener('resize', schedule, { passive: true });
      update();
      destroyRef.onDestroy(() => {
        view.removeEventListener('scroll', schedule);
        view.removeEventListener('resize', schedule);
        if (frame) view.cancelAnimationFrame(frame);
      });
    });
  }
}
