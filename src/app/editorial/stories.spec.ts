import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideMediaDelivery } from '../core/media/media-delivery';
import { MotionService } from '../motion/motion';
import type { DestinationIndexEntry } from './destination-index';
import { DestinationIndex } from './destination-index/destination-index';
import type { StoryData } from './story';
import { Story } from './story/story';
import { StoryFeature } from './story-feature/story-feature';
import { destination, photo, ref, summary } from './testing/editorial-fixtures';

const TOSSA = destination('tossa-de-mar', 'Tossa de Mar');
const CADAQUES = destination('cadaques', 'Cadaqués');

const FEATURE: StoryData = {
  article: summary('itinerario-costa-brava', {
    title: 'Itinerario de ejemplo por la Costa Brava',
    cover: photo('viajes-cadaques-bahia'),
    destinations: [ref(TOSSA), ref(destination('begur', 'Begur')), ref(CADAQUES)],
  }),
  place: ref(CADAQUES),
};

@Component({
  imports: [StoryFeature, Story, DestinationIndex],
  template: `
    @if (feature(); as f) {
      <app-story-feature [story]="f" cta="Descubrir el itinerario" />
    }
    @if (story(); as s) {
      <app-story [story]="s" />
    }
    @if (entries(); as e) {
      <app-destination-index [entries]="e" />
    }
  `,
})
class Host {
  readonly feature = signal<StoryData | null>(null);
  readonly story = signal<StoryData | null>(null);
  readonly entries = signal<DestinationIndexEntry[] | null>(null);
}

/** Sin GSAP: el movimiento se prueba aparte. */
async function render(setup: (host: Host) => void) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMediaDelivery(),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  setup(fixture.componentInstance);
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    element.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  return { fixture, element, text };
}

describe('Historia principal', () => {
  it('lugar, región, título enlazado, entradilla y recorrido', async () => {
    const { element, text } = await render((host) => host.feature.set(FEATURE));
    expect(text('.feature__area')).toBe('Girona · España');
    expect(text('.feature__place')).toBe('Cadaqués');
    expect(element.querySelector('.feature__title a')?.getAttribute('href')).toBe(
      '/viajes/itinerario-costa-brava',
    );
    expect(text('.feature__meta')).toBe('Guía · 2 min · 3 paradas: de Tossa de Mar a Cadaqués');
  });

  it('el enlace de llamada no duplica la tabulación del título', async () => {
    const { element } = await render((host) => host.feature.set(FEATURE));
    const cta = element.querySelector('.feature__copy a[app-link]');
    expect(cta?.textContent?.trim()).toBe('Descubrir el itinerario');
    expect(cta?.getAttribute('tabindex')).toBe('-1');
    expect(cta?.getAttribute('aria-hidden')).toBe('true');
  });

  it('foto a sangre recortada: carga diferida, sin «auto» en sizes y crédito con licencia', async () => {
    const { element, text } = await render((host) => host.feature.set(FEATURE));
    const img = element.querySelector('.feature__frame img');
    expect(img?.getAttribute('loading')).toBe('lazy');
    expect(img?.getAttribute('fetchpriority')).not.toBe('high');
    expect(element.querySelector('.feature__frame source')?.getAttribute('sizes')).toBe(
      '(width < 640px) 200vw, 110vw',
    );
    expect(text('.feature__credit--over')).toBe(
      'Foto: Autoría de prueba · Wikimedia Commons · CC BY-SA 4.0 · adaptada',
    );
  });
});

describe('Piezas secundarias', () => {
  it('díptico: dos fotos, lugares tratados y sin región (son varias ciudades)', async () => {
    const lisboa = destination('lisboa', 'Lisboa', { country: 'PT' });
    const story: StoryData = {
      article: summary('ciudades-luz-de-tarde', {
        format: 'reportaje',
        destinations: [ref(lisboa), ref(destination('praga', 'Praga', { country: 'CZ' }))],
      }),
      place: ref(lisboa),
      layout: 'diptych',
      secondary: photo('ciudades-lisboa-alfama'),
    };
    const { element, text } = await render((host) => host.story.set(story));
    expect(element.querySelector('app-story')?.getAttribute('data-layout')).toBe('diptych');
    expect(element.querySelectorAll('app-story img')).toHaveLength(2);
    // Díptico real: dos fotos juntas, cada una con su crédito, y el texto debajo.
    const plates = [...element.querySelectorAll('.story__pair > .story__plate')];
    expect(plates.map((p) => p.classList.contains('story__plate--wide'))).toEqual([true, false]);
    expect(plates.every((p) => p.querySelector('app-media')?.hasAttribute('data-flush'))).toBe(
      true,
    );
    expect(plates.map((p) => p.querySelector('app-photo-credit')).filter(Boolean)).toHaveLength(2);
    expect(element.querySelector('.story__pair + .story__text--pair')).not.toBeNull();
    expect(text('.story__meta')).toBe('Reportaje · 2 min');
    expect(text('.story__places')).toBe('Lisboa · Praga');
    expect(text('.story__text a[app-link]')).toBe('Leer el reportaje');
  });

  it('retrato: un solo lugar, con su región y país', async () => {
    const cala = destination('cala', "Cala s'Almunia", { region: 'Mallorca' });
    const story: StoryData = {
      article: summary('calas-de-mallorca', { destinations: [ref(cala)] }),
      place: ref(cala),
      layout: 'portrait',
    };
    const { element, text } = await render((host) => host.story.set(story));
    expect(text('.story__meta')).toBe('Mallorca · España · Guía · 2 min');
    expect(element.querySelector('.story__places')).toBeNull();
    expect(element.querySelector('.story__portrait source')?.getAttribute('sizes')).toBe(
      '(width >= 1600px) 51vw, (width >= 900px) 68vw, 143vw',
    );
  });

  it('franja: panorámica a sangre con el crédito sobre la imagen y bajo ella en móvil', async () => {
    const story: StoryData = {
      article: summary('alicante-desde-el-puerto', { format: 'nota' }),
      place: null,
      layout: 'strip',
    };
    const { element, text } = await render((host) => host.story.set(story));
    expect(element.querySelector('.story__band app-media')?.hasAttribute('data-flush')).toBe(true);
    expect(element.querySelectorAll('app-photo-credit')).toHaveLength(2);
    expect(text('.story__text a[app-link]')).toBe('Leer la nota');
  });
});

