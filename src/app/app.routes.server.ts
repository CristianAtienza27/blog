import { inject } from '@angular/core';
import { RenderMode, ServerRoute } from '@angular/ssr';
import { SNAPSHOT_MODE } from './content/content-mode.server';
import { ArticleRepository, MAX_PAGE_SIZE } from './content/repositories/article-repository';
import { SECTIONS, STATIC_PATHS } from './layout/navigation';

/**
 * Renderizado híbrido.
 * - Prerender: páginas estáticas, se generan como HTML en el build.
 * - Server: contenido dinámico (futuro backend Spring Boot), se renderiza en cada petición.
 * - Build de instantáneas (fase 6.3.2): la portada y los artículos, en el servidor, porque el
 *   build no lleva contenido con el que prerenderizarlos.
 */
export const serverRoutes: ServerRoute[] = [
  SNAPSHOT_MODE
    ? { path: '', renderMode: RenderMode.Server }
    : { path: '', renderMode: RenderMode.Prerender },
  ...STATIC_PATHS.map((path): ServerRoute => ({ path, renderMode: RenderMode.Prerender })),
  // Artículos existentes: prerenderizados. Un slug desconocido se renderiza en el servidor (404).
  ...SECTIONS.map((section): ServerRoute =>
    SNAPSHOT_MODE
      ? { path: `${section.path.slice(1)}/:slug`, renderMode: RenderMode.Server }
      : {
          path: `${section.path.slice(1)}/:slug`,
          renderMode: RenderMode.Prerender,
          async getPrerenderParams() {
            const articles = inject(ArticleRepository);
            const page = await articles.list({ category: section.id, limit: MAX_PAGE_SIZE });
            return page.items.map((article) => ({ slug: article.slug }));
          },
        },
  ),
  { path: '**', renderMode: RenderMode.Server },
];
