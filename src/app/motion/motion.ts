import { isPlatformBrowser } from '@angular/common';
import { DOCUMENT, DestroyRef, Injectable, PLATFORM_ID, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';
import type { gsap as GsapCore } from 'gsap';
import type { ScrollTrigger as ScrollTriggerPlugin } from 'gsap/ScrollTrigger';
import type { SplitText as SplitTextPlugin } from 'gsap/SplitText';

export interface MotionApi {
  gsap: typeof GsapCore;
  ScrollTrigger: typeof ScrollTriggerPlugin;
  SplitText: typeof SplitTextPlugin;
}

export type MotionDuration = 'instant' | 'fast' | 'base' | 'slow' | 'cinematic';

/** Curvas registradas en GSAP a partir de los tokens CSS (--ease-*): 'ca-out', 'ca-expo'… */
const EASES = ['standard', 'out', 'in-out', 'expo'] as const;

const DEFAULT_DURATIONS: Record<MotionDuration, number> = {
  instant: 0.12,
  fast: 0.22,
  base: 0.38,
  slow: 0.7,
  cinematic: 1.2,
};

/** Tiempo CSS válido: número no negativo con unidad ms o s («700ms», «.7s», «1.2s»). */
const CSS_TIME = /^(\d+(?:\.\d+)?|\.\d+)(ms|s)$/i;

/**
 * Convierte un tiempo CSS a segundos. Los tokens llegan en ms en desarrollo y en s en
 * producción (el minificador reescribe «700ms» como «.7s»): hay que respetar la unidad.
 * Vacío, sin unidad o no válido: la duración de reserva.
 */
export function cssTimeToSeconds(value: string, fallback: number): number {
  const match = CSS_TIME.exec(value.trim());
  if (!match) return fallback;
  const amount = Number(match[1]);
  return match[2].toLowerCase() === 'ms' ? amount / 1000 : amount;
}

/**
 * Motor de animación.
 * - GSAP se carga bajo demanda y solo en el navegador (nunca en el bundle inicial ni en SSR).
 * - Curvas y duraciones se leen de los tokens CSS: CSS y GSAP se mueven igual.
 * - Agrupa los ScrollTrigger.refresh() tras cargar imágenes, fuentes o navegar.
 */
@Injectable({ providedIn: 'root' })
export class MotionService {
  private readonly document = inject(DOCUMENT);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  private loading: Promise<MotionApi | null> | null = null;
  private api: MotionApi | null = null;
  private durations = { ...DEFAULT_DURATIONS };
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;

  /** Preferencia del sistema prefers-reduced-motion (reactiva). */
  readonly reducedMotion = signal(false);

  /**
   * true tras la primera navegación en el navegador. En la carga inicial el contenido
   * ya es visible (SSR): no se oculta lo que está en pantalla para no provocar parpadeos.
   */
  readonly clientNavigation = signal(false);

  constructor() {
    if (!this.isBrowser) return;
    const view = this.document.defaultView;
    const destroyRef = inject(DestroyRef);

    const query =
      typeof view?.matchMedia === 'function'
        ? view.matchMedia('(prefers-reduced-motion: reduce)')
        : null;
    if (query) {
      this.reducedMotion.set(query.matches);
      const onChange = (event: MediaQueryListEvent) => this.reducedMotion.set(event.matches);
      query.addEventListener('change', onChange);
      destroyRef.onDestroy(() => query.removeEventListener('change', onChange));
    }

    const subscription = inject(Router).events.subscribe((event) => {
      if (event instanceof NavigationStart && event.id > 1) this.clientNavigation.set(true);
      if (event instanceof NavigationEnd) this.refresh();
    });
    destroyRef.onDestroy(() => {
      subscription.unsubscribe();
      clearTimeout(this.refreshTimer);
    });
  }

  /** Carga GSAP y sus plugins una sola vez. En el servidor devuelve null. */
  load(): Promise<MotionApi | null> {
    if (!this.isBrowser) return Promise.resolve(null);
    this.loading ??= this.import().catch((error: unknown) => {
      console.error('No se pudo cargar el motor de animación', error);
      return null;
    });
    return this.loading;
  }

  /** API ya cargada (o null): para usos síncronos como cerrar el menú. */
  get loaded(): MotionApi | null {
    return this.api;
  }

  duration(name: MotionDuration): number {
    return this.durations[name];
  }

  /** Recalcula posiciones de ScrollTrigger, agrupando llamadas seguidas. */
  refresh(): void {
    if (!this.isBrowser) return;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => this.api?.ScrollTrigger.refresh(), 120);
  }

  private async import(): Promise<MotionApi> {
    const [{ gsap }, { ScrollTrigger }, { SplitText }, { CustomEase }] = await Promise.all([
      import('gsap'),
      import('gsap/ScrollTrigger'),
      import('gsap/SplitText'),
      import('gsap/CustomEase'),
    ]);
    gsap.registerPlugin(ScrollTrigger, SplitText, CustomEase);

    const style = getComputedStyle(this.document.documentElement);
    for (const name of EASES) {
      const bezier = /cubic-bezier\(([^)]+)\)/.exec(style.getPropertyValue(`--ease-${name}`));
      if (bezier) CustomEase.create(`ca-${name}`, bezier[1]);
    }
    for (const name of Object.keys(this.durations) as MotionDuration[]) {
      const value = style.getPropertyValue(`--duration-${name}`);
      this.durations[name] = cssTimeToSeconds(value, DEFAULT_DURATIONS[name]);
    }

    gsap.defaults({ ease: 'ca-out', duration: this.durations.slow });
    ScrollTrigger.config({ ignoreMobileResize: true });

    // Las imágenes y fuentes que terminan de cargar cambian alturas: hay que recalcular.
    this.document.addEventListener(
      'load',
      (event) => {
        if (event.target instanceof HTMLImageElement) this.refresh();
      },
      true,
    );
    void this.document.fonts?.ready.then(() => this.refresh());

    this.api = { gsap, ScrollTrigger, SplitText };
    return this.api;
  }
}
