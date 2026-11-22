import { Component, REQUEST_CONTEXT, inject, resource } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideServerRendering, renderApplication } from '@angular/platform-server';
import { rmSync } from 'node:fs';
import { withJsonContent } from '../json/with-json-content';
import { provideContent } from '../provide-content';
import type { MediaRecord } from '../records/records';
import { ArticleRepository } from '../repositories/article-repository';
import { MediaRepository } from '../repositories/media-repository';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import { contentRequestContext, contentServerProviders } from './content-mode.server.snapshot';
import { privateValues } from './public-projection';
import { loadSnapshot, setCurrentSnapshot } from './snapshot-store';
import { tempRoot, withPrivateOriginals, writeSnapshot } from './snapshot-testing';

const MEDIA_IDS = fixtureCollections().media.map((m) => m.id);

@Component({
  selector: 'app-root',
  template: `
    @if (article.value(); as current) {
      <h1>{{ current.title }}</h1>
      <img [src]="current.cover?.path" [alt]="current.cover?.alt" />
    }
    <p class="media">{{ media.value()?.length }}</p>
  `,
})
class ServerPage {
  private readonly articles = inject(ArticleRepository);
  private readonly mediaRepository = inject(MediaRepository);
  readonly article = resource({ loader: () => this.articles.bySlug('paso-stelvio') });
  readonly media = resource({ loader: () => this.mediaRepository.mediaList(MEDIA_IDS) });
}

@Component({ selector: 'app-root', template: '<p>Página estática</p>' })
class StaticPage {}

function render(context?: unknown) {
  return renderApplication(
    (bootstrapContext) =>
      bootstrapApplication(
        ServerPage,
        {
          providers: [
            provideServerRendering(),
            provideContent(withJsonContent()),
            ...contentServerProviders(),
            ...(context ? [{ provide: REQUEST_CONTEXT, useValue: context }] : []),
          ],
        },
        bootstrapContext,
      ),
    { document: '<html><head></head><body><app-root></app-root></body></html>', url: '/' },
  );
}

function transferState(html: string): Record<string, unknown> {
  const state = html.match(/<script id="ng-state" type="application\/json">(.*?)<\/script>/s);
  return JSON.parse(state?.[1] ?? '{}') as Record<string, unknown>;
}

describe('SSR del build de instantáneas', () => {
  let root: string;
  beforeEach(() => {
    root = tempRoot();
  });
  afterEach(() => rmSync(root, { recursive: true, force: true }));

  it('renderiza desde la versión cargada y transfiere su número, sin datos del original', async () => {
    const data = withPrivateOriginals(fixtureCollections());
    writeSnapshot(root, 'v1', data, FIXTURE_BODIES);
    setCurrentSnapshot(await loadSnapshot(root, 'v1'));

    const html = await render();
    expect(html).toContain('<h1>Paso del Stelvio en moto</h1>');
    expect(html).toContain(`<p class="media">${data.media.length}</p>`);

    const state = transferState(html);
    expect(state['content:snapshot-version']).toBe('v1');
    // HTML y TransferState: ni original ni hashes ni valores privados.
    expect(html).not.toContain('"original"');
    expect(html).not.toMatch(/[0-9a-f]{64}/);
    for (const value of privateValues(data.media as MediaRecord[]))
      expect(html).not.toContain(value);
  });

  it('cada petición fija la versión vigente al empezar', async () => {
    const first = fixtureCollections();
    const second = fixtureCollections();
    second.articles = second.articles.map((a) =>
      a.slug === 'paso-stelvio' ? { ...a, title: 'Stelvio, segunda versión' } : a,
    );
    writeSnapshot(root, 'v1', first, FIXTURE_BODIES);
    writeSnapshot(root, 'v2', second, FIXTURE_BODIES);
    const v1 = await loadSnapshot(root, 'v1');
    const v2 = await loadSnapshot(root, 'v2');

    // Como server.ts: la versión se fija al llegar cada petición (contentRequestContext).
    setCurrentSnapshot(v1);
    const a = render(contentRequestContext());
    setCurrentSnapshot(v2); // cambia mientras la primera petición sigue en curso
    const b = render(contentRequestContext());
    const [htmlA, htmlB] = await Promise.all([a, b]);

    expect(htmlA).toContain('Paso del Stelvio en moto');
    expect(transferState(htmlA)['content:snapshot-version']).toBe('v1');
    expect(htmlB).toContain('Stelvio, segunda versión');
    expect(transferState(htmlB)['content:snapshot-version']).toBe('v2');
    expect(htmlA).not.toContain('segunda versión');
  });

  it('sin versión cargada (el prerender del build) no transfiere ninguna', async () => {
    setCurrentSnapshot(undefined as never);
    const html = await renderApplication(
      (bootstrapContext) =>
        bootstrapApplication(
          StaticPage,
          {
            providers: [
              provideServerRendering(),
              provideContent(withJsonContent()),
              ...contentServerProviders(),
            ],
          },
          bootstrapContext,
        ),
      { document: '<html><head></head><body><app-root></app-root></body></html>', url: '/' },
    );
    expect(html).toContain('Página estática');
    expect(transferState(html)['content:snapshot-version']).toBeUndefined();
  });
});
