import { RESPONSE_INIT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { readFileSync } from 'node:fs';
import { NotFound } from './not-found';

describe('Página no encontrada', () => {
  it('en SSR responde con un 404 real', () => {
    const response: { status?: number } = {};
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: RESPONSE_INIT, useValue: response }],
    });
    TestBed.createComponent(NotFound);
    expect(response.status).toBe(404);
  });

  it('una sección con su h1 dentro del <main> de la aplicación (sin otro <main>)', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(NotFound);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('main')).toBeNull();
    const section = element.querySelector('section');
    const title = element.querySelector('h1');
    expect(section?.getAttribute('aria-labelledby')).toBe(title?.id);
    expect(title?.textContent).toBe('Página no encontrada');
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/');
    // La aplicación tiene un único <main>, donde se pinta la ruta (también la 404).
    expect(readFileSync('src/app/app.ts', 'utf8').match(/<main\b/g)).toHaveLength(1);
  });
});
