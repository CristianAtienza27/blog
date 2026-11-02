import { Component, DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TitleStrategy, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { PUBLIC_SITE_URL } from '../config/site-config';
import { DEFAULT_DESCRIPTION, SeoService } from './seo';
import { SeoTitleStrategy } from './seo-title-strategy';

@Component({ template: '' })
class Blank {}

function head() {
  const document = TestBed.inject(DOCUMENT);
  return {
    title: document.title,
    meta: (key: string) =>
      document.head
        .querySelector(`meta[name="${key}"], meta[property="${key}"]`)
        ?.getAttribute('content') ?? null,
    canonical: document.head.querySelector('link[rel="canonical"]')?.getAttribute('href') ?? null,
  };
}

describe('SeoService', () => {
  it('sin dominio público no genera canónica ni og:url', () => {
    TestBed.inject(SeoService).update({ title: 'Viajes', description: 'Relatos.' }, '/viajes');
    const tags = head();
    expect(tags.title).toBe('Viajes · Cristian Atienza');
    expect(tags.meta('description')).toBe('Relatos.');
    expect(tags.meta('og:title')).toBe('Viajes · Cristian Atienza');
    expect(tags.meta('og:locale')).toBe('es_ES');
    expect(tags.meta('og:url')).toBeNull();
    expect(tags.canonical).toBeNull();
  });

  it('con dominio público genera canónica y og:url absolutas', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: PUBLIC_SITE_URL, useValue: 'https://dominio.test' }],
    });
    TestBed.inject(SeoService).update({}, '/viajes?pagina=2#mapa');
    const tags = head();
    expect(tags.title).toBe('Cristian Atienza');
    expect(tags.meta('description')).toBe(DEFAULT_DESCRIPTION);
    expect(tags.canonical).toBe('https://dominio.test/viajes');
    expect(tags.meta('og:url')).toBe('https://dominio.test/viajes');
    expect(tags.meta('robots')).toBeNull();
  });

  it('noindex añade robots y elimina la canónica', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: PUBLIC_SITE_URL, useValue: 'https://dominio.test' }],
    });
    const seo = TestBed.inject(SeoService);
    seo.update({ title: 'Archivo' }, '/archivo');
    seo.update({ title: 'Archivo', noindex: true }, '/archivo');
    const tags = head();
    expect(tags.meta('robots')).toBe('noindex, follow');
    expect(tags.canonical).toBeNull();
  });
});

describe('SeoTitleStrategy', () => {
  it('aplica título y datos SEO de la ruta en cada navegación', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'motor',
            title: 'Motor',
            component: Blank,
            data: { seo: { description: 'Curvas.', noindex: true } },
          },
          { path: '', component: Blank },
        ]),
        { provide: TitleStrategy, useClass: SeoTitleStrategy },
      ],
    });
    const harness = await RouterTestingHarness.create();

    await harness.navigateByUrl('/motor');
    expect(head().title).toBe('Motor · Cristian Atienza');
    expect(head().meta('description')).toBe('Curvas.');
    expect(head().meta('robots')).toBe('noindex, follow');

    await harness.navigateByUrl('/');
    expect(head().title).toBe('Cristian Atienza');
    expect(head().meta('robots')).toBeNull();
  });
});
