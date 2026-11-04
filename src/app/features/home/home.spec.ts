import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, provideRouter } from '@angular/router';
import type { ArticleSummary, Destination, Media as MediaAsset } from '../../content/models';
import { ArticleRepository } from '../../content/repositories/article-repository';
import { MediaRepository } from '../../content/repositories/media-repository';
import { TravelRepository } from '../../content/repositories/travel-repository';
import { provideMediaDelivery } from '../../core/media/media-delivery';
import { destination, photo, ref, summary } from '../../editorial/testing/editorial-fixtures';
import { MotionApi, MotionService } from '../../motion/motion';
import { HomeHero } from './hero/home-hero';
import { HOME_COMPOSITION } from './home.composition';
import { HeroData, HomeData, homeResolver } from './home.resolver';
import { HomeIntro } from './intro/home-intro';

const { hero: composition } = HOME_COMPOSITION;

const PHOTO: MediaAsset = {
  id: composition.media,
  path: 'motor/stelvio.0a1b2c3d',
  width: 2400,
  height: 1333,
  widths: [640, 1024, 1600, 2400],
  alt: 'Carretera de montaña en el paso del Stelvio',
  focal: { x: 0.5, y: 0.6 },
  placeholder: 'data:image/webp;base64,UklGRhIAAABXRUJQVlA4TAYAAAAvAAAAAAfQ//73v/+BiOh/AAA=',
  credit: {
    author: 'Autoría de prueba',
    source: 'wikimedia',
    sourceUrl: 'https://commons.wikimedia.org/wiki/File:Stelvio.jpg',
    license: 'CC0-1.0',
    licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
  },
  demo: true,
};

const STELVIO: Destination = {
  id: 'stelvio',
  slug: composition.destination,
  name: 'Paso del Stelvio',
  country: 'IT',
  location: { lat: 46.53, lng: 10.45 },
  elevation: 2757,
  summary: 'Destino de prueba',
  demo: true,
};

/** Contenido de prueba de la portada: artículos, destinos y fotos que pide la composición. */
const { intro, travel } = HOME_COMPOSITION;
const CADAQUES = destination('cadaques', 'Cadaqués', { cover: photo('viajes-cadaques-bahia') });
const TOSSA = destination('tossa-de-mar', 'Tossa de Mar', {
  cover: photo('viajes-tossa-vila-vella'),
});
const ARTICLES = [
  summary(travel.feature, {
    cover: photo('viajes-cadaques-bahia'),
    destinations: [ref(TOSSA), ref(CADAQUES)],
  }),
  ...travel.stories.map((story) => summary(story.article)),
  summary('paso-del-stelvio', {
    category: { id: 'motor', slug: 'motor', name: 'Motor', description: '' },
    destinations: [ref(STELVIO)],
  }),
];
const DESTINATIONS = [CADAQUES, TOSSA, { ...STELVIO, cover: PHOTO }];

