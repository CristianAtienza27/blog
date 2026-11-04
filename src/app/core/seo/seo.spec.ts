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

  it('artículo: imagen con medidas y texto alternativo, sección y etiquetas', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: PUBLIC_SITE_URL, useValue: 'https://dominio.test' }],
    });
    const seo = TestBed.inject(SeoService);
    seo.update(
      {
        title: 'Calas',
        type: 'article',
        image: '/media/viajes/cala-1600.webp',
        imageAlt: 'Una cala',
        imageWidth: 1600,
        imageHeight: 1067,
        article: { section: 'Viajes', tags: ['Costa', 'Mediterráneo'] },
      },
      '/viajes/calas',
    );
    const tags = head();
    expect(tags.meta('og:type')).toBe('article');
    expect(tags.meta('og:image')).toBe('https://dominio.test/media/viajes/cala-1600.webp');
    expect(tags.meta('og:image:alt')).toBe('Una cala');
    expect(tags.meta('og:image:width')).toBe('1600');
    expect(tags.meta('og:image:height')).toBe('1067');
    expect(tags.meta('twitter:card')).toBe('summary_large_image');
    expect(tags.meta('twitter:image:alt')).toBe('Una cala');
    expect(tags.meta('article:section')).toBe('Viajes');
    expect(tags.meta('article:published_time')).toBeNull();
    const document = TestBed.inject(DOCUMENT);
    const articleTags = () =>
      [...document.head.querySelectorAll('meta[property="article:tag"]')].map((meta) =>
        meta.getAttribute('content'),
      );
    expect(articleTags()).toEqual(['Costa', 'Mediterráneo']);

    // La página siguiente no hereda nada del artículo.
    seo.update({ title: 'Viajes' }, '/viajes');
    expect(articleTags()).toEqual([]);
    expect(head().meta('article:section')).toBeNull();
    expect(head().meta('og:image:alt')).toBeNull();
    expect(head().meta('og:image:width')).toBeNull();
  });

  it('sin dominio público ni URL absoluta no hay imagen social (ni sus medidas)', () => {
    TestBed.inject(SeoService).update(
      { image: '/media/a-1600.webp', imageAlt: 'A', imageWidth: 1600, imageHeight: 900 },
      '/a',
    );
    expect(head().meta('og:image')).toBeNull();
    expect(head().meta('og:image:width')).toBeNull();
    expect(head().meta('twitter:card')).toBe('summary');
  });

  it('una imagen ya absoluta (Azure Blob Storage o CDN) se usa tal cual', () => {
    TestBed.inject(SeoService).update(
      { image: 'https://cdn.test/media/a-1600.webp', imageWidth: 1600, imageHeight: 900 },
      '/a',
    );
    expect(head().meta('og:image')).toBe('https://cdn.test/media/a-1600.webp');
    expect(head().meta('og:image:height')).toBe('900');
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
