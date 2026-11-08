import { Component, PLATFORM_ID, TransferState, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, provideRouter } from '@angular/router';
import { existsSync } from 'node:fs';
import { provideMediaDelivery } from '../../core/media/media-delivery';
import { HomeIntro, IntroData } from '../../features/home/intro/home-intro';
import { MotionService } from '../../motion/motion';
import type { Article, Media } from '../models';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import { FULL_BLEED_MIN, articlePresentation } from '../../features/article/article-presentation';
import { ArticleRepository, MAX_PAGE_SIZE } from '../repositories/article-repository';
import { bodyItems } from '../../features/article/body/body-items';
import { languageLabel } from '../../features/article/body/code-block';
import { figurePlan } from '../../features/article/body/figure-layout';
import { galleryPlan } from '../../features/article/body/gallery-layout';
import { articleSeo } from '../../features/article/article-seo';
import { articleTeaser } from '../../features/article/closing/closing';
import { readingMinutes } from '../records/reading-time';
import { articleClosing } from '../../features/article/closing/closing.resolver';
import { HOME_COMPOSITION } from '../../features/home/home.composition';
import { HomeData, homeResolver } from '../../features/home/home.resolver';
import { provideContent } from '../provide-content';
import { withJsonContent } from './with-json-content';
import { SECTIONS } from '../../layout/navigation';
import { RESERVED_IDS, validateContent } from '../records/integrity';
import { ArticleBodyRecord, COLLECTION_NAMES, ContentCollections } from '../records/records';
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
    expect(validateContent(data, bodies, { externalMedia, retiredMedia: RETIRED_MEDIA })).toEqual(
      [],
    );
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

  it('ninguna cabecera usa una fotografía retirada; sin portada, cabecera compacta sin foto', async () => {
    const withoutCover: string[] = [];
    for (const { article, header } of await articleHeaders()) {
      expect(RETIRED_MEDIA, article.slug).not.toContain(header.cover?.media.id);
      if (article.cover) continue;
      withoutCover.push(article.slug);
      expect(header.cover, article.slug).toBeNull();
      expect(header.variant, article.slug).toBe('compact');
    }
    expect(withoutCover.sort()).toEqual([
      'anatomia-moto-de-turismo',
      'equipaje-ruta-varios-dias',
      'repositorios-desacoplados-angular',
    ]);
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

  it('los siete bloques de código de Tecnología se muestran íntegros y con su archivo', async () => {
    const shown = (await articleHeaders()).flatMap(({ article }) =>
      bodyItems(article).flatMap((item) =>
        item.kind === 'code' ? [{ slug: article.slug, block: item.block }] : [],
      ),
    );
    expect(shown.map(({ slug, block }) => `${slug} · ${block.filename}`)).toEqual([
      'como-esta-construida-esta-revista · app.routes.server.ts',
      'como-esta-construida-esta-revista · _root.scss',
      'como-esta-construida-esta-revista · motion.ts',
      'como-esta-construida-esta-revista · tools/media-worker/src/config.mjs',
      'repositorios-desacoplados-angular · article-repository.ts',
      'repositorios-desacoplados-angular · app.config.ts',
      'repositorios-desacoplados-angular · content-cache.ts',
    ]);
    for (const { slug, block } of shown) {
      // Lenguajes con nombre propio (no el identificador en bruto).
      expect(languageLabel(block.language), slug).not.toBe(block.language);
      // Sin tabuladores ni espacios finales que cambien al copiarlo o al hidratar.
      expect(block.code, slug).not.toMatch(/\t|[ ]+$/m);
    }
  });
});

/** Fotografías del cuerpo de cada artículo tal como se muestran (bodyItems). */
async function bodyPhotos() {
  return (await articleHeaders()).map(({ article }) => {
    const items = bodyItems(article);
    const singles = items.flatMap((item) => (item.kind === 'figure' ? [item.block] : []));
    const compositions = items.flatMap((item) => (item.kind === 'figures' ? [item.block] : []));
    const media = [
      ...singles.map((block) => block.media),
      ...compositions.flatMap((block) => block.items.map((item) => item.media)),
    ];
    return { article, singles, compositions, media };
  });
}

