import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MenuState } from '../menu/menu-state';
import { Header, nextHeaderHidden } from './header';

describe('nextHeaderHidden', () => {
  it('siempre visible cerca del inicio', () => {
    expect(nextHeaderHidden(true, 0, 50)).toBe(false);
  });

  it('se oculta al bajar y reaparece al subir', () => {
    expect(nextHeaderHidden(false, 400, 500)).toBe(true);
    expect(nextHeaderHidden(true, 500, 420)).toBe(false);
  });

  it('ignora movimientos mínimos', () => {
    expect(nextHeaderHidden(true, 500, 503)).toBe(true);
    expect(nextHeaderHidden(false, 500, 497)).toBe(false);
  });
});

describe('Header', () => {
  it('muestra las cinco secciones y abre el menú', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    const links = [...element.querySelectorAll('.header__nav a')].map((a) => a.textContent?.trim());
    expect(links).toEqual(['Viajes', 'Fotografía', 'Motor', 'Experiencias', 'Tecnología']);

    const button = element.querySelector<HTMLButtonElement>('.header__menu');
    expect(button?.getAttribute('aria-expanded')).toBe('false');
    button?.click();
    await fixture.whenStable();
    expect(TestBed.inject(MenuState).isOpen()).toBe(true);
    expect(button?.getAttribute('aria-expanded')).toBe('true');
  });
});
