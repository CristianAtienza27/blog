import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Article, ContentBlock } from '../../../content/models';
import { summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { bodyItems } from './body-items';
import { isShortSpec } from './specs-block';

const CHAPTER: ContentBlock = {
  type: 'chapter',
  id: 'tossa-de-mar',
  number: 1,
  title: 'Tossa de Mar',
  kicker: 'Murallas junto al mar',
};
const SPECS: ContentBlock = {
  type: 'specs',
  title: 'Datos del itinerario',
  items: [
    { label: 'Etapas', value: '4' },
    { label: 'Distancia total', value: '117', unit: 'km' },
    { label: 'Época recomendada', value: 'Primavera y otoño' },
  ],
  note: 'Distancias aproximadas.',
};

function article(blocks: ContentBlock[], overrides: Partial<Article> = {}): Article {
  return { ...summary('costa'), format: 'reportaje', blocks, ...overrides };
}

describe('Bloques del cuerpo (orden y jerarquía)', () => {
  it('h2 antes del primer capítulo y h3 dentro; la ficha sigue la misma regla', () => {
    const items = bodyItems(
      article([
        { type: 'text', html: '<h3>Antes</h3>' },
        SPECS,
        CHAPTER,
        { type: 'text', html: '<h3>Dentro</h3>' },
        SPECS,
      ]),
    );
    expect(items.map((item) => item.kind)).toEqual(['prose', 'specs', 'chapter', 'prose', 'specs']);
    expect(items[0]).toEqual({ kind: 'prose', html: '<h2>Antes</h2>' });
    expect(items[1]).toMatchObject({ level: 2, headingId: 'datos-1' });
    expect(items[3]).toEqual({ kind: 'prose', html: '<h3>Dentro</h3>' });
    expect(items[4]).toMatchObject({ level: 3, headingId: 'datos-4' });
  });

  it('ids de las fichas únicos aunque un capítulo use el mismo (contenido sin validar)', () => {
    const clash: ContentBlock = { type: 'chapter', id: 'datos-1', title: 'Choque' };
    const clash2: ContentBlock = { type: 'chapter', id: 'datos-1-2', title: 'Otro choque' };
    const a = article([clash, SPECS, clash2, SPECS]);
    const ids = (items: ReturnType<typeof bodyItems>) =>
      items.flatMap((item) => (item.kind === 'specs' ? [item.headingId] : []));
    // La ficha del bloque 1 no puede ser «datos-1» ni «datos-1-2»: ya son de capítulos.
    expect(ids(bodyItems(a))).toEqual(['datos-1-3', 'datos-3']);
    const all = [...ids(bodyItems(a)), 'datos-1', 'datos-1-2'];
    expect(new Set(all).size).toBe(all.length);
  });

  it('ids deterministas: solo dependen del artículo (servidor e hidratación coinciden)', () => {
    const a = article([CHAPTER, SPECS, SPECS, { type: 'chapter', id: 'datos-2', title: 'X' }]);
    expect(bodyItems(a)).toEqual(bodyItems(a));
    expect(bodyItems(structuredClone(a))).toEqual(bodyItems(a));
  });

  it('capitular solo en el primer texto de un formato narrativo', () => {
    const blocks: ContentBlock[] = [
      { type: 'text', html: '<p>Uno.</p>' },
      { type: 'text', html: '<p>Dos.</p>' },
    ];
    const prose = (a: Article) =>
      bodyItems(a).map((item) => (item.kind === 'prose' ? item.html : ''));
    expect(prose(article(blocks))).toEqual(['<p class="has-dropcap">Uno.</p>', '<p>Dos.</p>']);
    expect(prose(article(blocks, { format: 'nota' }))).toEqual(['<p>Uno.</p>', '<p>Dos.</p>']);
    expect(prose(article(blocks, { format: 'ficha' }))).toEqual(['<p>Uno.</p>', '<p>Dos.</p>']);
  });

  it('un mapa sin puntos se omite; el código se muestra en su sitio, tal cual', () => {
    const code: ContentBlock = { type: 'code', language: 'ts', code: 'x' };
    const items = bodyItems(
      article([
        { type: 'map', points: [], route: false },
        code,
        { type: 'divider', variant: 'line' },
      ]),
    );
    expect(items.map((item) => item.kind)).toEqual(['code', 'divider']);
    expect(items[0]).toEqual({ kind: 'code', block: code });
  });

  it('valores cortos destacados; largos a lo ancho', () => {
    expect(isShortSpec({ label: 'Altitud', value: '2757', unit: 'm' })).toBe(true);
    expect(isShortSpec({ label: 'Tramo', value: '25', unit: 'km aprox.' })).toBe(true);
    expect(isShortSpec({ label: 'Apertura', value: 'De finales de primavera a otoño' })).toBe(
      false,
    );
  });
});

@Component({
  imports: [ArticleBody],
  template: `<app-article-body [article]="article()" />`,
})
class Host {
  readonly article = signal<Article>(article([]));
}

async function renderBody(blocks: ContentBlock[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.set(article(blocks));
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const text = (selector: string) =>
    element.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  return { element, text };
}

describe('Cuerpo del artículo (componentes)', () => {
  it('capítulo: ancla en el título, enfocable, número visual y nombre accesible completo', async () => {
    const { element, text } = await renderBody([CHAPTER]);
    const title = element.querySelector('h2');
    expect(title?.id).toBe('tossa-de-mar');
    expect(title?.getAttribute('tabindex')).toBe('-1');
    expect(text('h2')).toBe('Capítulo 1. Tossa de Mar');
    expect(element.querySelector('.chapter__number')?.getAttribute('aria-hidden')).toBe('true');
    expect(text('.chapter__number')).toBe('01');
    // hgroup: título y antetítulo juntos; el antetítulo no es otro encabezado.
    expect(text('hgroup > p')).toBe('Murallas junto al mar');
  });

  it('cita destacada con su variante y, si la hay, su autoría', async () => {
    const { element, text } = await renderBody([
      { type: 'quote', text: 'Las distancias se miden en calas.', variant: 'pull' },
      { type: 'quote', text: 'Cita en el texto.', cite: 'Fuente', variant: 'block' },
    ]);
    const quotes = element.querySelectorAll('app-quote-block');
    expect([...quotes].map((q) => q.getAttribute('data-variant'))).toEqual(['pull', 'block']);
    expect(quotes[0].classList).toContain('body__wide');
    expect(text('app-quote-block blockquote')).toBe('Las distancias se miden en calas.');
    expect(quotes[0].querySelector('figcaption')).toBeNull();
    expect(quotes[1].querySelector('figcaption')?.textContent?.trim()).toBe('Fuente');
  });

  it('separador: <hr> con su variante', async () => {
    const { element } = await renderBody([{ type: 'divider', variant: 'ornament' }]);
    expect(element.querySelector('app-divider-block')?.getAttribute('data-variant')).toBe(
      'ornament',
    );
    expect(element.querySelector('app-divider-block hr')).not.toBeNull();
  });

  it('ficha: sección con título, lista de definiciones y nota; solo los datos que hay', async () => {
    const { element, text } = await renderBody([SPECS]);
    const section = element.querySelector('app-specs-block section');
    expect(section?.getAttribute('aria-labelledby')).toBe('datos-0');
    expect(element.querySelector('h2#datos-0')?.textContent).toBe('Datos del itinerario');
    const items = [...element.querySelectorAll('.specs__item')];
    expect(items).toHaveLength(3);
    expect(items.map((item) => item.querySelector('dt')?.textContent?.trim())).toEqual([
      'Etapas',
      'Distancia total',
      'Época recomendada',
    ]);
    expect(items[1].querySelector('dd')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('117 km');
    expect(items.map((item) => item.classList.contains('specs__item--long'))).toEqual([
      false,
      false,
      true,
    ]);
    expect(text('.specs__note')).toBe('Distancias aproximadas.');
  });

  it('ficha cuyo id choca con un capítulo: ids únicos y la sección sigue nombrada', async () => {
    const { element } = await renderBody([
      { type: 'chapter', id: 'datos-1', title: 'Choque' },
      SPECS,
    ]);
    const ids = [...element.querySelectorAll('[id]')].map((node) => node.id);
    expect(new Set(ids).size).toBe(ids.length);
    const labelledBy = element
      .querySelector('app-specs-block section')
      ?.getAttribute('aria-labelledby');
    expect(labelledBy).toBe('datos-1-2');
    expect(element.querySelector(`#${labelledBy}`)?.textContent).toBe('Datos del itinerario');
    // El ancla del capítulo sigue apuntando al capítulo.
    expect(element.querySelector('#datos-1')?.tagName).toBe('H2');
    expect(element.querySelector('#datos-1')?.closest('app-chapter-heading')).not.toBeNull();
  });

  it('ficha sin título: nombrada para los lectores de pantalla, sin encabezado vacío', async () => {
    const { element } = await renderBody([{ type: 'specs', items: [{ label: 'A', value: '1' }] }]);
    expect(element.querySelector('app-specs-block section')?.getAttribute('aria-label')).toBe(
      'Datos',
    );
    expect(element.querySelector('app-specs-block :is(h2, h3)')).toBeNull();
    expect(element.querySelector('.specs__note')).toBeNull();
  });

  it('texto: enlaces y anclas dentro de la columna de lectura', async () => {
    const { element } = await renderBody([
      CHAPTER,
      { type: 'text', html: '<p>Ir a <a href="#tossa-de-mar">Tossa</a>.</p>' },
    ]);
    expect(element.querySelector('app-prose-block a')?.getAttribute('href')).toBe(
      '/viajes/costa#tossa-de-mar',
    );
  });
});
