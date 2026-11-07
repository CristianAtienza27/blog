import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  convertToParamMap,
  provideRouter,
} from '@angular/router';
import type { Article, ArticleSummary, ContentBlock } from '../../../content/models';
import { ArticleRepository } from '../../../content/repositories/article-repository';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { photo, summary } from '../../../editorial/testing/editorial-fixtures';
import { ArticleCredits } from './article-credits';
import { ArticleNavigation } from './article-navigation';
import { ArticleTags } from './article-tags';
import { articlePhotos, articleReferences, articleTeaser, creditsPlan } from './closing';
import {
  type ArticleClosing,
  RELATED_LIMIT,
  articleClosing,
  articleClosingResolver,
} from './closing.resolver';
import { RelatedArticles } from './related-articles';

const art = (slug: string, overrides: Partial<ArticleSummary> = {}) => summary(slug, overrides);

const article = (blocks: ContentBlock[], overrides: Partial<Article> = {}): Article => ({
  ...summary('relato', {
    cover: photo('portada', {
      caption: 'La portada.',
      credit: { ...photo('portada').credit, author: 'Ana' },
    }),
    tags: [
      { id: 'costa', slug: 'costa', label: 'Costa' },
      { id: 'rutas', slug: 'rutas', label: 'Rutas' },
    ],
  }),
  format: 'reportaje',
  blocks,
  ...overrides,
});

const byAna = photo('cala', {
  caption: 'Una cala.',
  credit: { ...photo('cala').credit, author: 'Ana' },
});
const byLuis = photo('faro', {
  caption: 'Un faro.',
  credit: { ...photo('faro').credit, author: 'Luis', license: 'CC-BY-4.0' },
});

describe('Cierre: anterior, siguiente y relacionados', () => {
  // Llegan desordenados y con fechas al revés del orden editorial: manda `order`.
  const section = [
    art('c', { order: 3, publishedAt: '2026-01-01' }),
    art('a', { order: 1, publishedAt: '2026-12-01' }),
    art('d', { order: 4, publishedAt: '2025-01-01' }),
    art('b', { order: 2, publishedAt: '2026-06-01' }),
  ];

  it('vecinos por orden editorial (`order`), nunca por fecha', () => {
    expect(articleClosing('b', section, [])).toMatchObject({
      previous: { slug: 'a' },
      next: { slug: 'c' },
    });
  });

  it('al principio de la sección no hay anterior; al final, no hay siguiente', () => {
    expect(articleClosing('a', section, [])).toMatchObject({ previous: null, next: { slug: 'b' } });
    expect(articleClosing('d', section, [])).toMatchObject({ previous: { slug: 'c' }, next: null });
    expect(articleClosing('solo', [art('solo')], [])).toMatchObject({ previous: null, next: null });
  });

  it('nunca cruza de sección, aunque la lista traiga artículos de otra', () => {
    const motor = { id: 'motor' as const, slug: 'motor', name: 'Motor', description: '' };
    const mixed = [...section, art('m', { order: 5, category: motor })];
    expect(articleClosing('d', mixed, [])).toMatchObject({ previous: { slug: 'c' }, next: null });
    expect(articleClosing('m', mixed, [])).toMatchObject({ previous: null, next: null });
  });

  it('relacionados sin el propio artículo, sin los vecinos y sin repetir, como mucho tres', () => {
    const related = ['b', 'a', 'x', 'x', 'c', 'y', 'z', 'w'].map((slug) => art(slug));
    const closing = articleClosing('b', section, related);
    expect(closing.related.map((a) => a.slug)).toEqual(['x', 'y', 'z']);
    expect(closing.related).toHaveLength(RELATED_LIMIT);
  });
});

