import { ChangeDetectionStrategy, Component, DOCUMENT, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Footer } from './layout/footer/footer';
import { Header } from './layout/header/header';
import { Menu } from './layout/menu/menu';

/** Estructura común de todas las páginas. */
@Component({
  selector: 'app-root',
  imports: [Footer, Header, Menu, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="skip-link" href="#contenido" (click)="skipToContent($event)">Saltar al contenido</a>
    <app-header />
    <main id="contenido" tabindex="-1">
      <router-outlet />
    </main>
    <app-footer />
    <app-menu />
  `,
  styleUrl: './app.scss',
})
export class App {
  private readonly document = inject(DOCUMENT);

  /** Con <base href="/"> un enlace "#id" navegaría a la portada: se enfoca el contenido a mano. */
  protected skipToContent(event: Event): void {
    event.preventDefault();
    this.document.getElementById('contenido')?.focus();
  }
}
