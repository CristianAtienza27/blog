import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, provideRouter } from '@angular/router';
import { existsSync } from 'node:fs';
import { provideMediaDelivery } from '../../core/media/media-delivery';
import { HomeIntro, IntroData } from '../../features/home/intro/home-intro';
import { MotionService } from '../../motion/motion';
import type { Article, Media } from '../models';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import { FULL_BLEED_MIN, articlePresentation } from '../../features/article/article-presentation';
import { ArticleRepository } from '../repositories/article-repository';
import { HOME_COMPOSITION } from '../../features/home/home.composition';
import { HomeData, homeResolver } from '../../features/home/home.resolver';
import { provideContent } from '../provide-content';
import { withJsonContent } from './with-json-content';
import { SECTIONS } from '../../layout/navigation';
import { RESERVED_IDS, validateContent } from '../records/integrity';
import { COLLECTION_NAMES, ContentCollections } from '../records/records';
import { jsonFileSource } from './content-source';

/** Carga el contenido real de src/content tal como lo hará la aplicación. */
async function loadContent() {
  const entries = await Promise.all(
    COLLECTION_NAMES.map(async (name) => [name, await jsonFileSource.collection(name)] as const),
  );
  const data = Object.fromEntries(entries) as unknown as ContentCollections;
  const bodies = new Map<string, unknown>();
  for (const article of data.articles)
    bodies.set(article.slug, await jsonFileSource.body(article.slug));
  return { data, bodies };
}

describe('Contenido de src/content', () => {
  it('cumple la estructura y todas las relaciones son válidas', async () => {
    const { data, bodies } = await loadContent();
    const externalMedia = SECTIONS.map((section) => section.preview);
    expect(validateContent(data, bodies, { externalMedia })).toEqual([]);
  });

  it('cada sección del menú tiene una fotografía de vista previa registrada', async () => {
    const { data } = await loadContent();
    const ids = new Set(data.media.map((m) => m.id));
    expect(SECTIONS.filter((section) => !ids.has(section.preview)).map((s) => s.id)).toEqual([]);
  });

  it('la fotografía y el destino del hero de la portada existen', async () => {
    const { data } = await loadContent();
    const { hero } = HOME_COMPOSITION;
    expect(data.media.map((m) => m.id)).toContain(hero.media);
    const destination = data.destinations.find((d) => d.slug === hero.destination);
    expect(destination?.elevation).toBeTypeOf('number');
  });

  it('la portada y el menú no usan fotografías retiradas', async () => {
    const used = [HOME_COMPOSITION.hero.media, ...SECTIONS.map((section) => section.preview)];
    expect(used.filter((id) => RETIRED_MEDIA.includes(id))).toEqual([]);
  });

  it('todas las imágenes tienen texto alternativo, autoría y licencia', async () => {
    const { data } = await loadContent();
    for (const m of data.media) {
      expect(m.alt.trim().length, m.id).toBeGreaterThan(10);
      expect(m.credit.author.trim().length, m.id).toBeGreaterThan(1);
    }
  });

  it('las categorías coinciden con las secciones de la navegación', async () => {
    const { data } = await loadContent();
    expect(data.categories.map((c) => [c.id, c.name])).toEqual(
      SECTIONS.map((s) => [s.id, s.label]),
    );
  });

  it('todo el contenido editorial está marcado como demostración', async () => {
    const { data } = await loadContent();
    const editorial = [
      ...data.articles,
      ...data.galleries,
      ...data.destinations,
      ...data.trips,
      ...data.projects,
    ];
    expect(editorial.filter((item) => !item.demo).map((item) => item.id)).toEqual([]);
  });

  it('las imágenes de fuentes externas se marcan como provisionales', async () => {
    const { data } = await loadContent();
    const external = data.media.filter((m) => m.credit.source !== 'propia');
    expect(external.filter((m) => !m.demo).map((m) => m.id)).toEqual([]);
  });

  it('el autor no tiene biografía inventada', async () => {
    const { data } = await loadContent();
    expect(data.authors.every((author) => author.bio === undefined)).toBe(true);
  });

  it('un slug desconocido o con caracteres de ruta no carga nada', async () => {
    expect(await jsonFileSource.body('no-existe')).toBeNull();
    expect(await jsonFileSource.body('../categories')).toBeNull();
  });
});

