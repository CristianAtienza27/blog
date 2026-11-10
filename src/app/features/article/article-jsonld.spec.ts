import type { Article, Media } from '../../content/models';
import { photo, summary } from '../../editorial/testing/editorial-fixtures';
import { articleJsonLd, isRealDate } from './article-jsonld';
import { articlePresentation } from './article-presentation';
import { ARTICLE_PAGE_COMPLETE, articleSeo } from './article-seo';

const SITE = 'https://revista.example';

/** Artículo real (no de demostración), con portada y fechas reales. */
function real(overrides: Partial<Article> = {}): Article {
  return {
    ...summary('calas-de-mallorca', {
      title: 'Calas de Mallorca',
      dek: 'Roca caliza y agua transparente.',
      tags: [
        { id: 'costa', slug: 'costa', label: 'Costa' },
        { id: 'mediterraneo', slug: 'mediterraneo', label: 'Mediterráneo' },
      ],
      cover: photo('viajes-mallorca-almunia', { width: 2400, height: 1600 }),
      publishedAt: '2026-07-21',
      demo: false,
    }),
    blocks: [],
    ...overrides,
  };
}

const graph = (ld: Record<string, unknown> | null) =>
  (ld?.['@graph'] ?? []) as Record<string, unknown>[];

describe('JSON-LD de artículo: cuándo se genera', () => {
  it('artículo real con dominio, fecha y autor: BlogPosting y BreadcrumbList', () => {
    const ld = articleJsonLd(real(), { siteUrl: SITE, image: '/media/viajes/almunia-1600.webp' });
    expect(ld?.['@context']).toBe('https://schema.org');
    const [post, crumbs] = graph(ld);
    expect(post).toEqual({
      '@type': 'BlogPosting',
      '@id': `${SITE}/viajes/calas-de-mallorca#articulo`,
      headline: 'Calas de Mallorca',
      description: 'Roca caliza y agua transparente.',
      author: { '@type': 'Person', name: 'Autoría de prueba' },
      datePublished: '2026-07-21',
      image: `${SITE}/media/viajes/almunia-1600.webp`,
      mainEntityOfPage: { '@type': 'WebPage', '@id': `${SITE}/viajes/calas-de-mallorca` },
      url: `${SITE}/viajes/calas-de-mallorca`,
      articleSection: 'Viajes',
      keywords: ['Costa', 'Mediterráneo'],
    });
    expect(crumbs).toEqual({
      '@type': 'BreadcrumbList',
      itemListElement: [
        { '@type': 'ListItem', position: 1, name: 'Inicio', item: `${SITE}/` },
        { '@type': 'ListItem', position: 2, name: 'Viajes', item: `${SITE}/viajes` },
        {
          '@type': 'ListItem',
          position: 3,
          name: 'Calas de Mallorca',
          item: `${SITE}/viajes/calas-de-mallorca`,
        },
      ],
    });
  });

  it('nunca para contenido de demostración', () => {
    expect(articleJsonLd(real({ demo: true }), { siteUrl: SITE })).toBeNull();
  });

  it('nunca sin dominio público (PUBLIC_SITE_URL)', () => {
    expect(articleJsonLd(real(), { siteUrl: null })).toBeNull();
    expect(articleJsonLd(real(), { siteUrl: '' })).toBeNull();
  });

  it('nunca sin fecha real de publicación', () => {
    expect(isRealDate('2026-07-21')).toBe(true);
    for (const date of ['', '2026-02-30', '21/07/2026', '2026-7-21', 'mañana']) {
      expect(isRealDate(date), date).toBe(false);
      expect(articleJsonLd(real({ publishedAt: date }), { siteUrl: SITE }), date).toBeNull();
    }
  });

  it('nunca sin autor del catálogo', () => {
    const author = { id: '', name: '', role: '' };
    expect(articleJsonLd(real({ author }), { siteUrl: SITE })).toBeNull();
  });

  it('sin canónica válida (dominio mal formado), nada', () => {
    expect(articleJsonLd(real(), { siteUrl: 'no es un dominio' })).toBeNull();
  });

  it('artículo real sin portada: sin imagen (no se inventa)', () => {
    const [post] = graph(articleJsonLd(real({ cover: undefined }), { siteUrl: SITE }));
    expect(post).not.toHaveProperty('image');
    expect(post['datePublished']).toBe('2026-07-21');
  });

  it('imagen ya absoluta (Azure Blob Storage) tal cual; fecha de actualización solo si es real', () => {
    const azure = 'https://cuenta.blob.core.windows.net/media/viajes/almunia-1600.webp';
    const [post] = graph(
      articleJsonLd(real({ updatedAt: '2026-08-02' }), { siteUrl: SITE, image: azure }),
    );
    expect(post['image']).toBe(azure);
    expect(post['dateModified']).toBe('2026-08-02');
    const [same] = graph(articleJsonLd(real({ updatedAt: '2026-07-21' }), { siteUrl: SITE }));
    expect(same).not.toHaveProperty('dateModified');
  });

  it('sin etiquetas, sin keywords', () => {
    const [post] = graph(articleJsonLd(real({ tags: [] }), { siteUrl: SITE }));
    expect(post).not.toHaveProperty('keywords');
  });
});

describe('SEO del artículo (articleSeo) con la página completa', () => {
  it('ARTICLE_PAGE_COMPLETE: la página de artículo está terminada', () => {
    expect(ARTICLE_PAGE_COMPLETE).toBe(true);
  });

  it('demostración: noindex y sin JSON-LD, aunque haya dominio', () => {
    const article = real({ demo: true });
    const seo = articleSeo(article, articlePresentation(article).header, '/media', SITE);
    expect(seo.noindex).toBe(true);
    expect(seo.jsonLd).toBeUndefined();
    expect(seo.article?.publishedTime).toBeUndefined();
  });

  it('real: indexable, con fecha, imagen social y JSON-LD con la misma imagen', () => {
    const article = real();
    const seo = articleSeo(article, articlePresentation(article).header, '/media', SITE);
    expect(seo.noindex).toBe(false);
    expect(seo.article?.publishedTime).toBe('2026-07-21');
    expect(seo.image).toMatch(/^\/media\/viajes\/viajes-mallorca-almunia.*-1600\.webp$/);
    expect(graph(seo.jsonLd ?? null)[0]['image']).toBe(SITE + seo.image);
  });

  it('real sin dominio: indexable pero sin JSON-LD (sin URL canónica no hay datos estructurados)', () => {
    const article = real();
    const seo = articleSeo(article, articlePresentation(article).header, '/media', null);
    expect(seo.noindex).toBe(false);
    expect(seo.jsonLd).toBeUndefined();
  });

  it('real sin portada: sin imagen social ni en el JSON-LD', () => {
    const article = real({ cover: undefined as Media | undefined });
    const seo = articleSeo(article, articlePresentation(article).header, '/media', SITE);
    expect(seo.image).toBeUndefined();
    expect(graph(seo.jsonLd ?? null)[0]).not.toHaveProperty('image');
  });
});