describe('Índice de destinos (componente)', () => {
  const entries: DestinationIndexEntry[] = [
    {
      id: 'begur',
      number: '01',
      name: 'Begur',
      area: 'Girona · España',
      country: 'ES',
      article: { title: 'Itinerario', href: '/viajes/itinerario' },
      preview: null,
    },
    {
      id: 'tossa',
      number: '02',
      name: 'Tossa de Mar',
      area: 'Girona · España',
      country: 'ES',
      article: { title: 'Itinerario', href: '/viajes/itinerario' },
      preview: photo('viajes-tossa-vila-vella'),
    },
    {
      id: 'praga',
      number: '03',
      name: 'Praga',
      area: 'Bohemia · Chequia',
      country: 'CZ',
      article: null,
      preview: null,
    },
  ];

  async function renderIndex() {
    const result = await render((host) => host.entries.set(entries));
    const rows = [...result.element.querySelectorAll<HTMLElement>('.index__row')];
    const settle = () => result.fixture.whenStable();
    return { ...result, rows, settle };
  }

  it('filas numeradas con región y país; enlaza solo si hay publicación', async () => {
    const { element, rows, text } = await renderIndex();
    expect(rows.map((row) => row.tagName)).toEqual(['A', 'A', 'DIV']);
    expect(rows[0].getAttribute('href')).toBe('/viajes/itinerario');
    // Separadores ocultos: el nombre accesible no junta número, nombre y región.
    expect(rows[1].textContent?.replace(/\s+/g, ' ')).toContain(
      '02, Tossa de Mar, Girona · España, Itinerario',
    );
    expect(text('.index__summary')).toBe('3 destinos · 2 países');
    expect(element.querySelector('.index__frame')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('sin vistas previas en el HTML inicial: se cargan al activarse', async () => {
    const { element, rows, settle, text } = await renderIndex();
    expect(element.querySelectorAll('.index__photo')).toHaveLength(0);
    rows[1].dispatchEvent(new PointerEvent('pointerenter', { pointerType: 'mouse' }));
    await settle();
    expect(rows[1].classList).toContain('is-active');
    expect(element.querySelector('.index__photo.is-active img')).not.toBeNull();
    expect(text('.index__caption')).toContain('02 · Tossa de Mar');
    expect(text('.index__caption')).toContain('CC BY-SA 4.0');
  });

  it('el toque no activa la vista previa (navega directamente)', async () => {
    const { element, rows, settle } = await renderIndex();
    rows[1].dispatchEvent(new PointerEvent('pointerenter', { pointerType: 'touch' }));
    await settle();
    expect(element.querySelector('.index__row.is-active')).toBeNull();
  });

  it('también funciona con el teclado y se oculta al salir del índice', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    const { element, rows, settle } = await renderIndex();
    rows[1].dispatchEvent(new FocusEvent('focus'));
    await settle();
    expect(rows[1].classList).toContain('is-active');
    element
      .querySelector('section.index')
      ?.dispatchEvent(new FocusEvent('focusout', { relatedTarget: document.body }));
    await settle();
    expect(element.querySelector('.index__row.is-active')).toBeNull();
    vi.unstubAllGlobals();
  });

  it('un destino sin foto deja la vista previa en reposo', async () => {
    const { element, rows, settle } = await renderIndex();
    rows[0].dispatchEvent(new PointerEvent('pointerenter', { pointerType: 'mouse' }));
    await settle();
    expect(element.querySelector('.index__frame')?.classList).toContain('is-idle');
  });
});