/** Repositorios simulados: la portada solo conoce las interfaces abstractas. */
async function resolveHome(
  content: { media?: MediaAsset | null; articles?: ArticleSummary[] } = {},
): Promise<HomeData> {
  const media = {
    media: vi.fn(async (id: string) =>
      id === composition.media && content.media !== undefined ? content.media : photo(id),
    ),
    mediaList: vi.fn(async (ids: readonly string[]) => ids.map((id) => photo(id))),
  };
  const travelRepository = {
    destination: vi.fn(async () => STELVIO),
    destinations: vi.fn(async () => DESTINATIONS),
  };
  const items = content.articles ?? ARTICLES;
  const articles = {
    list: vi.fn(async () => ({ items, total: items.length, offset: 0, limit: 50 })),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: MediaRepository, useValue: media },
      { provide: TravelRepository, useValue: travelRepository },
      { provide: ArticleRepository, useValue: articles },
    ],
  });
  const data = await TestBed.runInInjectionContext(
    () =>
      homeResolver({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as Promise<HomeData>,
  );
  expect(media.media).toHaveBeenCalledWith(composition.media);
  expect(travelRepository.destination).toHaveBeenCalledWith(composition.destination);
  // Una sola consulta de artículos para toda la portada.
  expect(articles.list).toHaveBeenCalledTimes(1);
  return data;
}

describe('Resolver de la portada', () => {
  it('reúne la fotografía y el lugar del hero desde los repositorios', async () => {
    const { hero } = await resolveHome({ media: PHOTO });
    expect(hero?.media).toBe(PHOTO);
    expect(hero?.destination).toBe(STELVIO);
    expect(hero?.kicker).toBe(composition.kicker);
    expect(hero?.tagline).toEqual(composition.tagline);
  });

  it('sin la fotografía, la portada se muestra sin hero', async () => {
    expect((await resolveHome({ media: null })).hero).toBeNull();
  });

  it('la presentación lleva los textos de la composición y su fotografía', async () => {
    const data = await resolveHome();
    expect(data.intro).toEqual({ ...intro, media: photo(intro.media) });
  });

  it('la historia principal toma como lugar el destino retratado en su portada', async () => {
    const { feature } = (await resolveHome()).travel;
    expect(feature?.article.slug).toBe(travel.feature);
    expect(feature?.place?.name).toBe('Cadaqués');
  });

  it('cada pieza secundaria tiene su composición; el díptico, su segunda foto', async () => {
    const { stories } = (await resolveHome()).travel;
    expect(stories.map((s) => [s.article.slug, s.layout])).toEqual(
      travel.stories.map((s) => [s.article, s.layout]),
    );
    expect(stories[0].secondary?.id).toBe('ciudades-lisboa-alfama');
    expect(stories.slice(1).map((s) => s.secondary)).toEqual([null, null]);
  });

  it('omite las piezas cuyo artículo no existe', async () => {
    const { travel: data } = await resolveHome({ articles: [ARTICLES[0]] });
    expect(data.feature).not.toBeNull();
    expect(data.stories).toEqual([]);
  });

  it('el índice no usa la foto del hero como vista previa', async () => {
    const { index } = (await resolveHome()).travel;
    const stelvio = index.find((entry) => entry.id === STELVIO.id);
    expect(stelvio?.preview).toBeNull();
    expect(stelvio?.article?.href).toBe('/motor/paso-del-stelvio');
    expect(index.find((entry) => entry.id === 'tossa-de-mar')?.preview?.id).toBe(
      'viajes-tossa-vila-vella',
    );
  });
});

/** GSAP simulado para comprobar qué se anima en scroll. */
function fakeMotion(conditions: { reduce: boolean; mobile: boolean }) {
  const timeline = { to: vi.fn() };
  const gsap = {
    matchMedia: () => ({
      add: (_: unknown, setup: (context: { conditions: typeof conditions }) => void) =>
        setup({ conditions }),
      revert: vi.fn(),
    }),
    timeline: vi.fn(() => timeline),
    to: vi.fn(),
  };
  const api = { gsap, ScrollTrigger: {} } as unknown as MotionApi;
  const service: Partial<MotionService> = {
    load: () => Promise.resolve(api),
    clientNavigation: (() => false) as MotionService['clientNavigation'],
    duration: () => 0.5,
  };
  return { service, gsap, timeline };
}

function heroData(destination: Partial<Destination> = {}): HeroData {
  return {
    media: PHOTO,
    destination: { ...STELVIO, ...destination },
    kicker: composition.kicker,
    tagline: composition.tagline,
  };
}

@Component({
  imports: [HomeHero],
  template: `<app-home-hero [hero]="hero()" />`,
})
class Host {
  readonly hero = signal<HeroData>(heroData());
}

async function renderHero(
  hero = heroData(),
  conditions: { reduce: boolean; mobile: boolean } = { reduce: false, mobile: false },
) {
  const motion = fakeMotion(conditions);
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMediaDelivery(),
      { provide: MotionService, useValue: motion.service },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.hero.set(hero);
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve));
  const element = fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    element.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  const metaValues = () =>
    [...element.querySelectorAll('.hero__meta dd')].map((dd) =>
      dd.textContent?.replace(/\s+/g, ' ').trim(),
    );
  return { element, text, metaValues, ...motion };
}

