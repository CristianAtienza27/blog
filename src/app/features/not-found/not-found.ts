import { ChangeDetectionStrategy, Component, RESPONSE_INIT, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main>
      <h1>Página no encontrada</h1>
      <p><a routerLink="/">Volver a la portada</a></p>
    </main>
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
