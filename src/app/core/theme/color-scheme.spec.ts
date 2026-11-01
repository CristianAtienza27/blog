import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/core';
import { COLOR_SCHEME_STORAGE_KEY, ColorSchemeService } from './color-scheme';

describe('ColorSchemeService', () => {
  let root: HTMLElement;

  beforeEach(() => {
    localStorage.clear();
    root = TestBed.inject(DOCUMENT).documentElement;
    root.removeAttribute('data-theme');
  });

  it('sigue al sistema por defecto', () => {
    const service = TestBed.inject(ColorSchemeService);
    TestBed.tick();
    expect(service.scheme()).toBe('system');
    expect(root.hasAttribute('data-theme')).toBe(false);
  });

  it('aplica y guarda un modo explícito', () => {
    const service = TestBed.inject(ColorSchemeService);
    service.set('dark');
    TestBed.tick();
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem(COLOR_SCHEME_STORAGE_KEY)).toBe('dark');
  });

  it('volver a sistema elimina el atributo y la preferencia', () => {
    const service = TestBed.inject(ColorSchemeService);
    service.set('light');
    TestBed.tick();
    service.set('system');
    TestBed.tick();
    expect(root.hasAttribute('data-theme')).toBe(false);
    expect(localStorage.getItem(COLOR_SCHEME_STORAGE_KEY)).toBeNull();
  });

  it('recupera la preferencia guardada', () => {
    localStorage.setItem(COLOR_SCHEME_STORAGE_KEY, 'light');
    const service = TestBed.inject(ColorSchemeService);
    expect(service.scheme()).toBe('light');
  });
});