describe('Hero de la portada', () => {
  it('titular en dos líneas con el apellido en cursiva', async () => {
    const { element } = await renderHero();
    const title = element.querySelector('h1#hero-title');
    const lines = [...(title?.querySelectorAll('.hero__line') ?? [])];
    expect(lines.map((line) => line.textContent?.trim())).toEqual(['Cristian', 'Atienza']);
    expect(lines[1].querySelector('em')?.textContent?.trim()).toBe('Atienza');
    expect(element.querySelector('section')?.getAttribute('aria-labelledby')).toBe('hero-title');
  });

  it('lema en tres líneas', async () => {
    const { element } = await renderHero();
    const lines = [...element.querySelectorAll('.hero__tagline span')];
    expect(lines.map((line) => line.textContent?.trim())).toEqual([
      'Explorando lugares.',
      'Capturando momentos.',
      'Viviendo experiencias.',
    ]);
  });

  it('metadatos del lugar desde el destino: coordenadas, altitud y país', async () => {
    const { element, metaValues } = await renderHero();
    expect(metaValues()).toEqual(['46.53° N · 10.45° E', '2757 m', 'Paso del Stelvio · Italia']);
    expect([...element.querySelectorAll('.hero__meta dt')].map((dt) => dt.textContent)).toEqual([
      'Coordenadas',
      'Altitud',
      'Lugar',
    ]);
  });

  it('sin altitud registrada no muestra esa línea', async () => {
    const { metaValues } = await renderHero(
      heroData({ elevation: undefined, region: 'Lombardía' }),
    );
    expect(metaValues()).toEqual(['46.53° N · 10.45° E', 'Paso del Stelvio · Lombardía · Italia']);
  });

  it('sin destino no muestra metadatos', async () => {
    const { element } = await renderHero({ ...heroData(), destination: null });
    expect(element.querySelector('.hero__meta')).toBeNull();
  });

  it('crédito con autor, fuente y licencia enlazadas', async () => {
    const { element, text } = await renderHero();
    expect(text('.hero__credit')).toBe('Foto: Autoría de prueba · Wikimedia Commons · CC0');
    const links = [...element.querySelectorAll('.hero__credit a')].map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toEqual([PHOTO.credit.sourceUrl, PHOTO.credit.licenseUrl]);
  });

  it('el crédito se divide en autor, fuente y licencia (tres líneas en móvil)', async () => {
    const photo: MediaAsset = {
      ...PHOTO,
      credit: { ...PHOTO.credit, author: 'Kallerna', license: 'CC-BY-SA-4.0' },
    };
    const { element, text } = await renderHero({ ...heroData(), media: photo });
    expect(text('.hero__credit')).toBe(
      'Foto: Kallerna · Wikimedia Commons · CC BY-SA 4.0 · adaptada',
    );
    const parts = [...element.querySelectorAll('.hero__credit > span')].map((part) =>
      part.textContent?.replace(/\s+/g, ' ').replace(/^ · /, '').trim(),
    );
    expect(parts).toEqual(['Foto: Kallerna', 'Wikimedia Commons', 'CC BY-SA 4.0 · adaptada']);
    expect(element.querySelectorAll('.hero__credit-sep')).toHaveLength(2);
  });

  it('la fotografía es la imagen prioritaria de la página, sin crédito duplicado', async () => {
    const { element } = await renderHero();
    const img = element.querySelector('.hero__media img') as HTMLImageElement;
    expect(img.getAttribute('fetchpriority')).toBe('high');
    expect(img.getAttribute('loading')).toBe('eager');
    expect(img.getAttribute('sizes')).toBe('(width < 640px) max(100vw, 116vh), max(100vw, 180vh)');
    expect(element.querySelector('.hero__media picture source')).toBeNull();
    expect(element.querySelector('.media__credit')).toBeNull();
  });

  it('la foto llena todo el alto del hero, sin el hueco reservado al pie de foto', async () => {
    const { element } = await renderHero();
    const media = element.querySelector('.hero__media app-media');
    expect(media?.hasAttribute('data-fill')).toBe(true);
    expect(media?.hasAttribute('data-flush')).toBe(true);
    expect(element.querySelector('.hero__media figcaption')).toBeNull();
  });

  it('el indicador de scroll lleva al contenido de la portada', async () => {
    const { element } = await renderHero();
    const cue = element.querySelector('a.hero__cue');
    expect(cue?.getAttribute('href')).toBe('/#portada');
    expect(cue?.getAttribute('aria-label')).toBe('Ir al contenido de la portada');
  });

  it('en scroll mueve la foto y desvanece el texto y los metadatos', async () => {
    const { gsap, timeline } = await renderHero();
    expect(gsap.timeline).toHaveBeenCalledTimes(1);
    expect(timeline.to).toHaveBeenCalledTimes(2);
    expect(gsap.to).toHaveBeenCalledTimes(1);
  });

  it('en móvil solo hay parallax suave de la foto', async () => {
    const { timeline } = await renderHero(heroData(), { reduce: false, mobile: true });
    expect(timeline.to).toHaveBeenCalledTimes(1);
    expect(timeline.to.mock.calls[0][1]).toEqual({ yPercent: 6, scale: 1 });
  });

  it('con reduced motion no anima nada en scroll', async () => {
    const { gsap } = await renderHero(heroData(), { reduce: true, mobile: false });
    expect(gsap.timeline).not.toHaveBeenCalled();
    expect(gsap.to).not.toHaveBeenCalled();
  });
});

