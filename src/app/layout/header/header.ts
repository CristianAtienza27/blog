import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';
import { MenuState } from '../menu/menu-state';
import { SECTIONS } from '../navigation';

/**
 * Decide si el header debe ocultarse según el desplazamiento.
 * Se oculta al bajar y reaparece al subir; cerca del inicio siempre es visible.
 */
export function nextHeaderHidden(
  hidden: boolean,
  lastY: number,
  y: number,
  threshold = 120,
  tolerance = 6,
): boolean {
  if (y <= threshold) return false;
  const delta = y - lastY;
  if (delta > tolerance) return true;
  if (delta < -tolerance) return false;
  return hidden;
}

/**
 * Cabecera minimalista. Nunca se oculta con el menú abierto, con el foco dentro
 * ni mientras se navega con teclado (Tab), hasta el siguiente uso del puntero.
 */
@Component({
  selector: 'app-header',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.is-hidden]': 'hidden()',
    '[class.is-scrolled]': 'scrolled()',
    '(focusin)': 'focusWithin.set(true)',
    '(focusout)': 'focusWithin.set(false)',
  },
  templateUrl: './header.html',
  styleUrl: './header.scss',
})
export class Header {
  protected readonly menu = inject(MenuState);
  protected readonly sections = SECTIONS;

  protected readonly scrolled = signal(false);
  protected readonly focusWithin = signal(false);
  private readonly scrollHidden = signal(false);
  private readonly keyboardNav = signal(false);
  private readonly pinned = computed(
    () => this.menu.isOpen() || this.keyboardNav() || this.focusWithin(),
  );
  protected readonly hidden = computed(() => this.scrollHidden() && !this.pinned());

  constructor() {
    const document = inject(DOCUMENT);
    let cleanup: (() => void) | undefined;

    afterNextRender(() => {
      const view = document.defaultView;
      if (!view) return;
      let lastY = view.scrollY;
      let frame = 0;

      const update = () => {
        frame = 0;
        const y = view.scrollY;
        this.scrollHidden.set(nextHeaderHidden(this.scrollHidden(), lastY, y));
        this.scrolled.set(y > 8);
        lastY = y;
      };
      const onScroll = () => {
        frame ||= view.requestAnimationFrame(update);
      };
      const onKeydown = (event: KeyboardEvent) => {
        if (event.key === 'Tab') this.keyboardNav.set(true);
      };
      const onPointerdown = () => this.keyboardNav.set(false);

      view.addEventListener('scroll', onScroll, { passive: true });
      document.addEventListener('keydown', onKeydown);
      document.addEventListener('pointerdown', onPointerdown, { passive: true });
      update();

      cleanup = () => {
        view.removeEventListener('scroll', onScroll);
        document.removeEventListener('keydown', onKeydown);
        document.removeEventListener('pointerdown', onPointerdown);
        if (frame) view.cancelAnimationFrame(frame);
      };
    });

    inject(DestroyRef).onDestroy(() => cleanup?.());

    // Tras cada navegación, el header vuelve a mostrarse.
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.scrollHidden.set(false));
  }
}
