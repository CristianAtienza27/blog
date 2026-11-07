import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Article, ContentBlock, ImageBlock, Media } from '../../../content/models';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { photo, summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { bodyItems } from './body-items';
import { cropFactor, figurePlan, figuresPlan, mediaLocation, ratioValue } from './figure-layout';

const LANDSCAPE = photo('paisaje', { width: 2400, height: 1600, widths: [640, 1024, 1600, 2400] });
const SOFT = photo('blanda', { width: 1600, height: 1067 });
const TALL = photo('vertical', { width: 1200, height: 1600, widths: [640, 1024] });

const image = (extra: Partial<ImageBlock> = {}): ImageBlock => ({
  type: 'image',
  media: LANDSCAPE,
  layout: 'content',
  ...extra,
});

describe('Plan de una fotografía', () => {
  it('colocación según disposición y alineación', () => {
    expect(figurePlan(image()).placement).toBe('column');
    expect(figurePlan(image({ align: 'start' })).placement).toBe('side');
    expect(figurePlan(image({ layout: 'wide' })).placement).toBe('wide');
    expect(figurePlan(image({ layout: 'wide', align: 'end' })).placement).toBe('edge');
    expect(figurePlan(image({ layout: 'full' })).placement).toBe('full');
    // A sangre no se alinea: aunque llegue un align, se ignora.
    expect(figurePlan(image({ layout: 'full', align: 'start' })).align).toBeNull();
  });

  it('una vertical nunca pasa de un margen: a lo ancho mediría más que la pantalla', () => {
    const plan = figurePlan(image({ layout: 'full', ratio: '4/5' }));
    expect(plan).toMatchObject({ placement: 'side', align: 'end', orientation: 'portrait' });
    expect(figurePlan(image({ media: TALL, layout: 'wide', align: 'start' }))).toMatchObject({
      placement: 'side',
      align: 'start',
    });
    // En la columna de lectura se queda en la columna.
    expect(figurePlan(image({ ratio: '4/5' })).placement).toBe('column');
  });

  it('recorte: marco con su proporción, sizes ampliado por el recorte y sin «auto»', () => {
    const plain = figurePlan(image());
    expect(plain).toMatchObject({ ratio: null, cropped: false });
    expect(plain.sizes).toBe('(width < 900px) 90vw, (width < 1200px) 62vw, 600px');
    const cropped = figurePlan(image({ ratio: '4/5', align: 'end' }));
    expect(cropped).toMatchObject({ ratio: '4/5', cropped: true });
    // Una foto 3:2 en un marco 4:5 necesita 1,875 veces su ancho.
    expect(cropFactor(1.5, ratioValue('4/5'))).toBeCloseTo(1.875);
    expect(cropped.sizes).toBe('(width < 900px) 188vw, (width < 1200px) 87vw, 1125px');
  });

  it('anchos de cada colocación (escritorio, tableta y móvil)', () => {
    expect(figurePlan(image({ align: 'start' })).sizes).toBe(
      '(width < 900px) 100vw, (width < 1200px) 62vw, 900px',
    );
    expect(figurePlan(image({ layout: 'wide', align: 'end' })).sizes).toBe(
      '(width < 900px) 100vw, (width < 1200px) 96vw, calc((50vw + 300px) * 1.000)',
    );
    expect(figurePlan(image({ layout: 'wide' })).sizes).toBe(
      '(width < 900px) 100vw, (width >= 1568px) 1440px, 92vw',
    );
    expect(figurePlan(image({ layout: 'full' })).sizes).toBe('100vw');
  });

  it('desenfoque de carga solo en fotos que no pasan de unos 1000 px', () => {
    expect(figurePlan(image()).blur).toBe(true);
    expect(figurePlan(image({ align: 'start' })).blur).toBe(true);
    expect(figurePlan(image({ layout: 'wide' })).blur).toBe(false);
    expect(figurePlan(image({ layout: 'full' })).blur).toBe(false);
  });

  it('punto focal del bloque, pie propio o el de la foto y lugar si la foto lo tiene', () => {
    const plan = figurePlan(image({ focal: { x: 0.2, y: 0.8 }, caption: 'Pie del artículo' }));
    expect(plan.media.focal).toEqual({ x: 0.2, y: 0.8 });
    expect(plan.media.id).toBe(LANDSCAPE.id);
    expect(plan.caption).toBe('Pie del artículo');
    expect(figurePlan(image({ media: { ...LANDSCAPE, caption: 'De la foto' } })).caption).toBe(
      'De la foto',
    );
    expect(mediaLocation(LANDSCAPE)).toBeNull();
    const located: Media = {
      ...LANDSCAPE,
      location: { name: 'Cadaqués', region: 'Girona', country: 'ES' },
    };
    expect(mediaLocation(located)).toBe('Cadaqués · Girona · España');
    expect(mediaLocation({ ...LANDSCAPE, location: { name: 'Sin país' } })).toBe('Sin país');
  });
});

describe('Plan de una composición', () => {
  it('díptico y tríptico: columnas en proporción a cada foto (misma altura)', () => {
    const plan = figuresPlan({
      type: 'figures',
      layout: 'triptych',
      items: [
        { media: LANDSCAPE, ratio: '3/2' },
        { media: LANDSCAPE, ratio: '4/5' },
        { media: SOFT },
      ],
    });
    expect(plan.columns).toBe(
      `minmax(0, 1.5000fr) minmax(0, 0.8000fr) minmax(0, ${(1600 / 1067).toFixed(4)}fr)`,
    );
    // La foto vertical recorta la 3:2 (factor 1,875) y ocupa su parte de la fila.
    const share = 0.8 / (1.5 + 0.8 + 1600 / 1067);
    expect(plan.items[1].sizes).toBe(
      `(width < 640px) ${Math.ceil(82 * 1.875)}vw, (width >= 1568px) ${Math.ceil(1440 * share * 1.875)}px, ${Math.ceil(92 * share * 1.875)}vw`,
    );
    expect(plan.items.map((item) => item.cropped)).toEqual([false, true, false]);
    // Sin recorte, el marco es el de la propia foto.
    expect(plan.items[2].ratio).toBe('1600 / 1067');
  });

  it('secuencia: numerada y con el ancho de su escalera', () => {
    const plan = figuresPlan({
      type: 'figures',
      layout: 'sequence',
      items: [{ media: LANDSCAPE }, { media: LANDSCAPE, focal: { x: 0, y: 1 } }],
    });
    expect(plan.items.map((item) => item.index)).toEqual(['01', '02']);
    expect(plan.items[0].sizes).toBe('(width < 640px) 86vw, (width < 1200px) 62vw, 800px');
    expect(plan.items[1].media.focal).toEqual({ x: 0, y: 1 });
  });
});

const article = (blocks: ContentBlock[], overrides: Partial<Article> = {}): Article => ({
  ...summary('relato', { cover: photo('portada') }),
  format: 'reportaje',
  blocks,
  ...overrides,
});

describe('Fotografías en el cuerpo (qué se muestra)', () => {
  it('nunca una fotografía retirada ni la de la portada', () => {
    const items = bodyItems(
      article([
        image({ media: photo('motor-moto-trail') }),
        image({ media: photo('portada') }),
        image(),
      ]),
    );
    expect(items).toEqual([{ kind: 'figure', block: image() }]);
  });

  it('una composición sin las fotos suficientes se degrada sin romperse', () => {
    const triptych: ContentBlock = {
      type: 'figures',
      layout: 'triptych',
      items: [{ media: photo('a') }, { media: photo('tecnologia-teclado') }, { media: photo('b') }],
    };
    expect(bodyItems(article([triptych]))[0]).toMatchObject({
      kind: 'figures',
      block: { layout: 'diptych', items: [{ media: { id: 'a' } }, { media: { id: 'b' } }] },
    });
    const alone: ContentBlock = {
      type: 'figures',
      layout: 'sequence',
      items: [{ media: photo('portada') }, { media: photo('c'), ratio: '4/5', caption: 'Pie' }],
    };
    expect(bodyItems(article([alone]))).toEqual([
      {
        kind: 'figure',
        block: { type: 'image', layout: 'wide', media: photo('c'), ratio: '4/5', caption: 'Pie' },
      },
    ]);
    const none: ContentBlock = {
      type: 'figures',
      layout: 'diptych',
      items: [{ media: photo('portada') }, { media: photo('motor-moto-costa') }],
    };
    expect(bodyItems(article([none]))).toEqual([]);
  });

  it('más de tres fotos (contenido sin validar): solo las tres primeras', () => {
    const many: ContentBlock = {
      type: 'figures',
      layout: 'sequence',
      items: ['a', 'b', 'c', 'd'].map((id) => ({ media: photo(id) })),
    };
    const [item] = bodyItems(article([many]));
    expect(item.kind === 'figures' && item.block.items.map((i) => i.media.id)).toEqual([
      'a',
      'b',
      'c',
    ]);
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
      provideMediaDelivery(),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.set(article(blocks));
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Fotografías en el cuerpo (componentes)', () => {
  it('fotografía: colocación en atributos, carga diferida, AVIF y crédito con licencia', async () => {
    const element = await renderBody([image({ ratio: '4/5', align: 'end', caption: 'Pie' })]);
    const host = element.querySelector('app-figure-block');
    expect(host?.getAttribute('data-placement')).toBe('side');
    expect(host?.getAttribute('data-align')).toBe('end');
    expect(host?.getAttribute('data-orientation')).toBe('portrait');
    const img = host?.querySelector('img');
    expect(img?.getAttribute('loading')).toBe('lazy');
    expect(img?.getAttribute('fetchpriority')).not.toBe('high');
    expect(img?.getAttribute('alt')).toBe(LANDSCAPE.alt);
    // Recortada: el <source> AVIF pide el ancho del recorte, sin «auto».
    const source = host?.querySelector('source[type="image/avif"]');
    expect(source?.getAttribute('sizes')).toBe(
      figurePlan(image({ ratio: '4/5', align: 'end' })).sizes,
    );
    // El hueco se reserva con la proporción del marco (sin saltos al cargar).
    const frame = host?.querySelector('.media__frame') as HTMLElement;
    expect(frame.style.aspectRatio.replace(/\s/g, '')).toBe('4/5');
    const caption = host?.querySelector('figure.fig > figcaption');
    expect(caption?.textContent).toContain('Pie');
    expect(caption?.querySelector('app-photo-credit')?.textContent).toContain('CC BY-SA 4.0');
  });

  it('díptico: una figura por foto, cada una con su pie, y columnas proporcionales', async () => {
    const element = await renderBody([
      {
        type: 'figures',
        layout: 'diptych',
        items: [
          { media: LANDSCAPE, caption: 'Primera' },
          { media: SOFT, ratio: '1/1' },
        ],
      },
    ]);
    const host = element.querySelector('app-figures-block');
    expect(host?.getAttribute('data-layout')).toBe('diptych');
    const figures = [...(host?.querySelectorAll('figure.figs__item') ?? [])];
    expect(figures).toHaveLength(2);
    expect(figures.map((f) => f.querySelector('.caption__text')?.textContent)).toEqual([
      'Primera',
      SOFT.caption ?? undefined,
    ]);
    expect(figures.every((f) => f.querySelector('app-photo-credit'))).toBe(true);
    expect(
      (host?.querySelector('.figs') as HTMLElement).style.getPropertyValue('--figs-columns'),
    ).toBe('minmax(0, 1.5000fr) minmax(0, 1.0000fr)');
    expect(
      [...(host?.querySelectorAll('img') ?? [])].every((i) => i.getAttribute('loading') === 'lazy'),
    ).toBe(true);
    // Sin numeración fuera de las secuencias.
    expect(host?.querySelector('.caption__index')).toBeNull();
  });

  it('secuencia: fotos numeradas en orden', async () => {
    const element = await renderBody([
      { type: 'figures', layout: 'sequence', items: [{ media: LANDSCAPE }, { media: SOFT }] },
    ]);
    expect(
      [...element.querySelectorAll('app-figures-block .caption__index')].map((n) => n.textContent),
    ).toEqual(['01', '02']);
  });
});
