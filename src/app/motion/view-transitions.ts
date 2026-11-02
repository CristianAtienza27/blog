import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, ViewTransitionInfo, withViewTransitions } from '@angular/router';
import { MotionService } from './motion';

/**
 * Transiciones entre páginas con la View Transitions API (las animaciones viven en
 * styles/motion/_motion.scss). Navegadores sin soporte: cambio instantáneo.
 * - Se omite en la primera carga, con reduced motion y si solo cambia el fragmento o la query.
 * - Al terminar se recalculan los ScrollTriggers de la página nueva.
 */
export function withPageTransitions() {
  return withViewTransitions({
    skipInitialTransition: true,
    onViewTransitionCreated: handleTransition,
  });
}

/** Ruta completa (sin query ni fragmento) a partir del snapshot raíz. */
function pathOf(root: ActivatedRouteSnapshot): string {
  const segments: string[] = [];
  for (let route: ActivatedRouteSnapshot | null = root; route; route = route.firstChild) {
    segments.push(...route.url.map((segment) => segment.path));
  }
  return '/' + segments.join('/');
}

function handleTransition({ transition, from, to }: ViewTransitionInfo): void {
  const motion = inject(MotionService);

  if (motion.reducedMotion() || pathOf(from) === pathOf(to)) {
    transition.skipTransition();
    return;
  }
  void transition.finished.then(() => motion.refresh());
}
