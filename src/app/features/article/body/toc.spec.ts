import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Article, ContentBlock } from '../../../content/models';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { photo, summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { tocEntries } from './article-toc';
import { ChapterReading } from './chapter-reading';
import { readingProgress } from './reading-progress';

const chapter = (id: string, title: string, number?: number): ContentBlock => ({
  type: 'chapter',
  id,
  title,
  ...(number ? { number } : {}),
});
const text = (html = '<p>Texto.</p>'): ContentBlock => ({ type: 'text', html });

const article = (blocks: ContentBlock[]): Article => ({
  ...summary('relato', { cover: photo('portada') }),
  format: 'reportaje',
  blocks,
});

const CHAPTERS: ContentBlock[] = [
  text(),
  chapter('uno', 'Primer capítulo', 1),
  text(),
  chapter('dos', 'Segundo capítulo', 2),
  text(),
  chapter('epilogo', 'Epílogo'),
  text(),
];

describe('Índice: capítulos del artículo', () => {
  it('en orden, con su número (dos cifras) y su título; sin número si no lo tienen', () => {
    expect(tocEntries(CHAPTERS)).toEqual([
      { id: 'uno', number: '01', title: 'Primer capítulo' },
      { id: 'dos', number: '02', title: 'Segundo capítulo' },
      { id: 'epilogo', number: null, title: 'Epílogo' },
    ]);
    expect(tocEntries([text()])).toEqual([]);
  });
});

describe('Barra de lectura: progreso del cuerpo, no de la página', () => {
  it('0 con el inicio del cuerpo arriba, 1 con su final abajo', () => {
    // Cuerpo de 3000 px en una pantalla de 800: recorrido de 2200 px.
    expect(readingProgress(500, 3000, 800)).toBe(0);
    expect(readingProgress(0, 3000, 800)).toBe(0);
    expect(readingProgress(-1100, 3000, 800)).toBe(0.5);
    expect(readingProgress(-2200, 3000, 800)).toBe(1);
    expect(readingProgress(-4000, 3000, 800)).toBe(1);
  });

  it('un cuerpo más corto que la pantalla: completo en cuanto empieza', () => {
    expect(readingProgress(200, 600, 800)).toBe(0);
    expect(readingProgress(-10, 600, 800)).toBe(1);
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
  // Deja pasar la primera lectura por posición (en jsdom no hay scroll: ningún capítulo).
  await new Promise((resolve) => setTimeout(resolve, 50));
  return {
    fixture,
    element: fixture.nativeElement as HTMLElement,
    reading: TestBed.inject(ChapterReading),
  };
}

describe('Índice del artículo (componente)', () => {
  it('desplegable y lateral: «En este artículo», capítulos numerados con enlace a su ancla', async () => {
    const { element } = await render(CHAPTERS);
    const toc = element.querySelector('app-article-toc');
    // Antes del cuerpo, para que en móvil quede entre la cabecera y el texto.
    expect(toc?.nextElementSibling?.classList).toContain('body');
    const navs = [...(toc?.querySelectorAll('nav') ?? [])];
    expect(navs.map((n) => n.getAttribute('aria-label'))).toEqual([
      'En este artículo',
      'En este artículo',
    ]);
    const summaryText = toc?.querySelector('details > summary')?.textContent?.replace(/\s+/g, ' ');
    expect(summaryText?.trim()).toBe('En este artículo: 3 capítulos');
    for (const nav of navs) {
      const links = [...nav.querySelectorAll('a')];
      expect(links.map((a) => a.getAttribute('href'))).toEqual([
        '/viajes/relato#uno',
        '/viajes/relato#dos',
        '/viajes/relato#epilogo',
      ]);
      expect(links.map((a) => a.querySelector('.toc__number')?.textContent ?? null)).toEqual([
        '01',
        '02',
        null,
      ]);
      // El número es decorativo: el lector de pantalla oye el título.
      expect(links[0].querySelector('.toc__number')?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('refleja el capítulo que se lee (ChapterReading) con aria-current, en las dos vistas', async () => {
    const { fixture, element, reading } = await render(CHAPTERS);
    expect(element.querySelector('app-article-toc [aria-current]')).toBeNull();
    reading.active.set('dos');
    await fixture.whenStable();
    const current = [...element.querySelectorAll('app-article-toc [aria-current]')];
    expect(current).toHaveLength(2);
    expect(current.every((a) => a.getAttribute('aria-current') === 'true')).toBe(true);
    expect(current.every((a) => a.getAttribute('href') === '/viajes/relato#dos')).toBe(true);
  });

  it('el desplegable se cierra al elegir un capítulo', async () => {
    const { element } = await render(CHAPTERS);
    const details = element.querySelector('details') as HTMLDetailsElement;
    details.open = true;
    const link = details.querySelectorAll('a')[1];
    // Sin navegación real en jsdom: solo interesa el cierre.
    link.addEventListener('click', (event) => event.preventDefault());
    link.click();
    expect(details.open).toBe(false);
  });

  it('sin índice con menos de dos capítulos; la barra de lectura, en todos', async () => {
    const { element } = await render([text(), chapter('uno', 'Único', 1), text()]);
    expect(element.querySelector('app-article-toc')).toBeNull();
    const progress = element.querySelector('app-reading-progress');
    expect(progress).not.toBeNull();
    expect(progress?.getAttribute('aria-hidden')).toBe('true');
  });

  it('un solo sistema de seguimiento: el índice lee el mismo estado que el mapa', async () => {
    const { element, reading } = await render(CHAPTERS);
    expect(element.querySelector('app-article-toc')).not.toBeNull();
    // ChapterReading es único en la aplicación: no hay otro por componente.
    expect(TestBed.inject(ChapterReading)).toBe(reading);
  });
});
