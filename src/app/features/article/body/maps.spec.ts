import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type {
  Article,
  ContentBlock,
  Destination,
  MapBlock as MapData,
  MapPoint,
  Trip,
} from '../../../content/models';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { photo, summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { bodyItems } from './body-items';
import { ChapterReading } from './chapter-reading';
import { mapNotice } from './map-block';
import { formatCoord, gridStep, mapKind, mapLayout } from './map-layout';
import { placesPlan, tripPlan } from './place-plan';

const place = (
  id: string,
  lat: number,
  lng: number,
  extra: Partial<Destination> = {},
): Destination => ({
  id,
  slug: id,
  name: id[0].toUpperCase() + id.slice(1),
  country: 'ES',
  region: 'Girona',
  location: { lat, lng },
  summary: `Descripción de ${id}.`,
  demo: true,
  ...extra,
});

const TOSSA = place('tossa', 41.7197, 2.933);
const CALELLA = place('calella', 41.8869, 3.1856);
const BEGUR = place('begur', 41.9542, 3.2077);
const CADAQUES = place('cadaques', 42.2889, 3.2779);
const STOPS = [TOSSA, CALELLA, BEGUR, CADAQUES];

const point = (d: Destination): MapPoint => ({
  label: d.name,
  location: d.location,
  slug: d.slug,
  destination: d,
});

const trip = (overrides: Partial<Trip> = {}): Trip => ({
  id: 'ruta',
  slug: 'ruta',
  title: 'Ruta de prueba',
  summary: 'Distancias aproximadas.',
  stops: STOPS.map((destination, i) => ({
    order: i + 1,
    destination,
    mode: 'coche',
    ...(i ? { distanceKm: [0, 42, 10, 65][i] } : {}),
  })),
  demo: true,
  ...overrides,
});

const mapBlock = (overrides: Partial<MapData> = {}): MapData => ({
  type: 'map',
  points: STOPS.map(point),
  route: true,
  caption: 'Recorrido de prueba.',
  trip: trip(),
  ...overrides,
});

const article = (blocks: ContentBlock[]): Article => ({
  ...summary('ruta-de-prueba', { cover: photo('portada') }),
  format: 'guia',
  blocks,
});

const chapter = (id: string, destination?: string, number?: number): ContentBlock => ({
  type: 'chapter',
  id,
  title: id,
  ...(number ? { number } : {}),
  ...(destination ? { destination } : {}),
});

describe('Cartografía: encuadre a partir de las coordenadas', () => {
  it('norte arriba y este a la derecha, con los puntos dentro del margen', () => {
    const { kind, markers } = mapLayout(STOPS.map(point), true);
    expect(kind).toBe('geographic');
    const [tossa, calella, , cadaques] = markers;
    expect(cadaques.y).toBeLessThan(calella.y);
    expect(calella.y).toBeLessThan(tossa.y);
    expect(tossa.x).toBeLessThan(cadaques.x);
    for (const m of markers) {
      expect(m.x).toBeGreaterThan(0.05);
      expect(m.x).toBeLessThan(0.95);
      expect(m.y).toBeGreaterThan(0.05);
      expect(m.y).toBeLessThan(0.95);
    }
  });

  it('proporción del encuadre acotada: ni columna ni tira', () => {
    // La Costa Brava es estrecha y alta: se ensancha hasta 0,8.
    expect(mapLayout(STOPS.map(point), true).ratio).toBeCloseTo(0.8, 5);
    // De Lisboa a Praga: apaisado dentro del límite.
    const europe = [place('lisboa', 38.72, -9.14), place('praga', 50.08, 14.44)].map(point);
    const { ratio } = mapLayout(europe, false);
    expect(ratio).toBeGreaterThan(0.8);
    expect(ratio).toBeLessThanOrEqual(1.6);
  });

  it('números en orden, coordenadas reales y ruta solo si se pide', () => {
    const route = mapLayout(STOPS.map(point), true);
    expect(route.markers.map((m) => m.number)).toEqual(['01', '02', '03', '04']);
    expect(route.markers[0].coords).toBe('41.72° N · 2.93° E');
    expect(route.markers.map((m) => m.destination)).toEqual([
      'tossa',
      'calella',
      'begur',
      'cadaques',
    ]);
    expect(route.path?.startsWith('M')).toBe(true);
    expect(route.path?.match(/L/g)).toHaveLength(3);
    expect(mapLayout(STOPS.map(point), false).path).toBeNull();
    expect(route.description).toBe(
      '4 paradas unidas en orden: 1. Tossa (41.72° N · 2.93° E); 2. Calella (41.89° N · 3.19° E); ' +
        '3. Begur (41.95° N · 3.21° E); 4. Cadaques (42.29° N · 3.28° E).',
    );
  });

  it('retícula en grados redondos, con el formato de la web', () => {
    expect(gridStep(0.8)).toBe(0.2);
    expect(gridStep(12)).toBe(5);
    expect(formatCoord(42.2, 'lat', 1)).toBe('42.2° N');
    expect(formatCoord(-9.14, 'lng')).toBe('9.14° O');
    expect(formatCoord(0, 'lng', 0)).toBe('0°');
    const { grid } = mapLayout(STOPS.map(point), true);
    expect(grid.filter((g) => g.axis === 'lat').map((g) => g.label)).toEqual([
      '41.8° N',
      '42.0° N',
      '42.2° N',
    ]);
    expect(grid.every((g) => g.at > 0 && g.at < 1)).toBe(true);
  });

  it('etiquetas hacia el centro y, si dos chocarían, una cambia de lado', () => {
    const { markers } = mapLayout(STOPS.map(point), true);
    const side = (id: string) => markers.find((m) => m.destination === id)?.side;
    expect(side('tossa')).toBe('end');
    // Calella y Begur quedan casi a la misma altura: no comparten lado.
    expect(side('calella')).not.toBe(side('begur'));
  });

  it('un solo lugar: encuadre de su entorno, centrado', () => {
    const { kind, markers, ratio } = mapLayout([point(TOSSA)], false);
    expect(kind).toBe('geographic');
    expect(markers[0].x).toBeCloseTo(0.5, 5);
    expect(markers[0].y).toBeCloseTo(0.5, 5);
    expect(ratio).toBeCloseTo(1.6, 5);
  });

  it('sin posiciones que separar: esquema identificado como tal, sin coordenadas', () => {
    const same = [point(TOSSA), { ...point(BEGUR), location: TOSSA.location }];
    expect(mapKind(same)).toBe('schematic');
    const layout = mapLayout(same, true);
    expect(layout.kind).toBe('schematic');
    expect(layout.grid).toEqual([]);
    expect(layout.markers.every((m) => m.coords === null)).toBe(true);
    expect(layout.description).not.toContain('°');
  });

  it('aviso: orientativo, no a escala y no sirve para navegar', () => {
    expect(mapNotice('geographic', true)).toBe(
      'Mapa orientativo, no a escala: posiciones aproximadas. Las líneas unen las paradas en orden; ' +
        'no siguen las carreteras. No sirve para navegar.',
    );
    expect(mapNotice('geographic', false)).toBe(
      'Mapa orientativo, no a escala: posiciones aproximadas. No sirve para navegar.',
    );
    expect(mapNotice('schematic', true)).toContain('Esquema sin escala');
  });
});

describe('Itinerario y lugares: solo los datos que existen', () => {
  const chapters = { tossa: { id: 'cap-tossa', number: 1 }, begur: { id: 'cap-begur', number: 3 } };

  it('paradas, distancia total y medio común; tramos desde la parada anterior', () => {
    const plan = tripPlan(trip(), chapters);
    expect(plan.facts).toEqual(['4 paradas', '117 km aprox.', 'en coche']);
    expect(plan.stops.map((s) => [s.number, s.name, s.leg])).toEqual([
      ['01', 'Tossa', null],
      ['02', 'Calella', '42 km'],
      ['03', 'Begur', '10 km'],
      ['04', 'Cadaques', '65 km'],
    ]);
    expect(plan.stops.map((s) => s.chapter?.id ?? null)).toEqual([
      'cap-tossa',
      null,
      'cap-begur',
      null,
    ]);
    expect(plan.stops[0]).toMatchObject({
      area: 'Girona · España',
      summary: 'Descripción de tossa.',
    });
  });

  it('sin una distancia, no hay total; con medios distintos, cada tramo lleva el suyo', () => {
    const t = trip();
    t.stops = t.stops.map((s, i) => ({
      ...s,
      mode: i === 3 ? 'barco' : 'coche',
      distanceKm: i === 2 ? undefined : s.distanceKm,
    }));
    const plan = tripPlan(t, {});
    expect(plan.facts).toEqual(['4 paradas']);
    expect(plan.stops.map((s) => s.leg)).toEqual([
      null,
      '42 km · en coche',
      'en coche',
      '65 km · en barco',
    ]);
  });

  it('la nota de la parada sustituye a la descripción del destino', () => {
    const t = trip();
    t.stops[1] = { ...t.stops[1], note: 'Nota propia de la parada.' };
    expect(tripPlan(t, {}).stops[1].summary).toBe('Nota propia de la parada.');
  });

  it('lugares: zona sin repetir el nombre y altitud si existe', () => {
    const lisboa = place('lisboa', 38.72, -9.14, {
      name: 'Lisboa',
      region: 'Lisboa',
      country: 'PT',
    });
    const paso = place('paso', 46.53, 10.45, {
      region: 'Lombardía',
      country: 'IT',
      elevation: 2757,
    });
    expect(placesPlan([lisboa, paso], { lisboa: { id: 'lisboa', number: 1 } })).toEqual([
      expect.objectContaining({
        number: '01',
        area: 'Portugal',
        chapter: { id: 'lisboa', number: 1 },
      }),
      expect.objectContaining({ number: '02', area: 'Lombardía · Italia · 2757 m', chapter: null }),
    ]);
  });
});

describe('Mapas en el cuerpo (qué se muestra)', () => {
  it('cada lugar enlaza con el capítulo que lo trata; el nivel del título depende del capítulo', () => {
    const items = bodyItems(
      article([
        mapBlock(),
        chapter('cap-tossa', 'tossa', 1),
        chapter('cap-begur', 'begur', 2),
        mapBlock({ trip: undefined, route: false }),
      ]),
    );
    const maps = items.flatMap((i) => (i.kind === 'map' ? [i] : []));
    expect(maps.map((m) => m.level)).toEqual([2, 3]);
    expect(maps[0].chapters).toEqual({
      tossa: { id: 'cap-tossa', number: 1 },
      begur: { id: 'cap-begur', number: 2 },
    });
    expect(maps[0].basePath).toBe('/viajes/ruta-de-prueba');
  });

  it('un mapa sin puntos no se muestra', () => {
    expect(bodyItems(article([mapBlock({ points: [] })]))).toEqual([]);
  });
});

@Component({
  imports: [ArticleBody],
  template: `<app-article-body [article]="article()" />`,
})
class Host {
  readonly article = signal<Article>(article([]));
}

async function render(blocks: ContentBlock[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMediaDelivery(),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.set(article(blocks));
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const reading = TestBed.inject(ChapterReading);
  return { fixture, element, reading };
}

describe('Mapa, itinerario y lugares (componentes)', () => {
  it('SVG con título y descripción, puntos numerados, línea de ruta y aviso', async () => {
    const { element } = await render([mapBlock()]);
    const svg = element.querySelector('app-map-block svg');
    expect(svg?.getAttribute('role')).toBe('img');
    expect(svg?.querySelector('title')?.textContent).toBe('Ruta de prueba');
    expect(svg?.querySelector('desc')?.textContent).toContain('4 paradas unidas en orden');
    expect(svg?.querySelectorAll('circle')).toHaveLength(4);
    expect(svg?.querySelector('path.map__route')).not.toBeNull();
    expect(svg?.querySelectorAll('line.map__grid').length).toBeGreaterThan(2);
    const markers = [...element.querySelectorAll('.map__marker')].map(
      (m) =>
        `${m.querySelector('.map__badge')?.textContent} ${m.querySelector('.map__label')?.textContent}`,
    );
    expect(markers).toEqual(['01 Tossa', '02 Calella', '03 Begur', '04 Cadaques']);
    // Las etiquetas del mapa ya están en el SVG (desc): no se leen dos veces.
    expect(element.querySelector('.map__overlay')?.getAttribute('aria-hidden')).toBe('true');
    expect(element.querySelector('.mapblock__note')?.textContent).toContain(
      'No sirve para navegar',
    );
    expect(element.querySelector('.mapblock__legend')).not.toBeNull();
  });

  it('itinerario: nombre, datos, paradas en orden con tramos y enlaces a sus capítulos', async () => {
    const { element } = await render([mapBlock(), chapter('cap-begur', 'begur', 3)]);
    const route = element.querySelector('app-trip-route section');
    expect(route?.getAttribute('aria-label')).toBe('Itinerario: Ruta de prueba');
    expect(route?.querySelector('h2')?.textContent).toBe('Ruta de prueba');
    expect(route?.querySelector('.route__facts')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      '4 paradas · 117 km aprox. · en coche',
    );
    const stops = [...(route?.querySelectorAll('li') ?? [])];
    expect(stops.map((s) => s.querySelector('.item__name')?.textContent?.trim())).toEqual([
      'Tossa',
      'Calella',
      'Begur',
      'Cadaques',
    ]);
    expect(stops[1].querySelector('.item__leg')?.textContent).toContain(
      'Desde la parada anterior: 42 km',
    );
    // Solo la parada con capítulo enlaza; el enlace nombra el capítulo y el lugar.
    const links = [...(route?.querySelectorAll('a') ?? [])];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/viajes/ruta-de-prueba#cap-begur']);
    expect(links[0].textContent?.replace(/\s+/g, ' ')).toContain('Capítulo 3');
    expect(links[0].querySelector('.visually-hidden')?.textContent).toBe(': Begur');
  });

  it('sin itinerario: lista de lugares con su zona y descripción', async () => {
    const { element } = await render([mapBlock({ trip: undefined, route: false })]);
    expect(element.querySelector('app-trip-route')).toBeNull();
    const list = element.querySelector('app-place-list section');
    expect(list?.getAttribute('aria-label')).toBe('Lugares del artículo');
    expect(list?.querySelector('.places__kicker')?.textContent).toBe('4 lugares');
    expect(list?.querySelectorAll('li')).toHaveLength(4);
    expect(list?.querySelector('.item__area')?.textContent).toBe('Girona · España');
    expect(element.querySelector('.mapblock__legend')).toBeNull();
  });

  it('el capítulo que se lee destaca su parada en el mapa y en la ruta (no solo con color)', async () => {
    const { fixture, element, reading } = await render([
      mapBlock(),
      chapter('cap-tossa', 'tossa', 1),
      chapter('cap-begur', 'begur', 2),
      chapter('otro', undefined, 3),
    ]);
    expect(element.querySelector('.is-active')).toBeNull();
    // Deja pasar el primer cálculo por posición (en jsdom no hay scroll: ningún capítulo).
    await new Promise((resolve) => setTimeout(resolve, 50));
    reading.active.set('cap-begur');
    await fixture.whenStable();
    expect(element.querySelector('.map__marker.is-active')?.textContent).toContain('Begur');
    expect(element.querySelector('circle.is-active')).not.toBeNull();
    const current = element.querySelector('app-trip-route [aria-current]');
    expect(current?.getAttribute('aria-current')).toBe('step');
    expect(current?.querySelector('.item__now')?.textContent).toBe('Leyendo');
    // Un capítulo sin lugar no destaca nada.
    reading.active.set('otro');
    await fixture.whenStable();
    // (En el mapa y la ruta: el índice del artículo sí marca ese capítulo.)
    expect(element.querySelector('app-map-block :is(.is-active, [aria-current])')).toBeNull();
  });
});

describe('Capítulo que se lee (ChapterReading)', () => {
  // jsdom: 768 px de alto, así que la línea de lectura queda a 307 px.
  function setup(tops: Record<string, number>) {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => {
      cb(0);
      return 0;
    });
    const root = document.createElement('div');
    for (const id of Object.keys(tops)) {
      const heading = document.createElement('app-chapter-heading');
      heading.innerHTML = `<h2 id="${id}" tabindex="-1">${id}</h2><p><a href="#">enlace ${id}</a></p>`;
      root.append(heading);
      (heading.querySelector('h2') as HTMLElement).getBoundingClientRect = () =>
        ({ top: tops[id] }) as DOMRect;
    }
    root.getBoundingClientRect = () => ({ bottom: 5000 }) as DOMRect;
    document.body.append(root);
    const reading = new ChapterReading();
    const stop = reading.start(root);
    const scroll = (next: Record<string, number>) => {
      Object.assign(tops, next);
      window.dispatchEvent(new Event('scroll'));
    };
    return { root, reading, stop, scroll };
  }

  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('por scroll: el capítulo en la línea de lectura, cuando el lector se detiene en él', () => {
    const { reading, stop, scroll } = setup({ uno: 900, dos: 2000 });
    expect(reading.active()).toBeNull();
    scroll({ uno: 200 });
    // Aún pasando por él: no cuenta hasta que se detiene.
    expect(reading.active()).toBeNull();
    vi.advanceTimersByTime(450);
    expect(reading.active()).toBe('uno');
    scroll({ uno: -800, dos: 100 });
    vi.advanceTimersByTime(450);
    expect(reading.active()).toBe('dos');
    stop();
  });

  it('al volver al mapa (fuera de los capítulos) se conserva el último, aunque se pase por otros', () => {
    const { reading, stop, scroll } = setup({ uno: -1800, dos: 100 });
    // Al arrancar (por ejemplo, con #dos en la URL) no hay espera.
    expect(reading.active()).toBe('dos');
    // Sube deprisa: pasa por «uno» unos milisegundos y llega por encima de los capítulos.
    scroll({ uno: 200, dos: 1300 });
    vi.advanceTimersByTime(80);
    scroll({ uno: 1200, dos: 2300 });
    vi.advanceTimersByTime(1000);
    expect(reading.active()).toBe('dos');
    stop();
  });

  it('un enlace directo (#capitulo) lo activa al instante', () => {
    const tops = { uno: 900, dos: 2000 };
    const { reading, stop } = setup(tops);
    // El navegador salta al ancla: el capítulo queda en la línea de lectura.
    Object.assign(tops, { uno: -1200, dos: 80 });
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    expect(reading.active()).toBe('dos');
    stop();
  });

  it('por teclado: el capítulo del elemento enfocado con el tabulador, al instante', () => {
    const { root, reading, stop } = setup({ uno: 900, dos: 2000 });
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab' }));
    root.querySelectorAll('a')[1].dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(reading.active()).toBe('dos');
    // Un foco sin tabulador (un clic) no cambia el capítulo: lo decide la posición.
    vi.advanceTimersByTime(5000);
    root.querySelectorAll('a')[0].dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(reading.active()).toBe('dos');
    stop();
  });

  it('al cerrar el artículo se borra, salvo que otro ya haya empezado su lectura', () => {
    const first = setup({ uno: 100 });
    expect(first.reading.active()).toBe('uno');
    first.stop();
    expect(first.reading.active()).toBeNull();
    // Dos sesiones solapadas (transición entre artículos): cerrar la vieja no borra la nueva.
    const reading = first.reading;
    const a = document.createElement('div');
    const b = document.createElement('div');
    b.innerHTML = '<app-chapter-heading><h2 id="nuevo">nuevo</h2></app-chapter-heading>';
    (b.querySelector('h2') as HTMLElement).getBoundingClientRect = () => ({ top: 50 }) as DOMRect;
    a.getBoundingClientRect = b.getBoundingClientRect = () => ({ bottom: 5000 }) as DOMRect;
    const stopOld = reading.start(a);
    const stopNew = reading.start(b);
    expect(reading.active()).toBe('nuevo');
    stopOld();
    expect(reading.active()).toBe('nuevo');
    stopNew();
  });
});
