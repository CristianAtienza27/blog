import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  PLATFORM_ID,
  computed,
  effect,
  resource,
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
import type { Media as MediaAsset } from '../../content/models';
import { MediaRepository } from '../../content/repositories/media-repository';
import { MenuAnimator } from '../../motion/menu-animator';
import { Link } from '../../shared/ui/link/link';
import { Media } from '../../shared/ui/media/media';
import { creditLine } from '../../shared/ui/media/media-credit';
import { ThemeToggle } from '../../shared/ui/theme-toggle/theme-toggle';
import { NavSection, SECONDARY_PAGES, SECTIONS, SectionId } from '../navigation';
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

  /**
   * Fotografías de vista previa: se piden al repositorio la primera vez que se abre
   * el menú (nunca en el servidor ni en la carga inicial).
   */
  private readonly mediaRepository = inject(MediaRepository);
  private readonly requested = signal(false);
  private readonly previews = resource({
    params: () => (this.requested() ? SECTIONS.map((section) => section.preview) : undefined),
    loader: ({ params }) => this.mediaRepository.mediaList(params),
  });
  protected readonly activeCredit = computed(() => {
    const media = this.mediaFor(this.previewSection());
    return media ? creditLine(media.credit) : null;
  });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly router = inject(Router);
  private readonly animator = inject(MenuAnimator);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  private closeImmediately = false;
  /** Contador de interacciones: cada apertura o cierre invalida las anteriores. */
  private run = 0;

  constructor() {
    effect(() => {
      const open = this.state.isOpen();
      untracked(() => void (open ? this.openDialog() : this.closeDialog()));
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

  protected mediaFor(section: NavSection): MediaAsset | undefined {
    return this.previews.value()?.find((media) => media.id === section.preview);
  }

  protected pad(index: number): string {
    return String(index).padStart(2, '0');
  }

  /**
   * Apertura: primero se fijan los estados iniciales y solo después se muestra el diálogo,
   * de modo que el primer fotograma visible ya es el de partida. Si el menú se estaba
   * cerrando, la animación de apertura continúa desde donde estaba.
   */
  private async openDialog(): Promise<void> {
    if (!this.isBrowser) return;
    const dialog = this.dialog().nativeElement;
    const run = ++this.run;
    this.requested.set(true);

    if (!dialog.open) {
      await this.animator.prepare(dialog);
      // Mientras se preparaba, el menú se cerró o una apertura posterior tomó el relevo.
      if (run !== this.run || !this.state.isOpen()) {
        if (!this.state.isOpen()) this.animator.reset(dialog);
        return;
      }
      dialog.showModal();
    }
    this.animator.open(dialog);
  }

  private async closeDialog(): Promise<void> {
    if (!this.isBrowser) return;
    const dialog = this.dialog().nativeElement;
    const run = ++this.run;
    if (!dialog.open) {
      // Se cerró antes de llegar a mostrarse: se descartan los estados preparados.
      this.animator.reset(dialog);
      return;
    }

    const immediate = this.closeImmediately;
    this.closeImmediately = false;

    if (!immediate) {
      const finished = await this.animator.close(dialog);
      // Si se volvió a abrir durante la animación, la apertura ya la ha interrumpido.
      if (!finished || run !== this.run) return;
    }
    dialog.close();
    // Tras cerrar (nunca antes, para no mostrar un fotograma suelto) se limpian los estilos.
    this.animator.reset(dialog);
  }
}