/** La portada resuelta con el contenido real, como en el prerender. */
async function resolveHome(): Promise<HomeData> {
  TestBed.configureTestingModule({ providers: [provideContent(withJsonContent())] });
  return TestBed.runInInjectionContext(
    () =>
      homeResolver({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as Promise<HomeData>,
  );
}

describe('Portada con el contenido real', () => {
  it('todas las piezas de la composición existen', async () => {
    const { hero, intro, travel } = await resolveHome();
    expect(hero).not.toBeNull();
    expect(intro.media?.id).toBe(HOME_COMPOSITION.intro.media);
    expect(travel.feature?.article.slug).toBe(HOME_COMPOSITION.travel.feature);
    expect(travel.stories.map((story) => [story.article.slug, story.layout])).toEqual(
      HOME_COMPOSITION.travel.stories.map((story) => [story.article, story.layout]),
    );
    expect(travel.stories[0].secondary?.id).toBe('ciudades-lisboa-alfama');
  });

  it('la historia principal es Cadaqués, con las cuatro paradas del itinerario', async () => {
    const { feature } = (await resolveHome()).travel;
    expect(feature?.place?.name).toBe('Cadaqués');
    expect(feature?.article.destinations.map((d) => d.slug)).toEqual([
      'tossa-de-mar',
      'calella-de-palafrugell',
      'begur',
      'cadaques',
    ]);
  });

  it('cada fotografía de la portada cumple una sola función y ninguna está retirada', async () => {
    const { hero, intro, travel } = await resolveHome();
    const photos = [
      hero?.media.id,
      intro.media?.id,
      travel.feature?.article.cover.id,
      ...travel.stories.flatMap((story) => [story.article.cover.id, story.secondary?.id]),
    ].filter((id): id is string => !!id);
    expect(new Set(photos).size).toBe(photos.length);
    expect(photos.filter((id) => RETIRED_MEDIA.includes(id))).toEqual([]);
  });

  it('el índice enlaza todos los destinos y nunca repite el hero como vista previa', async () => {
    const { travel } = await resolveHome();
    expect(travel.index).toHaveLength(13);
    expect(travel.index.filter((entry) => !entry.article).map((entry) => entry.id)).toEqual([]);
    const previews = travel.index.flatMap((entry) => (entry.preview ? [entry.preview.id] : []));
    expect(previews).not.toContain(HOME_COMPOSITION.hero.media);
    expect(previews.filter((id) => RETIRED_MEDIA.includes(id))).toEqual([]);
  });

  it('cada artículo de viajes trata al menos un destino', async () => {
    const { data } = await loadContent();
    const tripIds = new Set(data.trips.map((t) => t.id));
    const travel = data.articles.filter((a) => a.category === 'viajes');
    expect(travel.length).toBe(4);
    for (const article of travel) {
      const hasPlaces =
        (article.trip && tripIds.has(article.trip)) || !!article.destinations?.length;
      expect(hasPlaces, article.slug).toBe(true);
    }
  });
});

/** Archivo publicado en public/ que corresponde a una URL de /media/. */
function publicFile(url: string): string {
  return 'public' + new URL(url, 'http://localhost').pathname;
}

/** Variantes que el componente puede pedir: cada ancho registrado, en AVIF y WebP. */
function variants(media: Media): string[] {
  return media.widths.flatMap((w) =>
    ['avif', 'webp'].map((f) => `public/media/${media.path}-${w}.${f}`),
  );
}

@Component({
  imports: [HomeIntro],
  template: `<app-home-intro [intro]="intro()" />`,
})
class IntroHost {
  readonly intro = input.required<IntroData>();
}

describe('Fotografías de la portada en disco', () => {
  it('cada foto de la portada tiene todas sus variantes publicadas', async () => {
    const { hero, intro, travel } = await resolveHome();
    const photos = [
      hero?.media,
      intro.media,
      travel.feature?.article.cover,
      ...travel.stories.flatMap((story) => [story.article.cover, story.secondary]),
      ...travel.index.map((entry) => entry.preview),
    ].filter((media): media is Media => !!media);
    const missing = photos.flatMap(variants).filter((file) => !existsSync(file));
    expect(missing).toEqual([]);
  });

  it('la foto de la presentación (bosque con niebla) se renderiza con fuentes válidas', async () => {
    const { intro } = await resolveHome();
    expect(intro.media?.id).toBe('naturaleza-niebla-bosque');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideMediaDelivery(),
        { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
      ],
    });
    const fixture = TestBed.createComponent(IntroHost);
    fixture.componentRef.setInput('intro', intro);
    await fixture.whenStable();
    const photo = (fixture.nativeElement as HTMLElement).querySelector('.intro__photo');
    const img = photo?.querySelector('img');
    const source = photo?.querySelector('source');
    // Datos de la foto aprobada: texto alternativo, recorte 4:5 y crédito.
    expect(img?.getAttribute('alt')).toBe(intro.media?.alt);
    expect(photo?.querySelector('.media__frame')?.getAttribute('style')).toContain(
      'aspect-ratio: 4/5',
    );
    expect(photo?.querySelector('.media__credit')?.textContent).toContain('Kuhnmi');
    // Todas las URL que puede pedir el navegador existen: src, srcset del <img> y del <source>.
    const urls = [
      img?.getAttribute('src'),
      ...(img?.getAttribute('srcset') ?? '').split(','),
      ...(source?.getAttribute('srcset') ?? '').split(','),
    ]
      .map((candidate) => candidate?.trim().split(' ')[0])
      .filter((url): url is string => !!url);
    expect(urls.length).toBe(7);
    expect(source?.getAttribute('type')).toBe('image/avif');
    expect(urls.filter((url) => !existsSync(publicFile(url)))).toEqual([]);
  });
});

