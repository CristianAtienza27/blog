import { Injectable, signal } from '@angular/core';

/** Línea de lectura: un capítulo «se lee» cuando su título ha pasado este punto del viewport. */
const READING_LINE = 0.4;
/** Por scroll, un capítulo pasa a ser el activo si el lector se detiene en él este tiempo. */
const DWELL_MS = 400;
/** Tras mover el foco con el teclado, el scroll que provoca no cambia el capítulo activo. */
const FOCUS_HOLD_MS = 700;

/**
 * Capítulo que se está leyendo. Lo consume cualquier pieza del artículo que lo necesite (el
 * mapa y el itinerario, que destacan su parada; más adelante, el índice lateral): es único en
 * la aplicación y lo arranca el cuerpo del artículo.
 *
 * - Por posición: el último capítulo cuyo título ha pasado la línea de lectura. Para que pasar
 *   de largo no cuente como leer, el cambio se confirma cuando el lector se detiene en él.
 * - Enlace directo (#capitulo) y teclado (foco dentro de un capítulo): cambio inmediato.
 * - Fuera de los capítulos (de vuelta al mapa, en la introducción o tras el cuerpo) se
 *   conserva el último: al volver al mapa, su parada sigue destacada.
 * Solo en el navegador y tras el render: en SSR no hay capítulo activo, así el HTML del
 * servidor y el de la hidratación coinciden.
 */
@Injectable({ providedIn: 'root' })
export class ChapterReading {
  /** Id del capítulo activo (null: aún ninguno). */
  readonly active = signal<string | null>(null);

  /** Sesión en curso: al cerrar una (otro artículo ya empezó la suya) no se toca el estado. */
  private session = 0;

  /** Empieza a seguir la lectura dentro de `root`; devuelve la función que la detiene. */
  start(root: HTMLElement): () => void {
    const view = root.ownerDocument.defaultView;
    if (!view) return () => undefined;
    const session = ++this.session;
    this.active.set(null);

    const headings = () =>
      Array.from(root.querySelectorAll<HTMLElement>('app-chapter-heading [id]'));
    let frame = 0;
    let heldUntil = 0;
    let tabAt = 0;
    let pending: { id: string; since: number } | null = null;
    let dwell = 0;

    /** Capítulo en la línea de lectura; null fuera de los capítulos. */
    const atLine = (): string | null => {
      const line = view.innerHeight * READING_LINE;
      if (root.getBoundingClientRect().bottom <= line) return null;
      let current: string | null = null;
      for (const heading of headings()) {
        if (heading.getBoundingClientRect().top <= line) current = heading.id;
      }
      return current;
    };

    const byPosition = (immediate = false) => {
      frame = 0;
      if (Date.now() < heldUntil) return;
      const current = atLine();
      if (current === null || current === this.active()) {
        pending = null;
        return;
      }
      if (immediate || (pending?.id === current && Date.now() - pending.since >= DWELL_MS)) {
        pending = null;
        this.active.set(current);
        return;
      }
      if (pending?.id !== current) pending = { id: current, since: Date.now() };
      // Si el scroll se detiene aquí, se confirma sin esperar a otro evento.
      view.clearTimeout(dwell);
      dwell = view.setTimeout(() => byPosition(), DWELL_MS + 20);
    };
    const schedule = () => {
      if (!frame) frame = view.requestAnimationFrame(() => byPosition());
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Tab') tabAt = Date.now();
    };
    // Un salto a un ancla (#capitulo) manda siempre, sin esperar.
    const onHash = () => {
      heldUntil = 0;
      view.requestAnimationFrame(() => byPosition(true));
    };
    const byFocus = (event: FocusEvent) => {
      // Solo el foco que llega con el tabulador (un clic lo decide la posición).
      if (Date.now() - tabAt > 200) return;
      const target = event.target as Node;
      let current: string | null = null;
      for (const heading of headings()) {
        // El título mismo o algo que va detrás de él en el documento.
        const follows = heading.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING;
        if (heading === target || heading.contains(target) || follows) current = heading.id;
      }
      heldUntil = Date.now() + FOCUS_HOLD_MS;
      pending = null;
      if (current !== null) this.active.set(current);
    };

    view.addEventListener('scroll', schedule, { passive: true });
    view.addEventListener('resize', schedule, { passive: true });
    view.addEventListener('hashchange', onHash);
    view.addEventListener('keydown', onKey, { capture: true });
    root.addEventListener('focusin', byFocus);
    // Primera lectura sin esperar: al llegar con #capitulo, ese capítulo ya está activo.
    frame = view.requestAnimationFrame(() => byPosition(true));
    return () => {
      view.removeEventListener('scroll', schedule);
      view.removeEventListener('resize', schedule);
      view.removeEventListener('hashchange', onHash);
      view.removeEventListener('keydown', onKey, { capture: true });
      root.removeEventListener('focusin', byFocus);
      if (frame) view.cancelAnimationFrame(frame);
      view.clearTimeout(dwell);
      if (session === this.session) this.active.set(null);
    };
  }
}
