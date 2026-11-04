import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import { routes } from '../../app.routes';
import { serverRoutes } from '../../app.routes.server';
import { ArticleRepository } from '../../content/repositories/article-repository';
import { summary } from '../../editorial/testing/editorial-fixtures';
import { SECTIONS } from '../../layout/navigation';
import { ArticlePreview } from './article-preview';
import {
  ArticlePreviewData,
  NOT_FOUND_TITLE,
  articleResolver,
  articleSeoResolver,
  articleTitleResolver,
} from './article.resolver';

const CALAS = summary('calas-de-mallorca', {
  title: 'Calas de Mallorca',
  dek: 'Roca caliza y agua transparente.',
});

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

describe('Rutas provisionales de artículo', () => {
  it('cada sección tiene /<sección>/:slug, prerenderizada con sus artículos', () => {
    for (const section of SECTIONS) {
      const path = `${section.path.slice(1)}/:slug`;
      const route = routes.find((candidate) => candidate.path === path);
      expect(route?.data?.['section'], path).toBe(section.id);
      expect(Object.keys(route?.resolve ?? {})).toEqual(['article', 'seo']);
      const server = serverRoutes.find((candidate) => candidate.path === path);
      expect(server && 'getPrerenderParams' in server, path).toBe(true);
    }
  });

  it('resuelve el artículo de su sección: título, entradilla, categoría y aviso', async () => {
    expect(await resolve(articleResolver, 'viajes', CALAS.slug)).toEqual({
      title: CALAS.title,
      dek: CALAS.dek,
      category: CALAS.category,
      demo: true,
    });
    expect(await resolve(articleTitleResolver, 'viajes', CALAS.slug)).toBe(CALAS.title);
    expect(await resolve(articleSeoResolver, 'viajes', CALAS.slug)).toEqual({
      description: CALAS.dek,
      type: 'article',
      noindex: true,
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
  imports: [ArticlePreview],
  template: `<app-article-preview [article]="article()" />`,
})
class Host {
  readonly article = signal<ArticlePreviewData | null>(null);
}

async function renderPreview(article: ArticlePreviewData | null) {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.set(article);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Página provisional de artículo', () => {
  it('muestra título, entradilla, sección y la etiqueta de demostración', async () => {
    const element = await renderPreview({ ...CALAS, demo: true });
    expect(element.querySelector('h1')?.textContent?.trim()).toBe(CALAS.title);
    expect(element.querySelector('.t-lead')?.textContent?.trim()).toBe(CALAS.dek);
    expect(element.querySelector('app-eyebrow')?.textContent).toContain('Viajes');
    expect(element.querySelector('app-tag')?.textContent?.trim()).toBe('Contenido de demostración');
    expect(element.querySelector('a[app-link]')?.getAttribute('href')).toBe('/viajes');
  });

  it('sin artículo muestra la página de no encontrado', async () => {
    const element = await renderPreview(null);
    expect(element.querySelector('app-not-found h1')?.textContent).toBe('Página no encontrada');
  });
});
