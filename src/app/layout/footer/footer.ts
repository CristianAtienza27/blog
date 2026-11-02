import { ChangeDetectionStrategy, Component, DOCUMENT, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Divider } from '../../shared/ui/divider/divider';
import { Eyebrow } from '../../shared/ui/eyebrow/eyebrow';
import { Link } from '../../shared/ui/link/link';
import { ThemeToggle } from '../../shared/ui/theme-toggle/theme-toggle';
import { SECONDARY_PAGES, SECTIONS } from '../navigation';

/** Footer editorial, siempre en «modo cine» oscuro, como los créditos de una película. */
@Component({
  selector: 'app-footer',
  imports: [Divider, Eyebrow, Link, RouterLink, ThemeToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'scheme-dark' },
  templateUrl: './footer.html',
  styleUrl: './footer.scss',
})
export class Footer {
  protected readonly sections = SECTIONS;
  protected readonly pages = SECONDARY_PAGES;
  protected readonly year = new Date().getFullYear();
  private readonly document = inject(DOCUMENT);

  /** Vuelve al inicio y lleva el foco al contenido principal (útil con teclado). */
  protected scrollTop(event: Event): void {
    event.preventDefault();
    const view = this.document.defaultView;
    const reduce = view?.matchMedia('(prefers-reduced-motion: reduce)').matches;
    view?.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
    this.document.getElementById('contenido')?.focus({ preventScroll: true });
  }
}
