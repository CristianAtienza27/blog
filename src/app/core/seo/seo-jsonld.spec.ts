import { DOCUMENT, PLATFORM_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PUBLIC_SITE_URL } from '../config/site-config';
import { SeoService, serializeJsonLd } from './seo';

const SITE = 'https://revista.example';
/** Separadores de línea y de párrafo de Unicode (rompen un script si no se escapan). */
const LINE_SEPARATOR = String.fromCharCode(0x2028);
const PARAGRAPH_SEPARATOR = String.fromCharCode(0x2029);
const LD = { '@context': 'https://schema.org', '@type': 'BlogPosting', headline: 'Calas' };

function setup(platform: 'browser' | 'server' = 'browser') {
  TestBed.configureTestingModule({
    providers: [
      { provide: PUBLIC_SITE_URL, useValue: SITE },
      { provide: PLATFORM_ID, useValue: platform },
    ],
  });
  const seo = TestBed.inject(SeoService);
  const document = TestBed.inject(DOCUMENT);
  const scripts = () =>
    [...document.head.querySelectorAll('script[type="application/ld+json"]')].map(
      (s) => s.textContent ?? '',
    );
  const meta = (key: string) =>
    [...document.head.querySelectorAll(`meta[name="${key}"], meta[property="${key}"]`)].map((m) =>
      m.getAttribute('content'),
    );
  return { seo, document, scripts, meta };
}

afterEach(() => {
  TestBed.inject(DOCUMENT)
    .head.querySelectorAll('script[type="application/ld+json"], meta, link[rel="canonical"]')
    .forEach((n) => n.remove());
});

describe('JSON-LD en la cabeza de la página (SeoService)', () => {
  it('un solo script, que se actualiza (no se duplica) en cada página indexable', () => {
    const { seo, scripts } = setup();
    seo.update({ type: 'article', jsonLd: LD }, '/viajes/calas');
    seo.update({ type: 'article', jsonLd: { ...LD, headline: 'Otra' } }, '/viajes/otra');
    expect(scripts()).toHaveLength(1);
    expect(JSON.parse(scripts()[0])).toEqual({ ...LD, headline: 'Otra' });
  });

  it('se elimina al navegar a una página sin datos estructurados o con noindex', () => {
    const { seo, scripts } = setup();
    seo.update({ type: 'article', jsonLd: LD }, '/viajes/calas');
    seo.update({ title: 'Viajes' }, '/viajes');
    expect(scripts()).toEqual([]);
    seo.update({ type: 'article', jsonLd: LD }, '/viajes/calas');
    seo.update({ type: 'article', jsonLd: LD, noindex: true }, '/viajes/demo');
    expect(scripts()).toEqual([]);
  });

  it('elimina restos duplicados (por ejemplo, de una carga anterior)', () => {
    const { seo, document, scripts } = setup();
    for (let i = 0; i < 2; i++) {
      const extra = document.createElement('script');
      extra.setAttribute('type', 'application/ld+json');
      extra.setAttribute('data-seo', '');
      document.head.appendChild(extra);
    }
    seo.update({ type: 'article', jsonLd: LD }, '/viajes/calas');
    expect(scripts()).toHaveLength(1);
  });

  it('también en el servidor (SSR): el script sale en el HTML prerenderizado', () => {
    const { seo, document } = setup('server');
    seo.update({ type: 'article', jsonLd: LD }, '/viajes/calas');
    expect(document.head.innerHTML).toContain('application/ld+json');
  });
});

describe('Serialización segura del JSON-LD', () => {
  it('ningún texto puede cerrar el script ni abrir etiquetas; el JSON se lee igual', () => {
    const hostile = {
      headline: '</script><script>alert(1)</script> & <!-- -->',
      description: `línea${LINE_SEPARATOR}separada${PARAGRAPH_SEPARATOR}aquí`,
    };
    const text = serializeJsonLd(hostile);
    expect(text).not.toMatch(new RegExp(`[<>&${LINE_SEPARATOR}${PARAGRAPH_SEPARATOR}]`));
    expect(text).not.toContain('</script');
    expect(JSON.parse(text)).toEqual(hostile);
  });

  it('en la página: el contenido hostil queda dentro del mismo script', () => {
    const { seo, document, scripts } = setup();
    seo.update(
      { type: 'article', jsonLd: { headline: '</script><img src=x onerror=alert(1)>' } },
      '/viajes/calas',
    );
    expect(scripts()).toHaveLength(1);
    expect(document.head.querySelector('img')).toBeNull();
    expect(JSON.parse(scripts()[0]).headline).toBe('</script><img src=x onerror=alert(1)>');
  });
});

describe('Limpieza de metadatos al navegar', () => {
  it('de un artículo con imagen y JSON-LD a una sección: nada del artículo se conserva', () => {
    const { seo, scripts, meta } = setup();
    seo.update(
      {
        title: 'Calas',
        type: 'article',
        image: '/media/calas-1600.webp',
        imageAlt: 'Cala',
        imageWidth: 1600,
        imageHeight: 1067,
        article: {
          section: 'Viajes',
          tags: ['Costa', 'Mediterráneo'],
          publishedTime: '2026-07-21',
        },
        jsonLd: LD,
      },
      '/viajes/calas',
    );
    expect(meta('og:image')).toEqual([`${SITE}/media/calas-1600.webp`]);
    expect(meta('article:tag')).toHaveLength(2);

    seo.update({ title: 'Viajes' }, '/viajes');
    expect(meta('og:type')).toEqual(['website']);
    for (const key of [
      'og:image',
      'og:image:alt',
      'og:image:width',
      'twitter:image',
      'article:section',
      'article:tag',
      'article:published_time',
    ]) {
      expect(meta(key), key).toEqual([]);
    }
    expect(meta('twitter:card')).toEqual(['summary']);
    expect(scripts()).toEqual([]);
  });

  it('de un artículo con portada a uno sin portada: sin imagen social residual', () => {
    const { seo, meta } = setup();
    seo.update({ type: 'article', image: '/media/a-1600.webp', imageAlt: 'A' }, '/motor/a');
    seo.update({ type: 'article', noindex: true }, '/motor/sin-portada');
    expect(meta('og:image')).toEqual([]);
    expect(meta('twitter:image')).toEqual([]);
    expect(meta('robots')).toEqual(['noindex, follow']);
  });
});
