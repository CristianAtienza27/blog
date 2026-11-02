import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  PLATFORM_ID,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import { filter } from 'rxjs';
import { MenuAnimator } from '../../motion/menu-animator';
import { Link } from '../../shared/ui/link/link';
import { Media } from '../../shared/ui/media/media';
import { ThemeToggle } from '../../shared/ui/theme-toggle/theme-toggle';
import { SECONDARY_PAGES, SECTIONS, SectionId } from '../navigation';
import { MenuState } from './menu-state';

/**
 * Menú overlay a pantalla completa sobre <dialog> modal:
 * foco atrapado, fondo inerte, Esc para cerrar y foco devuelto al botón al cerrar.
 *
 * Cierre:
 * - Esc, botón o enlace a la página actual → animación de cierre.
 * - Navegación a otra página → cierre inmediato; la View Transition funde menú y página.
 */
@Component({
  selector: 'app-menu',
  imports: [Link, Media, RouterLink, RouterLinkActive, ThemeToggle],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './menu.html',
  styleUrl: './menu.scss',
})
export class Menu {
  protected readonly state = inject(MenuState);
  protected readonly sections = SECTIONS;
  protected readonly pages = SECONDARY_PAGES;
  protected readonly preview = signal<SectionId>(SECTIONS[0].id);
  protected readonly previewSection = computed(
    () => SECTIONS.find((section) => section.id === this.preview()) ?? SECTIONS[0],
  );

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly router = inject(Router);
  private readonly animator = inject(MenuAnimator);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  private closeImmediately = false;

  constructor() {
    effect(() => {
      const open = this.state.isOpen();
      untracked(() => (open ? this.openDialog() : this.closeDialog()));
    });

    this.router.events
      .pipe(
        filter(
          (event) =>
            event instanceof NavigationEnd ||
            event instanceof NavigationCancel ||
            event instanceof NavigationError,
        ),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        if (!this.state.isOpen()) return;
        this.closeImmediately = true;
        this.state.hide();
      });

    inject(DestroyRef).onDestroy(() => this.animator.dispose());
  }

  /** Esc: se sustituye el cierre nativo por el animado. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.state.hide();
  }

  /** Cierre nativo (por ejemplo, un segundo Esc): sincroniza el estado. */
  protected onClosed(): void {
    if (this.state.isOpen()) {
      this.closeImmediately = true;
      this.state.hide();
    }
  }

  /** Un enlace a la página actual no navega: hay que cerrar el menú a mano. */
  protected onLinkClick(path: string): void {
    const current = this.router.url.split(/[?#]/)[0];
    if (current === path) this.state.hide();
  }

  protected pad(index: number): string {
    return String(index).padStart(2, '0');
  }

  private openDialog(): void {
    if (!this.isBrowser) return;
    const dialog = this.dialog().nativeElement;
    if (dialog.open) return;
    dialog.showModal();
    void this.animator.open(dialog);
  }

  private async closeDialog(): Promise<void> {
    if (!this.isBrowser) return;
    const dialog = this.dialog().nativeElement;
    if (!dialog.open) return;

    const immediate = this.closeImmediately;
    this.closeImmediately = false;

    if (!immediate) {
      const finished = await this.animator.close(dialog);
      // Si se volvió a abrir durante la animación, no se cierra.
      if (!finished || this.state.isOpen()) return;
    }
    dialog.close();
    // Tras cerrar (nunca antes, para no mostrar un fotograma suelto) se limpian los estilos.
    this.animator.reset(dialog);
  }
}
