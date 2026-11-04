import { Route, Routes } from '@angular/router';
import { SeoData } from './core/seo/seo';
import {
  articleResolver,
  articleSeoResolver,
  articleTitleResolver,
} from './features/article/article.resolver';
import { homeResolver } from './features/home/home.resolver';
import { NavPage, NavSection, SECONDARY_PAGES, SECTIONS } from './layout/navigation';

const loadPlaceholder = () =>
  import('./features/placeholder/placeholder').then((m) => m.Placeholder);

/** Ruta provisional: noindex hasta que tenga contenido definitivo. */
function placeholderRoute(page: NavPage | NavSection, title?: string): Route {
  const section = 'id' in page ? page : undefined;
  const seo: SeoData = { description: page.lead, noindex: true };
  return {
    path: page.path.slice(1),
    title,
    loadComponent: loadPlaceholder,
    data: {
      seo,
      section: section?.id,
      index: section?.index,
      label: page.label,
      heading: page.heading,
      emphasis: page.emphasis,
      lead: page.lead,
      tone: page.tone,
    },
  };
}

const loadArticlePreview = () =>
  import('./features/article/article-preview').then((m) => m.ArticlePreview);

/**
 * Artículo de una sección: /<sección>/<slug>. Provisional (título, entradilla y aviso de
 * demostración) hasta la página de artículo definitiva. Slug inexistente o de otra sección: 404.
 */
function articleRoute(section: NavSection): Route {
  return {
    path: `${section.path.slice(1)}/:slug`,
    loadComponent: loadArticlePreview,
    title: articleTitleResolver,
    resolve: { article: articleResolver, seo: articleSeoResolver },
    data: { section: section.id },
  };
}

/**
 * Rutas solo de desarrollo. En desarrollo ngDevMode aún no existe al evaluar este módulo
 * (undefined = desarrollo). En producción el builder lo sustituye por false,
 * el bloque se elimina del bundle y /_styleguide responde 404.
 */
const devRoutes: Routes =
  typeof ngDevMode === 'undefined' || ngDevMode
    ? [
        {
          path: '_styleguide',
          title: 'Sistema de diseño',
          data: { seo: { noindex: true } satisfies SeoData },
          loadComponent: () => import('./features/styleguide/styleguide').then((m) => m.Styleguide),
        },
      ]
    : [];

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
    resolve: { home: homeResolver },
    // Contenido de demostración: sin indexar hasta que sea definitivo.
    data: { seo: { noindex: true } satisfies SeoData },
  },
  ...SECTIONS.map((section) => placeholderRoute(section, section.label)),
  ...SECTIONS.map(articleRoute),
  ...SECONDARY_PAGES.map((page) => placeholderRoute(page, page.label)),
  ...devRoutes,
  {
    path: '**',
    title: 'Página no encontrada',
    data: { seo: { noindex: true } satisfies SeoData },
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
