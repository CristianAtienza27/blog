import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, provideRouter } from '@angular/router';
import type { Destination, Media as MediaAsset } from '../../content/models';
import { MediaRepository } from '../../content/repositories/media-repository';
import { TravelRepository } from '../../content/repositories/travel-repository';
import { provideMediaDelivery } from '../../core/media/media-delivery';
import { MotionApi, MotionService } from '../../motion/motion';
import { HomeHero } from './hero/home-hero';
import { HOME_COMPOSITION } from './home.composition';
import { HeroData, HomeData, homeResolver } from './home.resolver';

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

/** Repositorios simulados: la portada solo conoce las interfaces abstractas. */
async function resolveHome(content: { media?: MediaAsset | null } = {}): Promise<HomeData> {
  const media = {
    media: vi.fn(async () => (content.media === undefined ? PHOTO : content.media)),
  };
  const travel = { destination: vi.fn(async () => STELVIO) };
  TestBed.configureTestingModule({
    providers: [
      { provide: MediaRepository, useValue: media },
      { provide: TravelRepository, useValue: travel },
    ],
  });
  const data = await TestBed.runInInjectionContext(
    () =>
      homeResolver({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as Promise<HomeData>,
  );
  expect(media.media).toHaveBeenCalledWith(composition.media);
  expect(travel.destination).toHaveBeenCalledWith(composition.destination);
  return data;
}

describe('Resolver de la portada', () => {
  it('reúne la fotografía y el lugar del hero desde los repositorios', async () => {
    const { hero } = await resolveHome();
    expect(hero?.media).toBe(PHOTO);
    expect(hero?.destination).toBe(STELVIO);
    expect(hero?.kicker).toBe(composition.kicker);
    expect(hero?.tagline).toEqual(composition.tagline);
  });

  it('sin la fotografía, la portada se muestra sin hero', async () => {
    expect((await resolveHome({ media: null })).hero).toBeNull();
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