describe('Fotografías del cuerpo con el contenido real', () => {
  it('nunca muestran una fotografía retirada ni repiten la de la portada', async () => {
    for (const { article, media } of await bodyPhotos()) {
      const ids = media.map((m) => m.id);
      expect(
        ids.filter((id) => RETIRED_MEDIA.includes(id)),
        article.slug,
      ).toEqual([]);
      if (article.cover) expect(ids, article.slug).not.toContain(article.cover.id);
    }
  });

  it('cada foto del cuerpo tiene todas sus variantes AVIF y WebP en disco', async () => {
    const missing = (await bodyPhotos())
      .flatMap(({ media }) => media)
      .flatMap((m) => variants(m))
      .filter((file) => !existsSync(file));
    expect(missing).toEqual([]);
  });

  it('a sangre, solo fotos con resolución para toda la pantalla', async () => {
    for (const { article, singles } of await bodyPhotos()) {
      for (const block of singles) {
        if (figurePlan(block).placement !== 'full') continue;
        expect(block.media.width, `${article.slug}: ${block.media.id}`).toBeGreaterThanOrEqual(
          FULL_BLEED_MIN.width,
        );
      }
    }
  });

  it('los artículos de referencia prueban cada composición', async () => {
    const photos = await bodyPhotos();
    const of = (slug: string) => photos.find(({ article }) => article.slug === slug);
    const plans = (slug: string) => of(slug)?.singles.map((block) => figurePlan(block)) ?? [];
    expect(plans('itinerario-costa-brava')).toContainEqual(
      expect.objectContaining({ placement: 'side', align: 'end', orientation: 'portrait' }),
    );
    expect(plans('ciudades-luz-de-tarde')).toContainEqual(
      expect.objectContaining({ placement: 'side', align: 'start', orientation: 'portrait' }),
    );
    expect(plans('ciudades-luz-de-tarde')).toContainEqual(
      expect.objectContaining({ placement: 'edge', align: 'end' }),
    );
    expect(of('paso-del-stelvio')?.compositions.map((c) => c.layout)).toEqual(['diptych']);
    expect(of('serie-horizontes')?.compositions.map((c) => c.layout)).toEqual([
      'triptych',
      'sequence',
    ]);
  });
});

describe('Galerías del cuerpo con el contenido real', () => {
  const galleriesOf = async () =>
    (await articleHeaders()).map(({ article }) => ({
      slug: article.slug,
      galleries: bodyItems(article).flatMap((item) =>
        item.kind === 'gallery' ? [item.gallery] : [],
      ),
    }));

  it('solo Calas tiene galería: Mediterráneo, con dos fotos de la Costa Brava en pareja', async () => {
    const all = await galleriesOf();
    expect(all.filter((a) => a.galleries.length).map((a) => a.slug)).toEqual(['calas-de-mallorca']);
    const [mediterraneo] = all.find((a) => a.slug === 'calas-de-mallorca')?.galleries ?? [];
    expect(mediterraneo.id).toBe('mediterraneo');
    expect(mediterraneo.items.map((item) => item.media.id)).toEqual([
      'viajes-calella-banyera',
      'viajes-cadaques-bahia',
    ]);
    // La descripción aclara que no son de Mallorca; los pies son los de cada foto.
    expect(mediterraneo.description).toContain('Costa Brava');
    const plan = galleryPlan(mediterraneo);
    expect(plan.count).toBe('2 fotografías');
    expect(plan.groups.map((group) => [group.kind, group.items.length])).toEqual([['pair', 2]]);
    expect(plan.items.map((item) => item.caption)).toEqual([
      'Una cala de roca en Calella de Palafrugell.',
      'Cadaqués desde la iglesia de Santa María.',
    ]);
  });

  it('nunca muestran una fotografía retirada y todas tienen sus variantes en disco', async () => {
    const media = (await galleriesOf()).flatMap(({ galleries }) =>
      galleries.flatMap((g) => g.items.map((item) => item.media)),
    );
    expect(media.filter((m) => RETIRED_MEDIA.includes(m.id))).toEqual([]);
    expect(media.flatMap((m) => variants(m)).filter((file) => !existsSync(file))).toEqual([]);
  });
});

