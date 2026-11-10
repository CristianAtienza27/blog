import { ChangeDetectionStrategy, Component, RESPONSE_INIT, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Va dentro del <main> de la aplicación (app.ts): una sección, no otro <main>.
  template: `
    <section aria-labelledby="not-found-title">
      <h1 id="not-found-title">Página no encontrada</h1>
      <p><a routerLink="/">Volver a la portada</a></p>
    </section>
  `,
})
export class NotFound {
  constructor() {
    // En SSR devuelve un 404 real para que los buscadores no indexen la página.
    const response = inject(RESPONSE_INIT, { optional: true });
    if (response) {
      response.status = 404;
    }
  }
}
