import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { Media as MediaAsset } from '../../content/models';
import { MediaRepository } from '../../content/repositories/media-repository';
import { MotionService } from '../../motion/motion';
import { Menu } from './menu';
import { MenuState } from './menu-state';

@Component({ template: '' })
class Blank {}

// jsdom no implementa showModal()/close(): se simulan con el atributo open.
beforeAll(() => {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & Record<string, unknown>;
  proto.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
});

/** Motor sin animaciones: el menú debe funcionar igual. */
const motionWithoutGsap: Partial<MotionService> = {
  load: () => Promise.resolve(null),
  get loaded() {
    return null;
  },
  reducedMotion: (() => false) as MotionService['reducedMotion'],
  duration: () => 0,
  refresh: () => undefined,
};

/** Repositorio simulado: devuelve una foto por id y registra las peticiones. */
const requested: string[][] = [];
const mediaRepository: Partial<MediaRepository> = {
  mediaList: (ids: readonly string[]) => {
    requested.push([...ids]);
    return Promise.resolve(
      ids.map((id): MediaAsset => ({
        id,
        path: `viajes/${id}.0000000a`,
        width: 1600,
        height: 1067,
        widths: [640, 1024, 1600],
        alt: `Foto ${id}`,
        focal: { x: 0.5, y: 0.5 },
        placeholder: 'data:image/webp;base64,AAAA',
        credit: {
          author: 'Autoría de prueba',
          source: 'wikimedia',
          sourceUrl: 'https://commons.wikimedia.org/wiki/File:X.jpg',
          license: 'CC-BY-SA-4.0',
          licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
        },
        demo: true,
      })),
    );
  },
};

async function setup() {
  requested.length = 0;
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: '', component: Blank },
        { path: 'viajes', component: Blank },
      ]),
      { provide: MotionService, useValue: motionWithoutGsap },
      { provide: MediaRepository, useValue: mediaRepository },
    ],
  });
  const fixture = TestBed.createComponent(Menu);
  await fixture.whenStable();
  const element = fixture.nativeElement as HTMLElement;
  const dialog = element.querySelector('dialog') as HTMLDialogElement;
  const state = TestBed.inject(MenuState);
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
  };
  return { fixture, element, dialog, state, settle };
}

describe('Menu', () => {
  it('se abre como diálogo al cambiar el estado', async () => {
    const { dialog, state, settle } = await setup();
    expect(dialog.open).toBe(false);
    state.show();
    await settle();
    expect(dialog.open).toBe(true);
    expect(dialog.getAttribute('aria-label')).toBe('Menú principal');
  });

  it('Esc (cancel) cierra el menú', async () => {
    const { dialog, state, settle } = await setup();
    state.show();
    await settle();
    const cancel = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(cancel);
    await settle();
    expect(cancel.defaultPrevented).toBe(true);
    expect(state.isOpen()).toBe(false);
    expect(dialog.open).toBe(false);
  });

  it('se cierra al navegar a otra página', async () => {
    const { dialog, state, settle } = await setup();
    state.show();
    await settle();
    await TestBed.inject(Router).navigateByUrl('/viajes');
    await settle();
    expect(state.isOpen()).toBe(false);
    expect(dialog.open).toBe(false);
  });

  it('un enlace a la página actual también lo cierra', async () => {
    const { element, dialog, state, settle } = await setup();
    await TestBed.inject(Router).navigateByUrl('/viajes');
    state.show();
    await settle();
    element.querySelector<HTMLAnchorElement>('a[href="/viajes"]')?.click();
    await settle();
    expect(state.isOpen()).toBe(false);
    expect(dialog.open).toBe(false);
  });

  it('pide las fotos de vista previa solo al abrirse y muestra su crédito', async () => {
    const { element, state, settle } = await setup();
    expect(requested).toEqual([]);
    expect(element.querySelector('.menu__preview picture')).toBeNull();

    state.show();
    await settle();
    expect(requested).toEqual([
      [
        'viajes-cadaques-bahia',
        'paisaje-seceda-panorama',
        'motor-stelvio-bormio',
        'naturaleza-fanal-niebla',
        'tecnologia-codigo-pantalla',
      ],
    ]);
    expect(element.querySelectorAll('.menu__preview picture')).toHaveLength(5);
    // Las vistas previas llenan todo el marco: sin el hueco del pie de foto.
    const frames = [...element.querySelectorAll('app-media.menu__frame')];
    expect(frames).toHaveLength(5);
    expect(frames.every((frame) => frame.hasAttribute('data-flush'))).toBe(true);
    expect(element.querySelector('.menu__preview figcaption')).toBeNull();
    const credit = element.querySelector('.menu__credit')?.textContent?.replace(/\s+/g, ' ');
    expect(credit).toContain('Foto: Autoría de prueba');
    expect(credit).toContain('CC BY-SA 4.0');
  });

  it('lista las cinco secciones numeradas', async () => {
    const { element } = await setup();
    const items = [...element.querySelectorAll('.menu__link')].map((link) =>
      link.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(items[0]).toContain('01');
    expect(items[0]).toContain('Viajes');
    expect(items).toHaveLength(5);
  });
});
