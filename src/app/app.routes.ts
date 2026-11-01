import { Routes } from '@angular/router';

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
          title: 'Sistema de diseño · Cristian Atienza',
          loadComponent: () => import('./features/styleguide/styleguide').then((m) => m.Styleguide),
        },
      ]
    : [];

export const routes: Routes = [
  {
    path: '',
    title: 'Cristian Atienza',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  ...devRoutes,
  {
    path: '**',
    title: 'Página no encontrada · Cristian Atienza',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
