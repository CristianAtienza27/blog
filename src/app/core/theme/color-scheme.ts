import { isPlatformBrowser } from '@angular/common';
import { DOCUMENT, Injectable, PLATFORM_ID, effect, inject, signal } from '@angular/core';

export type ColorScheme = 'system' | 'light' | 'dark';

/** Debe coincidir con el script inline de src/index.html (evita el parpadeo de tema). */
export const COLOR_SCHEME_STORAGE_KEY = 'ca-color-scheme';

/**
 * Preferencia de modo claro u oscuro del lector.
 * 'system' sigue a prefers-color-scheme; 'light' y 'dark' fuerzan el modo con data-theme en <html>.
 */
@Injectable({ providedIn: 'root' })
export class ColorSchemeService {
  private readonly document = inject(DOCUMENT);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly current = signal<ColorScheme>(this.readStored());

  readonly scheme = this.current.asReadonly();

  constructor() {
    effect(() => this.apply(this.current()));
  }

  set(scheme: ColorScheme): void {
    this.current.set(scheme);
    this.store(scheme);
  }

  private apply(scheme: ColorScheme): void {
    if (!this.isBrowser) return;
    const root = this.document.documentElement;
    if (scheme === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', scheme);
    }
  }

  private readStored(): ColorScheme {
    if (!this.isBrowser) return 'system';
    try {
      const value = localStorage.getItem(COLOR_SCHEME_STORAGE_KEY);
      return value === 'light' || value === 'dark' ? value : 'system';
    } catch {
      return 'system';
    }
  }

  private store(scheme: ColorScheme): void {
    if (!this.isBrowser) return;
    try {
      if (scheme === 'system') {
        localStorage.removeItem(COLOR_SCHEME_STORAGE_KEY);
      } else {
        localStorage.setItem(COLOR_SCHEME_STORAGE_KEY, scheme);
      }
    } catch {
      // Almacenamiento no disponible (modo privado): la preferencia dura solo esta visita.
    }
  }
}