describe('Mapas, itinerarios y capítulos con el contenido real', () => {
  // Una sola carga del contenido por test (articleHeaders configura TestBed).
  const mapsLoader = async () => {
    const all = await articleHeaders();
    return (slug: string) => {
      const found = all.find(({ article }) => article.slug === slug);
      return found
        ? bodyItems(found.article).flatMap((item) => (item.kind === 'map' ? [item] : []))
        : [];
    };
  };

  it('Costa Brava y Stelvio: el mapa sale del itinerario y sus capítulos enlazan las paradas', async () => {
    const mapsOf = await mapsLoader();
    const [costa] = mapsOf('itinerario-costa-brava');
    expect(costa.block.trip?.id).toBe('costa-brava');
    expect(costa.block.points.map((p) => p.destination?.id)).toEqual([
      'tossa-de-mar',
      'calella-de-palafrugell',
      'begur',
      'cadaques',
    ]);
    expect(Object.keys(costa.chapters)).toEqual([
      'tossa-de-mar',
      'calella-de-palafrugell',
      'begur',
      'cadaques',
    ]);
    const [stelvio] = mapsOf('paso-del-stelvio');
    expect(stelvio.block.trip?.stops.map((s) => s.destination.id)).toEqual([
      'prato-allo-stelvio',
      'paso-del-stelvio',
      'bormio',
    ]);
    // El paso no tiene capítulo propio: solo las dos vertientes.
    expect(stelvio.chapters).toEqual({
      'prato-allo-stelvio': { id: 'vertiente-norte', number: 1 },
      bormio: { id: 'vertiente-sur', number: 2 },
    });
  });

  it('Ciudades y Calas: lugares sin itinerario, con coordenadas reales', async () => {
    const mapsOf = await mapsLoader();
    const [ciudades] = mapsOf('ciudades-luz-de-tarde');
    expect(ciudades.block.trip).toBeUndefined();
    expect(Object.keys(ciudades.chapters)).toEqual(['lisboa', 'florencia', 'praga', 'oporto']);
    const [calas] = mapsOf('calas-de-mallorca');
    expect(calas.block.points).toHaveLength(1);
    expect(calas.chapters).toEqual({});
    for (const map of [ciudades, calas]) {
      expect(
        map.block.points.every((p) => p.destination && p.location === p.destination.location),
      ).toBe(true);
    }
  });
});

describe('Orden editorial con el contenido real', () => {
  /** El orden decidido para cada sección (campo `order` de articles.json). */
  const EXPECTED: Record<string, string[]> = {
    viajes: [
      'itinerario-costa-brava',
      'ciudades-luz-de-tarde',
      'calas-de-mallorca',
      'alicante-desde-el-puerto',
    ],
    fotografia: ['hora-azul-notas-de-exposicion', 'serie-horizontes'],
    motor: ['anatomia-moto-de-turismo', 'paso-del-stelvio'],
    experiencias: ['plantilla-de-relato', 'equipaje-ruta-varios-dias'],
    tecnologia: ['como-esta-construida-esta-revista', 'repositorios-desacoplados-angular'],
  };

  const repository = () => {
    TestBed.configureTestingModule({ providers: [provideContent(withJsonContent())] });
    return TestBed.inject(ArticleRepository);
  };

  it('cada sección, en el orden definido (sin fechas)', async () => {
    const articles = repository();
    for (const section of SECTIONS) {
      const page = await articles.list({ category: section.id, limit: 50 });
      expect(
        page.items.map((a) => a.slug),
        section.id,
      ).toEqual(EXPECTED[section.id]);
      expect(page.items.map((a) => a.order)).toEqual(page.items.map((_, i) => i + 1));
    }
  });

  it('anterior y siguiente: los inmediatos en el orden, sin salir de la sección', async () => {
    const articles = repository();
    for (const section of SECTIONS) {
      const page = await articles.list({ category: section.id, limit: 50 });
      const slugs = EXPECTED[section.id];
      slugs.forEach((slug, i) => {
        const closing = articleClosing(slug, page.items, []);
        expect(closing.previous?.slug ?? null, `${slug} anterior`).toBe(slugs[i - 1] ?? null);
        expect(closing.next?.slug ?? null, `${slug} siguiente`).toBe(slugs[i + 1] ?? null);
      });
    }
    // Los ejemplos de la decisión editorial.
    const viajes = (await articles.list({ category: 'viajes', limit: 50 })).items;
    expect(articleClosing('itinerario-costa-brava', viajes, []).next?.slug).toBe(
      'ciudades-luz-de-tarde',
    );
    expect(articleClosing('alicante-desde-el-puerto', viajes, []).previous?.slug).toBe(
      'calas-de-mallorca',
    );
  });

  it('empates en relacionados: decide el orden editorial (sección y orden), no la fecha', async () => {
    const articles = repository();
    // Stelvio: la ficha de Motor suma 3; Costa Brava, Horizontes y Equipaje empatan a 2.
    const related = await articles.related('paso-del-stelvio', 4);
    expect(related.map((a) => a.slug)).toEqual([
      'anatomia-moto-de-turismo',
      'itinerario-costa-brava',
      'serie-horizontes',
      'equipaje-ruta-varios-dias',
    ]);
  });
});

