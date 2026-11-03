import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { Media as MediaAsset } from '../../../content/models';
import { provideMediaDelivery } from '../../../core/media/media-delivery';
import { Media, addAvifPreload } from './media';

const photo: MediaAsset = {
  id: 'cala',
  path: 'viajes/cala.0a1b2c3d',
  width: 1600,
  height: 1067,
  widths: [640, 1024, 1600],
  alt: 'Barcas en una cala de agua transparente',
  focal: { x: 0.3, y: 0.6 },
  placeholder: 'data:image/webp;base64,UklGRhIAAABXRUJQVlA4TAYAAAAvAAAAAAfQ//73v/+BiOh/AAA=',
  credit: {
    author: 'Autoría de prueba',
    source: 'wikimedia',
    sourceUrl: 'https://commons.wikimedia.org/wiki/File:Cala.jpg',
    license: 'CC-BY-SA-4.0',
    licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
  },
  demo: true,
};

@Component({
  imports: [Media],
  template: `<app-media
    [image]="image()"
    sizes="(width >= 900px) 50vw, 100vw"
    caption="Una cala"
  />`,
})
class Host {
  readonly image = signal<MediaAsset>(photo);
}

async function render(image = photo) {
  TestBed.configureTestingModule({ providers: [provideMediaDelivery()] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.image.set(image);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('app-media con fotografía optimizada', () => {
  it('genera un <picture> con AVIF y WebP en los anchos disponibles', async () => {
    const element = await render();
    const source = element.querySelector('picture source');
    expect(source?.getAttribute('type')).toBe('image/avif');
    expect(source?.getAttribute('srcset')).toBe(
      '/media/viajes/cala.0a1b2c3d-640.avif 640w, /media/viajes/cala.0a1b2c3d-1024.avif 1024w, /media/viajes/cala.0a1b2c3d-1600.avif 1600w',
    );
    const img = element.querySelector('picture img') as HTMLImageElement;
    expect(img.getAttribute('srcset')).toContain('/media/viajes/cala.0a1b2c3d-1024.webp 1024w');
    expect(img.getAttribute('srcset')).not.toContain('2400');
    expect(img.getAttribute('src')).toBe('/media/viajes/cala.0a1b2c3d-1600.webp');
  });

  it('carga diferida, texto alternativo, punto focal y sizes coherentes', async () => {
    const element = await render();
    const img = element.querySelector('picture img') as HTMLImageElement;
    expect(img.getAttribute('loading')).toBe('lazy');
    expect(img.getAttribute('alt')).toBe(photo.alt);
    expect(img.style.objectPosition).toBe('30% 60%');
    expect(img.getAttribute('sizes')).toBe('auto, (width >= 900px) 50vw, 100vw');
    expect(element.querySelector('source')?.getAttribute('sizes')).toBe(img.getAttribute('sizes'));
  });

  it('muestra el crédito con enlaces a la fuente y a la licencia', async () => {
    const element = await render();
    const credit = element.querySelector('.media__credit');
    expect(credit?.textContent?.replace(/\s+/g, ' ')).toContain(
      'Foto: Autoría de prueba · Wikimedia Commons · CC BY-SA 4.0 · adaptada',
    );
    const links = [...(credit?.querySelectorAll('a') ?? [])].map((a) => a.getAttribute('href'));
    expect(links).toEqual([photo.credit.sourceUrl, photo.credit.licenseUrl]);
  });

  it('en una foto propia el crédito es solo el autor', async () => {
    const element = await render({
      ...photo,
      credit: { author: 'Cristian Atienza', source: 'propia', license: 'derechos-reservados' },
    });
    expect(element.querySelector('.media__credit')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Foto: Cristian Atienza',
    );
  });

  it('la precarga de la imagen principal es solo AVIF y no se duplica', () => {
    addAvifPreload(document, '/media', photo, '100vw');
    addAvifPreload(document, '/media', photo, '100vw');
    const links = document.head.querySelectorAll(`link[data-media="${photo.path}"]`);
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('type')).toBe('image/avif');
    expect(links[0].getAttribute('fetchpriority')).toBe('high');
    expect(links[0].getAttribute('imagesrcset')).toContain('-1600.avif 1600w');
    links[0].remove();
  });
});