describe('Créditos: autor, fotografías, referencias y producción', () => {
  const blocks: ContentBlock[] = [
    {
      type: 'text',
      html: '<p>Ver <a href="https://es.wikipedia.org/wiki/Posidonia">la posidonia</a>.</p>',
    },
    { type: 'image', media: byAna, layout: 'wide' },
    { type: 'image', media: photo('motor-moto-trail'), layout: 'wide' },
    { type: 'figures', layout: 'diptych', items: [{ media: byLuis }, { media: byAna }] },
    { type: 'map', points: [], route: false },
    {
      type: 'text',
      html: '<p>Otra vez <a href="https://es.wikipedia.org/wiki/Posidonia">aquí</a>.</p>',
    },
  ];

  it('las fotos que se ven, en orden y sin repetir ni retiradas (la portada primero)', () => {
    expect(articlePhotos(article(blocks)).map((p) => [p.media.id, p.cover])).toEqual([
      ['portada', true],
      ['cala', false],
      ['faro', false],
    ]);
  });

  it('agrupadas por autor y licencia, cada una con su fuente; el autor del texto, aparte', () => {
    const plan = creditsPlan(article(blocks));
    expect(plan.author).toEqual({ name: 'Autoría de prueba', role: 'Autor' });
    expect(
      plan.photos.map((g) => [g.credit.author, g.credit.license, g.photos.map((p) => p.label)]),
    ).toEqual([
      ['Ana', 'CC-BY-SA-4.0', ['La portada', 'Una cala']],
      ['Luis', 'CC-BY-4.0', ['Un faro']],
    ]);
    expect(plan.photos[0].photos[1].href).toBe('https://commons.wikimedia.org/wiki/File:cala.jpg');
  });

  it('referencias: los enlaces externos del texto, una vez; notas: el mapa y la demostración', () => {
    expect(articleReferences(article(blocks))).toEqual([
      {
        label: 'la posidonia',
        href: 'https://es.wikipedia.org/wiki/Posidonia',
        host: 'es.wikipedia.org',
      },
    ]);
    const plan = creditsPlan(article(blocks));
    expect(plan.notes).toHaveLength(1);
    expect(plan.notes[0]).toContain('no sirve para navegar');
    expect(plan.demo).toBe(true);
    expect(creditsPlan(article([], { demo: false })).notes).toEqual([]);
  });

  it('pieza de otro artículo: sección de la navegación y sin la portada si está retirada', () => {
    const retired = art('moto', {
      category: { id: 'motor', slug: 'motor', name: 'Motor', description: '' },
      cover: photo('motor-moto-trail'),
    });
    expect(articleTeaser(retired)).toMatchObject({
      href: '/motor/moto',
      section: 'Motor',
      cover: null,
      demo: true,
    });
    expect(articleTeaser(art('cala')).cover?.id).toBe('cala-portada');
  });
});

@Component({
  imports: [ArticleCredits, ArticleTags, RelatedArticles, ArticleNavigation],
  template: `
    <app-article-credits [article]="article()" />
    <app-article-tags [tags]="article().tags" [category]="article().category" />
    <app-related-articles [articles]="related()" />
    <app-article-navigation [previous]="previous()" [next]="next()" />
  `,
})
class Host {
  readonly article = input.required<Article>();
  readonly related = input<ArticleSummary[]>([]);
  readonly previous = input<ArticleSummary | null>(null);
  readonly next = input<ArticleSummary | null>(null);
}

