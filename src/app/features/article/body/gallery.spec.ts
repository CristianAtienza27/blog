import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Article, ContentBlock, Gallery } from '../../../content/models';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { photo, summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { bodyItems } from './body-items';
import { galleryGroups, galleryPlan } from './gallery-layout';
import { viewerSizes } from './gallery-viewer';

// jsdom no implementa showModal()/close() ni scrollIntoView(): se simulan.
beforeAll(() => {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & Record<string, unknown>;
  proto.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
  Element.prototype.scrollIntoView ??= function () {
    /* sin desplazamiento en jsdom */
  };
});

const WIDE = photo('ancha', { width: 1600, height: 938, caption: 'Pie de la ancha' });
const SOFT = photo('blanda', { width: 1600, height: 1120 });
const CLASSIC = photo('clasica', { width: 2400, height: 1600, widths: [640, 1024, 1600, 2400] });

const gallery = (ids: string[] = [], overrides: Partial<Gallery> = {}): Gallery => ({
  id: 'serie',
  slug: 'serie',
  title: 'Serie de prueba',
  description: 'Descripción de la serie.',
  layout: 'grid',
  items: ids.length
    ? ids.map((id) => ({ media: photo(id) }))
    : [{ media: WIDE, caption: 'Primera' }, { media: SOFT }, { media: CLASSIC }],
  demo: true,
  ...overrides,
});

const article = (blocks: ContentBlock[]): Article => ({
  ...summary('relato', { cover: photo('portada') }),
  format: 'reportaje',
  blocks,
});

describe('Plan de una galería', () => {
  it('grupos «2 + 1» que se repiten, sin dejar nunca una fila de tres', () => {
    const shape = (n: number) => galleryGroups(n).map((g) => (g.kind === 'pair' ? 2 : 1));
    expect(shape(1)).toEqual([1]);
    expect(shape(2)).toEqual([2]);
    expect(shape(3)).toEqual([2, 1]);
    expect(shape(4)).toEqual([2, 2]);
    expect(shape(5)).toEqual([2, 1, 2]);
    expect(shape(6)).toEqual([2, 1, 2, 1]);
    expect(shape(7)).toEqual([2, 1, 2, 2]);
    for (let n = 1; n <= 12; n++) expect(shape(n).reduce((a, b) => a + b, 0)).toBe(n);
  });

  it('fotos en orden, numeradas, con nombre accesible y su proporción sin recortar', () => {
    const plan = galleryPlan(gallery());
    expect(plan.count).toBe('3 fotografías');
    expect(plan.items.map((i) => [i.position, i.number, i.label])).toEqual([
      [0, '01', 'Ampliar la fotografía 1 de 3'],
      [1, '02', 'Ampliar la fotografía 2 de 3'],
      [2, '03', 'Ampliar la fotografía 3 de 3'],
    ]);
    expect(plan.items.map((i) => i.ratio)).toEqual(['1600 / 938', '1600 / 1120', '2400 / 1600']);
    // El pie propio de la galería manda; si no, el de la foto.
    expect(plan.items.map((i) => i.caption)).toEqual(['Primera', SOFT.caption ?? null, null]);
  });

  it('pareja a la misma altura (columnas en proporción) y sueltas alternando el borde', () => {
    const plan = galleryPlan(gallery(['a', 'b', 'c', 'd', 'e', 'f']));
    expect(plan.groups.map((g) => [g.kind, g.align])).toEqual([
      ['pair', null],
      ['single', 'end'],
      ['pair', null],
      ['single', 'start'],
    ]);
    const [pair] = galleryPlan(gallery()).groups;
    expect(pair.columns).toBe('minmax(0, 1.7058fr) minmax(0, 1.4286fr)');
  });

  it('anchos pedidos: diapositiva 3:2 en móvil, su parte de la fila o la foto suelta', () => {
    const [wide, soft, classic] = galleryPlan(gallery()).items;
    // La ancha se recorta a 3:2 en el carrusel: se pide algo más ancha (×1,14).
    expect(wide.sizes).toBe(
      '(width < 640px) 96vw, (width < 900px) 619px, (width >= 1568px) 784px, 51vw',
    );
    expect(soft.sizes).toBe(
      '(width < 640px) 84vw, (width < 900px) 544px, (width >= 1568px) 657px, 42vw',
    );
    expect(classic.sizes).toBe(
      '(width < 640px) 84vw, (width < 900px) 544px, (width < 1200px) 66vw, 920px',
    );
  });

  it('visor: la foto al ancho que permite el alto libre, nunca más que la pantalla', () => {
    expect(viewerSizes(1600, 938)).toBe('min(100vw, calc((100vh - 11rem) * 1.706))');
    expect(viewerSizes(1200, 1600)).toBe('min(100vw, calc((100vh - 11rem) * 0.750))');
  });
});

describe('Galerías en el cuerpo (qué se muestra)', () => {
  const block = (g: Gallery): ContentBlock => ({ type: 'gallery', gallery: g });

  it('la serie completa (también la portada), sin las fotografías retiradas', () => {
    const [item] = bodyItems(article([block(gallery(['portada', 'motor-moto-trail', 'a']))]));
    expect(item.kind === 'gallery' && item.gallery.items.map((i) => i.media.id)).toEqual([
      'portada',
      'a',
    ]);
  });

  it('título h2 fuera de un capítulo y h3 dentro', () => {
    const items = bodyItems(
      article([
        block(gallery()),
        { type: 'chapter', id: 'uno', number: 1, title: 'Uno' },
        block(gallery()),
      ]),
    );
    expect(items.flatMap((i) => (i.kind === 'gallery' ? [i.level] : []))).toEqual([2, 3]);
  });

  it('con una sola foto visible se muestra individual; sin ninguna, nada', () => {
    const alone = gallery([]);
    alone.items = [{ media: photo('tecnologia-teclado') }, { media: SOFT, caption: 'Sola' }];
    expect(bodyItems(article([block(alone)]))).toEqual([
      { kind: 'figure', block: { type: 'image', layout: 'wide', media: SOFT, caption: 'Sola' } },
    ]);
    expect(bodyItems(article([block(gallery(['motor-moto-costa']))]))).toEqual([]);
  });
});

@Component({
  imports: [ArticleBody],
  template: `<app-article-body [article]="article()" />`,
})
class Host {
  readonly article = signal<Article>(article([]));
}

async function renderGallery(g: Gallery = gallery()) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMediaDelivery(),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.set(article([{ type: 'gallery', gallery: g }]));
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const buttons = () => [...element.querySelectorAll<HTMLButtonElement>('.gallery__open')];
  const dialog = () => element.querySelector<HTMLDialogElement>('dialog.viewer');
  const counter = () =>
    dialog()?.querySelector('.viewer__count')?.textContent?.replace(/\s+/g, ' ').trim();
  const key = (target: Element, k: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  return { fixture, element, buttons, dialog, counter, key };
}

describe('Galería (componente)', () => {
  it('región con nombre, título, número de fotos y una figura con pie y crédito por foto', async () => {
    const { element, buttons } = await renderGallery();
    const section = element.querySelector('app-gallery-block section');
    expect(section?.getAttribute('aria-label')).toBe('Galería: Serie de prueba');
    expect(section?.querySelector('h2.gallery__title')?.textContent).toBe('Serie de prueba');
    expect(section?.querySelector('.gallery__count')?.textContent).toBe('3 fotografías');
    expect(section?.querySelector('.gallery__position')?.textContent?.trim()).toBe('1 / 3');
    const figures = [...(section?.querySelectorAll('figure.gallery__item') ?? [])];
    expect(figures).toHaveLength(3);
    expect(figures.map((f) => f.querySelector('.caption__index')?.textContent)).toEqual([
      '01',
      '02',
      '03',
    ]);
    expect(
      figures.every((f) => f.querySelector('app-photo-credit')?.textContent?.includes('CC BY-SA')),
    ).toBe(true);
    // Cada foto se amplía con un botón con nombre propio; la imagen conserva su alt.
    expect(
      buttons().map((b) => [b.getAttribute('aria-label'), b.getAttribute('aria-haspopup')]),
    ).toEqual([
      ['Ampliar la fotografía 1 de 3', 'dialog'],
      ['Ampliar la fotografía 2 de 3', 'dialog'],
      ['Ampliar la fotografía 3 de 3', 'dialog'],
    ]);
    expect(figures[0].querySelector('img')?.getAttribute('alt')).toBe(WIDE.alt);
  });

  it('carga diferida: fotos lazy con AVIF y el hueco reservado; el visor no está hasta usarlo', async () => {
    const { element, dialog } = await renderGallery();
    const imgs = [...element.querySelectorAll('app-gallery-block img')];
    expect(imgs.every((i) => i.getAttribute('loading') === 'lazy')).toBe(true);
    expect(imgs.every((i) => i.getAttribute('fetchpriority') !== 'high')).toBe(true);
    expect(element.querySelectorAll('app-gallery-block source[type="image/avif"]')).toHaveLength(3);
    const photos = [...element.querySelectorAll<HTMLElement>('.gallery__photo')];
    expect(photos.map((p) => p.style.getPropertyValue('--ratio'))).toEqual([
      '1600 / 938',
      '1600 / 1120',
      '2400 / 1600',
    ]);
    expect(element.querySelector('app-gallery-viewer')).toBeNull();
    expect(dialog()).toBeNull();
  });

  it('flechas, Inicio y Fin pasan de una foto a otra', async () => {
    const { buttons, key } = await renderGallery();
    buttons()[0].focus();
    key(buttons()[0], 'ArrowRight');
    expect(document.activeElement).toBe(buttons()[1]);
    key(buttons()[1], 'End');
    expect(document.activeElement).toBe(buttons()[2]);
    key(buttons()[2], 'ArrowRight');
    expect(document.activeElement).toBe(buttons()[2]);
    key(buttons()[2], 'Home');
    expect(document.activeElement).toBe(buttons()[0]);
  });

  it('visor: se abre en la foto elegida, navega en bucle y se anuncia', async () => {
    const { fixture, buttons, dialog, counter, key } = await renderGallery();
    buttons()[1].focus();
    buttons()[1].click();
    await fixture.whenStable();
    const d = dialog();
    expect(d?.open).toBe(true);
    expect(d?.getAttribute('aria-label')).toBe('Visor de la galería Serie de prueba');
    expect(d?.classList).toContain('scheme-dark');
    expect(counter()).toBe('Fotografía 2 / de 3');
    // La foto entera, con su proporción, al tamaño del visor.
    const photoBox = d?.querySelector<HTMLElement>('.viewer__photo');
    expect(photoBox?.style.getPropertyValue('--ratio')).toBe('1600 / 1120');
    expect(d?.querySelector('img')?.getAttribute('sizes')).toContain('min(100vw');

    key(d as Element, 'ArrowRight');
    await fixture.whenStable();
    expect(counter()).toBe('Fotografía 3 / de 3');
    expect(d?.querySelector('[aria-live]')?.textContent).toBe('Fotografía 3 de 3');
    key(d as Element, 'ArrowRight');
    await fixture.whenStable();
    expect(counter()).toBe('Fotografía 1 / de 3');
    expect(d?.querySelector('[aria-live]')?.textContent).toBe('Fotografía 1 de 3: Primera');
    (d?.querySelector('.viewer__nav--prev') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(counter()).toBe('Fotografía 3 / de 3');
    key(d as Element, 'Home');
    await fixture.whenStable();
    expect(counter()).toBe('Fotografía 1 / de 3');
  });

  it('controles con nombre: cerrar, anterior y siguiente', async () => {
    const { fixture, buttons, dialog } = await renderGallery();
    buttons()[0].click();
    await fixture.whenStable();
    const d = dialog() as HTMLDialogElement;
    expect(d.querySelector('.viewer__close')?.textContent?.trim()).toBe('Cerrar');
    expect(d.querySelector('.viewer__close')?.hasAttribute('autofocus')).toBe(true);
    expect(
      [...d.querySelectorAll('.viewer__nav')].map((b) => b.getAttribute('aria-label')),
    ).toEqual(['Fotografía anterior', 'Fotografía siguiente']);
  });

  it('Esc y «Cerrar» cierran el visor y devuelven el foco a la foto que lo abrió', async () => {
    const { fixture, buttons, dialog } = await renderGallery();
    buttons()[2].focus();
    buttons()[2].click();
    await fixture.whenStable();
    const d = dialog() as HTMLDialogElement;
    (d.querySelector('.viewer__close') as HTMLElement).focus();
    d.dispatchEvent(new Event('cancel', { cancelable: true }));
    await fixture.whenStable();
    expect(d.open).toBe(false);
    expect(document.activeElement).toBe(buttons()[2]);

    // Se reabre (el visor ya está descargado) desde otra foto y se cierra con el botón.
    buttons()[0].click();
    await fixture.whenStable();
    expect(d.open).toBe(true);
    (d.querySelector('.viewer__close') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(d.open).toBe(false);
    expect(document.activeElement).toBe(buttons()[0]);
  });
});
