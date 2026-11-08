import { Component, signal } from '@angular/core';
import { ViewportScroller } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { routes } from '../../app.routes';
import { serverRoutes } from '../../app.routes.server';
import type { Article, Media } from '../../content/models';
import { ArticleRepository } from '../../content/repositories/article-repository';
import { provideMediaDelivery } from '../../core/media/media-delivery';
import type { SeoData } from '../../core/seo/seo';
import { destination, photo, ref, summary } from '../../editorial/testing/editorial-fixtures';
import { SECTIONS } from '../../layout/navigation';
import { MotionService } from '../../motion/motion';
import { ArticlePage } from './article-page';
import { ArticlePageData, articlePresentation } from './article-presentation';
import { articleSeo } from './article-seo';
import {
  NOT_FOUND_TITLE,
  articleResolver,
  articleSeoResolver,
  articleTitleResolver,
} from './article.resolver';

const CALA = destination('cala-s-almunia', "Cala s'Almunia", { region: 'Mallorca' });
const CALAS: Article & { cover: Media } = {
  ...summary('calas-de-mallorca', {
    title: 'Calas de Mallorca',
    dek: 'Roca caliza y agua transparente.',
    excerpt: 'Guía de demostración sobre las calas.',
    tags: [{ id: 'costa', slug: 'costa', label: 'Costa' }],
    cover: photo('viajes-mallorca-almunia'),
    destinations: [ref(CALA)],
    publishedAt: '2026-07-21',
  }),
  blocks: [{ type: 'text', html: '<p>Texto.</p>' }],
};

/** Ruta /<sección>/<slug> simulada con un repositorio que solo conoce un artículo. */
function resolve<T>(
  resolver: (route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => unknown,
  section: string,
  slug: string,
): Promise<T> {
  const repository = { bySlug: vi.fn(async (s: string) => (s === CALAS.slug ? CALAS : null)) };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: ArticleRepository, useValue: repository }],
  });
  const route = { paramMap: convertToParamMap({ slug }), data: { section } };
  return TestBed.runInInjectionContext(
    () =>
      resolver(route as unknown as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as Promise<T>,
  );
}

describe('Rutas de artículo', () => {
  it('cada sección tiene /<sección>/:slug con la página de artículo, prerenderizada', () => {
    for (const section of SECTIONS) {
      const path = `${section.path.slice(1)}/:slug`;
      const route = routes.find((candidate) => candidate.path === path);
      expect(route?.data?.['section'], path).toBe(section.id);
      expect(Object.keys(route?.resolve ?? {})).toEqual(['page', 'seo', 'closing']);
      const server = serverRoutes.find((candidate) => candidate.path === path);
      expect(server && 'getPrerenderParams' in server, path).toBe(true);
    }
  });

  it('resuelve el artículo completo (con su cuerpo) y la cabecera de su formato', async () => {
    const page = await resolve<ArticlePageData>(articleResolver, 'viajes', CALAS.slug);
    expect(page.article).toBe(CALAS);
    expect(page.article.blocks).toHaveLength(1);
    expect(page.header.variant).toBe('split');
    expect(page.header.title).toBe(CALAS.title);
    expect(await resolve(articleTitleResolver, 'viajes', CALAS.slug)).toBe(CALAS.title);
  });

  it('SEO: Open Graph de artículo con imagen, medidas y texto alternativo; sin fechas demo', async () => {
    const seo = await resolve<SeoData>(articleSeoResolver, 'viajes', CALAS.slug);
    expect(seo).toEqual({
      description: CALAS.dek,
      type: 'article',
      noindex: true,
      image: `/media/${CALAS.cover.path}-1600.webp`,
      imageAlt: CALAS.cover.alt,
      imageWidth: 1600,
      imageHeight: 1067,
      article: { section: 'Viajes', tags: ['Costa'] },
    });
  });

  it('un slug de otra sección o inexistente no resuelve nada (404) y no se indexa', async () => {
    expect(await resolve(articleResolver, 'motor', CALAS.slug)).toBeNull();
    expect(await resolve(articleResolver, 'viajes', 'no-existe')).toBeNull();
    expect(await resolve(articleTitleResolver, 'motor', CALAS.slug)).toBe(NOT_FOUND_TITLE);
    expect(await resolve(articleSeoResolver, 'viajes', 'no-existe')).toEqual({ noindex: true });
  });
});

@Component({
  imports: [ArticlePage],
  template: `<app-article-page [page]="page()" />`,
})
class Host {
  readonly page = signal<ArticlePageData | null>(null);
}

async function renderPage(page: ArticlePageData | null) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMediaDelivery(),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.page.set(page);
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    element.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  return { element, text };
}