@Component({
  imports: [HomeIntro],
  template: `<app-home-intro [intro]="intro" />`,
})
class IntroHost {
  readonly intro = { ...HOME_COMPOSITION.intro, media: photo(HOME_COMPOSITION.intro.media) };
}

describe('Presentación de la portada', () => {
  async function renderIntro() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideMediaDelivery(),
        { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
      ],
    });
    const fixture = TestBed.createComponent(IntroHost);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('titular con «Y código» en cursiva, entradilla y destino del indicador del hero', async () => {
    const element = await renderIntro();
    const section = element.querySelector('section');
    expect(section?.id).toBe('portada');
    expect(section?.querySelector('h2')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Lugares, luz y máquinas. Y código.',
    );
    expect(section?.querySelector('h2 em')?.textContent).toBe('Y código.');
    expect(section?.querySelector('.intro__lead')?.textContent?.trim()).toBe(
      'Un espacio personal sobre viajes, fotografía, motor, experiencias y tecnología.',
    );
    expect(section?.querySelector('app-eyebrow')?.textContent).toContain('00');
  });

  it('foto vertical recortada en carga diferida, con su crédito', async () => {
    const element = await renderIntro();
    const img = element.querySelector('.intro__photo img');
    expect(img?.getAttribute('loading')).toBe('lazy');
    expect(element.querySelector('.intro__photo .media__frame')?.getAttribute('style')).toContain(
      'aspect-ratio: 4/5',
    );
    expect(element.querySelector('.intro__photo .media__credit')?.textContent).toContain('Foto:');
  });

  it('accesos a las cinco secciones, cada uno con su acento', async () => {
    const element = await renderIntro();
    const links = [...element.querySelectorAll('.intro__sections a')];
    expect(links.map((a) => [a.getAttribute('href'), a.getAttribute('data-section')])).toEqual([
      ['/viajes', 'viajes'],
      ['/fotografia', 'fotografia'],
      ['/motor', 'motor'],
      ['/experiencias', 'experiencias'],
      ['/tecnologia', 'tecnologia'],
    ]);
  });
});
