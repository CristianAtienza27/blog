import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Portada provisional. Se sustituye en la fase 4. */
@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main>
      <h1>Cristian Atienza</h1>
      <p>Revista personal de viajes, fotografía, motor, experiencias y tecnología.</p>
    </main>
  `,
})
export class Home {}
