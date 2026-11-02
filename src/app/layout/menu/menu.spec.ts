import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
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

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: '', component: Blank },
        { path: 'viajes', component: Blank },
      ]),
      { provide: MotionService, useValue: motionWithoutGsap },
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