async function render(inputs: {
  article: Article;
  related?: ArticleSummary[];
  previous?: ArticleSummary | null;
  next?: ArticleSummary | null;
}) {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideMediaDelivery()] });
  const fixture = TestBed.createComponent(Host);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Cierre (componentes)', () => {
  it('créditos: texto y fotografía separados; licencia y fuente enlazadas', async () => {
    const element = await render({
      article: article([{ type: 'image', media: byLuis, layout: 'wide' }]),
    });
    const credits = element.querySelector('app-article-credits section');
    expect(credits?.getAttribute('aria-label')).toBe('Créditos');
    expect([...(credits?.querySelectorAll('dt') ?? [])].map((d) => d.textContent)).toEqual([
      'Texto',
      'Fotografía',
      'Producción',
    ]);
    const photos = [...(credits?.querySelectorAll('.credits__photos li') ?? [])];
    expect(photos.map((li) => li.querySelector('.credits__name')?.textContent)).toEqual([
      'Ana',
      'Luis',
    ]);
    expect(photos[1].querySelector('a[rel="license noopener"]')?.textContent?.trim()).toBe(
      'CC BY 4.0',
    );
    expect(photos[0].querySelector('.credits__mark')?.textContent).toBe('(portada)');
  });

  it('temas como texto (no tienen página); la sección, enlazada a su ruta', async () => {
    const element = await render({ article: article([]) });
    const tags = element.querySelector('app-article-tags');
    expect(
      [...(tags?.querySelectorAll('.tags__list li') ?? [])].map((li) => li.textContent),
    ).toEqual(['Costa', 'Rutas']);
    expect(tags?.querySelectorAll('.tags__list a')).toHaveLength(0);
    expect(tags?.querySelector('a.tags__section')?.getAttribute('href')).toBe('/viajes');
  });

  it('relacionados: la principal con foto grande y entradilla; las demás al lado; un enlace por pieza', async () => {
    const element = await render({
      article: article([]),
      related: [art('uno'), art('dos'), art('tres', { cover: photo('tecnologia-teclado') })],
    });
    const section = element.querySelector('app-related-articles section');
    expect(section?.getAttribute('aria-label')).toBe('Seguir leyendo');
    expect(section?.querySelector('.related__item--lead .related__dek')?.textContent).toBe(
      'Entradilla de uno',
    );
    expect(section?.querySelectorAll('.related__more .related__item')).toHaveLength(2);
    expect([...(section?.querySelectorAll('a') ?? [])].map((a) => a.getAttribute('href'))).toEqual([
      '/viajes/uno',
      '/viajes/dos',
      '/viajes/tres',
    ]);
    // Imágenes decorativas (el título ya nombra el enlace) y diferidas; sin foto si está retirada.
    const imgs = [...(section?.querySelectorAll('img') ?? [])];
    expect(imgs).toHaveLength(2);
    expect(
      imgs.every((i) => i.getAttribute('alt') === '' && i.getAttribute('loading') === 'lazy'),
    ).toBe(true);
    expect(section?.querySelector('.related__meta')?.textContent).toContain('Demostración');
  });

  it('anterior y siguiente: enlaces reales con rel; sin el que no existe', async () => {
    const both = await render({
      article: article([]),
      previous: art('antes'),
      next: art('despues'),
    });
    const nav = both.querySelector('app-article-navigation nav');
    expect(nav?.getAttribute('aria-label')).toBe('Más artículos de Viajes');
    expect(
      [...(nav?.querySelectorAll('a') ?? [])].map((a) => [
        a.getAttribute('rel'),
        a.getAttribute('href'),
      ]),
    ).toEqual([
      ['prev', '/viajes/antes'],
      ['next', '/viajes/despues'],
    ]);
    expect(nav?.querySelector('[rel="prev"]')?.textContent).toContain('Anterior:');

    TestBed.resetTestingModule();
    const onlyNext = await render({ article: article([]), next: art('despues') });
    expect(onlyNext.querySelectorAll('app-article-navigation a')).toHaveLength(1);
    expect(onlyNext.querySelector('[rel="prev"]')).toBeNull();

    TestBed.resetTestingModule();
    const none = await render({ article: article([]) });
    expect(none.querySelector('app-article-navigation nav')).toBeNull();
  });
});

describe('Resolver del cierre', () => {
  async function resolveClosing(section: string, slug: string) {
    // La lista llega desordenada: el resolver no depende de su orden.
    const list = [art('c', { order: 3 }), art('a', { order: 1 }), art('b', { order: 2 })];
    const repository = {
      bySlug: vi.fn(async (s: string) =>
        ['a', 'b', 'c'].includes(s) ? { ...art(s), blocks: [] } : null,
      ),
      list: vi.fn(async () => ({ items: list, total: 3, offset: 0, limit: 50 })),
      related: vi.fn(async () => [art('c'), art('z')]),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: ArticleRepository, useValue: repository }],
    });
    const route = { paramMap: convertToParamMap({ slug }), data: { section } };
    const result = (await TestBed.runInInjectionContext(() =>
      articleClosingResolver(route as unknown as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    )) as ArticleClosing | null;
    return { result, repository };
  }

  it('pide la sección (orden editorial, sin fechas) y los relacionados, sin repetir vecinos', async () => {
    const { result, repository } = await resolveClosing('viajes', 'b');
    expect(repository.list).toHaveBeenCalledWith({ category: 'viajes', limit: 50 });
    expect(result).toMatchObject({ previous: { slug: 'a' }, next: { slug: 'c' } });
    expect(result?.related.map((a) => a.slug)).toEqual(['z']);
  });

  it('artículo inexistente o de otra sección: sin cierre (la página es un 404)', async () => {
    expect((await resolveClosing('viajes', 'nada')).result).toBeNull();
    TestBed.resetTestingModule();
    expect((await resolveClosing('motor', 'b')).result).toBeNull();
  });
});
