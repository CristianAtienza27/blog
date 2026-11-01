import { RenderMode, ServerRoute } from '@angular/ssr';

/**
 * Renderizado híbrido.
 * - Prerender: páginas estáticas, se generan como HTML en el build.
 * - Server: contenido dinámico (futuro backend Spring Boot), se renderiza en cada petición.
 */
export const serverRoutes: ServerRoute[] = [
  { path: '', renderMode: RenderMode.Prerender },
  { path: '**', renderMode: RenderMode.Server },
];
