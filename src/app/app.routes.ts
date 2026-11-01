import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'Cristian Atienza',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: '**',
    title: 'Página no encontrada · Cristian Atienza',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