/** Cabecera de cada artículo con el contenido real, como en el prerender. */
async function articleHeaders() {
  TestBed.configureTestingModule({ providers: [provideContent(withJsonContent())] });
  const repository = TestBed.inject(ArticleRepository);
  const slugs = await repository.slugs();
  const articles = await Promise.all(slugs.map((slug) => repository.bySlug(slug)));
  return articles
    .filter((article): article is Article => !!article)
    .map((article) => ({ article, header: articlePresentation(article).header }));
}

describe('Cabeceras de artículo con el contenido real', () => {
  it('los artículos de referencia usan las cuatro variantes', async () => {
    const variants = Object.fromEntries(
      (await articleHeaders()).map(({ article, header }) => [article.slug, header.variant]),
    );
    expect(variants).toMatchObject({
      'itinerario-costa-brava': 'cinema',
      'ciudades-luz-de-tarde': 'cinema',
      'calas-de-mallorca': 'split',
      'paso-del-stelvio': 'plate',
      // Ensayo panorámico: sin altura para el fotograma completo, a lo ancho.
      'serie-horizontes': 'plate',
      'alicante-desde-el-puerto': 'compact',
    });
  });

  it('ninguna cabecera usa una fotografía retirada', async () => {
    for (const { article, header } of await articleHeaders()) {
      expect(RETIRED_MEDIA, article.slug).not.toContain(header.cover?.media.id);
      if (RETIRED_MEDIA.includes(article.cover.id)) expect(header.cover).toBeNull();
    }
  });

  it('las fotos a sangre (cine y lámina) tienen resolución suficiente', async () => {
    for (const { article, header } of await articleHeaders()) {
      if (header.variant !== 'cinema' && header.variant !== 'plate') continue;
      expect(header.cover?.media.width, article.slug).toBeGreaterThanOrEqual(FULL_BLEED_MIN.width);
      if (header.variant === 'cinema') {
        expect(header.cover?.media.height, article.slug).toBeGreaterThanOrEqual(
          FULL_BLEED_MIN.height,
        );
      }
    }
  });

  it('ninguna cabecera de demostración muestra fechas', async () => {
    for (const { article, header } of await articleHeaders()) {
      expect(article.demo).toBe(true);
      expect(
        header.facts.filter((fact) => fact.datetime),
        article.slug,
      ).toEqual([]);
    }
  });
});

describe('Cuerpo de los artículos con el contenido real', () => {
  it('cada capítulo tiene un id de ancla único en su página', async () => {
    for (const { article } of await articleHeaders()) {
      const ids = article.blocks.flatMap((block) => (block.type === 'chapter' ? [block.id] : []));
      expect(new Set(ids).size, article.slug).toBe(ids.length);
      expect(
        ids.filter((id) => RESERVED_IDS.includes(id)),
        article.slug,
      ).toEqual([]);
    }
  });

  it('los artículos de referencia prueban anclas, enlaces internos y externos', async () => {
    const headers = await articleHeaders();
    const html = async (slug: string) =>
      headers
        .find(({ article }) => article.slug === slug)
        ?.article.blocks.flatMap((block) => (block.type === 'text' ? [block.html] : []))
        .join('');
    expect(await html('itinerario-costa-brava')).toContain('href="#cadaques"');
    expect(await html('calas-de-mallorca')).toContain('href="/viajes/itinerario-costa-brava"');
    expect(await html('calas-de-mallorca')).toContain('href="https://es.wikipedia.org/');
  });
});