describe('Página de artículo', () => {
  it('artículo con su sección, titular, entradilla, datos y aviso de demostración', async () => {
    const { element, text } = await renderPage(articlePresentation(CALAS));
    const article = element.querySelector('article');
    expect(article?.getAttribute('data-section')).toBe('viajes');
    expect(article?.getAttribute('aria-labelledby')).toBe('article-title');
    expect(element.querySelector('app-article-header')?.getAttribute('data-variant')).toBe('split');
    expect(text('h1#article-title')).toBe(CALAS.title);
    expect(text('app-eyebrow .index')).toBe('01');
    expect(text('app-eyebrow > span:last-child')).toBe('Viajes · Guía');
    expect(element.querySelector('app-eyebrow a')?.getAttribute('href')).toBe('/viajes');
    expect(text('.t-lead')).toBe(CALAS.dek);
    expect(text('app-article-facts dl')).toBe(
      "Lectura 2 min de lectura Lugar Cala s'Almunia · Mallorca · España",
    );
    expect(text('app-tag')).toBe('Contenido de demostración');
    // Sin fecha: es contenido de demostración.
    expect(element.querySelector('time')).toBeNull();
  });

  it('la foto de cabecera es la principal (prioridad) y lleva su crédito con licencia', async () => {
    const { element, text } = await renderPage(articlePresentation(CALAS));
    const img = element.querySelector('app-article-header img');
    expect(img?.getAttribute('fetchpriority')).toBe('high');
    expect(img?.getAttribute('loading')).not.toBe('lazy');
    expect(img?.getAttribute('alt')).toBe(CALAS.cover.alt);
    expect(img?.getAttribute('sizes')).toBe('(width < 900px) 188vw, 57vw');
    expect(text('app-photo-credit')).toBe(
      'Foto: Autoría de prueba · Wikimedia Commons · CC BY-SA 4.0 · adaptada',
    );
  });

  it('sin portada: cabecera compacta sin foto, crédito ni imagen social; el resto, igual', async () => {
    const page = articlePresentation({ ...CALAS, format: 'ficha', cover: undefined });
    expect(page.header).toMatchObject({ variant: 'compact', cover: null });
    const { element, text } = await renderPage(page);
    const header = element.querySelector('app-article-header');
    expect(header?.querySelector('app-header-compact h1')?.textContent).toContain('Calas');
    expect(header?.querySelectorAll('img, picture, app-media, app-photo-credit')).toHaveLength(0);
    expect(text('app-tag')).toBe('Contenido de demostración');
    expect(element.querySelector('app-article-body')).not.toBeNull();
    expect(articleSeo(page.article, page.header, '/media').image).toBeUndefined();
  });

  it('cada variante tiene su componente', async () => {
    const cases: [Partial<Article>, string][] = [
      [{ format: 'reportaje', cover: photo('a', { width: 2400, height: 1600 }) }, 'cinema'],
      [{ format: 'guia' }, 'split'],
      [{ format: 'ficha', cover: photo('b', { width: 2400, height: 1333 }) }, 'plate'],
      [{ format: 'nota' }, 'compact'],
    ];
    for (const [overrides, variant] of cases) {
      TestBed.resetTestingModule();
      const { element } = await renderPage(articlePresentation({ ...CALAS, ...overrides }));
      expect(element.querySelector(`app-header-${variant} h1`), variant).not.toBeNull();
    }
  });

  it('cine: el header del sitio pasa a transparente sobre la foto', async () => {
    const cinema = articlePresentation({
      ...CALAS,
      format: 'reportaje',
      cover: photo('c', { width: 2400, height: 1600 }),
    });
    const { element } = await renderPage(cinema);
    expect(element.querySelector('.cinema')?.hasAttribute('data-header-overlay')).toBe(true);
    expect(element.querySelector('.cinema')?.classList).toContain('scheme-dark');
  });

  it('contenido definitivo: la fecha aparece en <time>', async () => {
    const { element } = await renderPage(articlePresentation({ ...CALAS, demo: false }));
    expect(element.querySelector('time')?.getAttribute('datetime')).toBe('2026-07-21');
    expect(element.querySelector('app-tag')).toBeNull();
  });

  it('debajo de la cabecera, el cuerpo del artículo', async () => {
    const { text } = await renderPage(articlePresentation(CALAS));
    expect(text('article > app-article-body app-prose-block')).toBe('Texto.');
  });

  it('las anclas dejan libre la altura del header mientras se muestra el artículo', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideMediaDelivery(),
        { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
      ],
    });
    const scroller = TestBed.inject(ViewportScroller);
    const setOffset = vi.spyOn(scroller, 'setOffset');
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.page.set(articlePresentation(CALAS));
    await fixture.whenStable();
    const offset = setOffset.mock.calls[0][0] as () => [number, number];
    expect(offset()[1]).toBeGreaterThanOrEqual(24);
    fixture.destroy();
    expect(setOffset).toHaveBeenLastCalledWith([0, 0]);
  });

  it('sin artículo muestra la página de no encontrado', async () => {
    const { element } = await renderPage(null);
    expect(element.querySelector('app-not-found h1')?.textContent).toBe('Página no encontrada');
  });
});