describe('Limpieza del contenido de demostración', () => {
  /** Fotografías que cita cada artículo en sus registros: portada, imágenes, composiciones y galerías. */
  async function referencedPhotos() {
    const { data, bodies } = await loadContent();
    const galleries = new Map(data.galleries.map((g) => [g.id, g]));
    return data.articles.map((article) => {
      const body = bodies.get(article.slug) as ArticleBodyRecord;
      const photos = [
        ...(article.cover ? [article.cover] : []),
        ...body.blocks.flatMap((block) => {
          if (block.type === 'image') return [block.media];
          if (block.type === 'figures') return block.items.map((item) => item.media);
          if (block.type === 'gallery') {
            return galleries.get(block.gallery)?.items.map((item) => item.media) ?? [];
          }
          return [];
        }),
      ];
      return { slug: article.slug, photos };
    });
  }

  it('ningún artículo cita las cuatro fotografías retiradas, ni las galerías', async () => {
    const { data } = await loadContent();
    for (const { slug, photos } of await referencedPhotos()) {
      expect(
        photos.filter((id) => RETIRED_MEDIA.includes(id)),
        slug,
      ).toEqual([]);
    }
    const inGalleries = data.galleries.flatMap((g) => g.items.map((item) => item.media));
    expect(inGalleries.filter((id) => RETIRED_MEDIA.includes(id))).toEqual([]);
    // El catálogo las conserva como recursos retirados (créditos y variantes intactos).
    expect(data.media.filter((m) => RETIRED_MEDIA.includes(m.id)).map((m) => m.id)).toEqual(
      RETIRED_MEDIA,
    );
  });

  it('ninguna fotografía se repite dentro de un artículo', async () => {
    for (const { slug, photos } of await referencedPhotos()) {
      expect(
        photos.filter((id, i) => photos.indexOf(id) !== i),
        slug,
      ).toEqual([]);
    }
  });

  it('todas las galerías del catálogo las muestra algún artículo', async () => {
    const { data, bodies } = await loadContent();
    const used = new Set(
      [...bodies.values()].flatMap((body) =>
        (body as ArticleBodyRecord).blocks.flatMap((block) =>
          block.type === 'gallery' ? [block.gallery] : [],
        ),
      ),
    );
    expect(data.galleries.map((g) => g.id)).toEqual(['mediterraneo']);
    expect(data.galleries.filter((g) => !used.has(g.id))).toEqual([]);
    // La etiqueta temática sigue existiendo y la usa Stelvio.
    expect(data.tags.map((t) => t.id)).toContain('puertos-de-montana');
    expect(data.articles.find((a) => a.slug === 'paso-del-stelvio')?.tags).toContain(
      'puertos-de-montana',
    );
  });

  it('el estado que el servidor pasa al navegador (TransferState) no lleva fotos retiradas', async () => {
    TestBed.configureTestingModule({
      providers: [provideContent(withJsonContent()), { provide: PLATFORM_ID, useValue: 'server' }],
    });
    const articles = TestBed.inject(ArticleRepository);
    // Lo que piden las rutas: portada, artículo, cierre (sección y relacionados).
    await TestBed.runInInjectionContext(() =>
      homeResolver({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
    for (const slug of await articles.slugs()) {
      const article = await articles.bySlug(slug);
      await articles.list({ category: article?.category.id, limit: MAX_PAGE_SIZE });
      await articles.related(slug, 5);
    }
    const state = TestBed.inject(TransferState).toJson();
    expect(state.length).toBeGreaterThan(1000);
    expect(RETIRED_MEDIA.filter((id) => state.includes(id))).toEqual([]);
  });

  it('sin portada: cabecera, SEO y tarjetas sin imagen', async () => {
    for (const { article, header } of await articleHeaders()) {
      if (article.cover) continue;
      const seo = articleSeo(article, header, '/media');
      expect(seo.image, article.slug).toBeUndefined();
      expect(seo.noindex, article.slug).toBe(true);
      expect(articleTeaser(article).cover, article.slug).toBeNull();
    }
  });

  it('todas las páginas de artículo siguen con noindex', async () => {
    for (const { article, header } of await articleHeaders()) {
      expect(articleSeo(article, header, '/media').noindex, article.slug).toBe(true);
    }
  });

  it('tiempo de lectura: el guardado coincide con el de los cuerpos limpios', async () => {
    const { data, bodies } = await loadContent();
    for (const article of data.articles) {
      const body = bodies.get(article.slug) as ArticleBodyRecord;
      expect(article.readingMinutes, article.slug).toBe(readingMinutes(body.blocks));
    }
  });
});

describe('Ficha «De un vistazo» de Calas de Mallorca', () => {
  const PLAYAS =
    'https://www.caib.es/sites/planols/es/l/playas_de_2013_planos_provisionales-7385/?pagina=18';
  const DECRETO = 'https://www.caib.es/eboibfront/eli/es-ib/d/2018/07/27/25/dof/spa/html';

  async function calas() {
    const found = (await articleHeaders()).find(
      ({ article }) => article.slug === 'calas-de-mallorca',
    );
    if (!found) throw new Error('falta Calas de Mallorca');
    return bodyItems(found.article);
  }

  it('va justo después del primer bloque de texto, antes del mapa, como título de nivel 2', async () => {
    const items = await calas();
    expect(items.slice(0, 4).map((item) => item.kind)).toEqual(['prose', 'specs', 'prose', 'map']);
    expect(items[1]).toMatchObject({ kind: 'specs', level: 2, headingId: 'datos-1' });
  });

  it('contiene exactamente los datos autorizados', async () => {
    const [, specs] = await calas();
    if (specs.kind !== 'specs') throw new Error('se esperaba la ficha');
    expect(specs.block).toEqual({
      type: 'specs',
      title: 'De un vistazo',
      items: [
        { label: 'Municipio', value: 'Santanyí, Mallorca' },
        {
          label: 'Normativa de fondeo',
          value: 'Prohibido con carácter general fondear directamente sobre Posidonia oceanica',
        },
      ],
      note: 'Decreto 25/2018, artículo 7: el fondeo sobre posidonia solo se admite con sistemas de bajo impacto autorizados, como boyas, y la norma prevé excepciones concretas.',
    });
  });

  it('las fuentes oficiales son enlaces externos seguros y el texto cita la norma con precisión', async () => {
    const items = await calas();
    const [, , sources] = items;
    if (sources.kind !== 'prose') throw new Error('se esperaban las fuentes');
    const links = [...sources.html.matchAll(/<a ([^>]*)>/g)].map(([, attrs]) => attrs);
    expect(links).toEqual([
      `href="${PLAYAS}" rel="noopener noreferrer" target="_blank"`,
      `href="${DECRETO}" rel="noopener noreferrer" target="_blank"`,
    ]);
    expect(sources.html.match(/se abre en una pestaña nueva/g)).toHaveLength(2);
    // «Qué respetar»: prohibición general, con los sistemas autorizados y las excepciones.
    const prose = items.flatMap((item) => (item.kind === 'prose' ? [item.html] : [])).join('');
    expect(prose).not.toContain('Fondear sobre ellas está prohibido');
    expect(prose).toContain(
      'El fondeo directo sobre Posidonia oceanica está prohibido con carácter general, de acuerdo con el Decreto 25/2018, que contempla sistemas de bajo impacto autorizados y excepciones concretas.',
    );
  });
});
