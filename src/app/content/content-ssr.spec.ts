import {
  ApplicationRef,
  Component,
  TransferState,
  inject,
  makeStateKey,
  resource,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideServerRendering, renderApplication } from '@angular/platform-server';
import { ContentCache, cacheKey } from './content-cache';
import { ArticleRepository } from './repositories/article-repository';
import { TravelRepository } from './repositories/travel-repository';
import { fixtureSource } from './testing/fixtures';
import { provideFixtureContent } from './testing/provide-fixture-content';

@Component({
  selector: 'app-root',
  template: `
    @if (article.value(); as current) {
      <h1>{{ current.title }}</h1>
      <p class="stops">{{ stops.value()?.join(' → ') }}</p>
    }
  `,
})
class ServerPage {
  private readonly articles = inject(ArticleRepository);
  private readonly travel = inject(TravelRepository);
  readonly article = resource({ loader: () => this.articles.bySlug('paso-stelvio') });
  readonly stops = resource({
    loader: async () =>
      (await this.travel.trip('alpes'))?.stops.map((stop) => stop.destination.name) ?? [],
  });
}

describe('Capa de contenido en SSR', () => {
  it('renderiza en el servidor y transfiere los datos al navegador', async () => {
    const { source } = fixtureSource();
    const html = await renderApplication(
      (context) =>
        bootstrapApplication(
          ServerPage,
          { providers: [provideServerRendering(), ...provideFixtureContent(source)] },
          context,
        ),
      { document: '<html><head></head><body><app-root></app-root></body></html>', url: '/' },
    );

    expect(html).toContain('<h1>Paso del Stelvio en moto</h1>');
    expect(html).toContain('Prato allo Stelvio → Paso del Stelvio');

    const state = html.match(/<script id="ng-state" type="application\/json">(.*?)<\/script>/s);
    expect(state).not.toBeNull();
    const transferred = JSON.parse(state?.[1] ?? '{}') as Record<string, unknown>;
    expect(Object.keys(transferred)).toEqual(
      expect.arrayContaining([
        'content:articles.bySlug:[["slug","paso-stelvio"]]',
        'content:travel.trip:[["slug","alpes"]]',
      ]),
    );
  });
});

describe('ContentCache en el navegador', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideFixtureContent(fixtureSource().source) });
  });

  it('durante la hidratación reutiliza lo que envió el servidor, sin volver a cargar', async () => {
    const key = cacheKey('articles.bySlug', { slug: 'paso-stelvio' });
    TestBed.inject(TransferState).set(makeStateKey<unknown>(`content:${key}`), {
      title: 'Del servidor',
    });
    const loader = vi.fn(async () => ({ title: 'Del navegador' }));

    const cache = TestBed.inject(ContentCache);
    expect(await cache.get(key, loader)).toEqual({ title: 'Del servidor' });
    expect(loader).not.toHaveBeenCalled();
  });

  it('una vez hidratada la aplicación, las consultas vuelven a cargarse', async () => {
    const key = cacheKey('x');
    TestBed.inject(TransferState).set(makeStateKey<unknown>(`content:${key}`), 'antiguo');
    const cache = TestBed.inject(ContentCache);
    await TestBed.inject(ApplicationRef).whenStable();
    await Promise.resolve();
    expect(await cache.get(key, async () => 'nuevo')).toBe('nuevo');
  });

  it('en el navegador no escribe en TransferState', async () => {
    const cache = TestBed.inject(ContentCache);
    await cache.get('solo-navegador', async () => 1);
    expect(TestBed.inject(TransferState).hasKey(makeStateKey('content:solo-navegador'))).toBe(
      false,
    );
  });

  it('las claves no dependen del orden de los parámetros ni de los indefinidos', () => {
    expect(cacheKey('a', { b: 1, a: 2, c: undefined })).toBe(cacheKey('a', { a: 2, b: 1 }));
    expect(cacheKey('a', {})).toBe('a');
  });
});
