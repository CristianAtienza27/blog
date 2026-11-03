import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MediaRepository } from '../../content/repositories/media-repository';
import { MenuAnimator } from '../../motion/menu-animator';
import { Menu } from './menu';
import { MenuState } from './menu-state';

@Component({ template: '' })
class Blank {}

/** Animador simulado: registra el orden de las llamadas y deja controlar el cierre. */
class RecordingAnimator {
  readonly calls: string[] = [];
  private finishClose?: (finished: boolean) => void;

  prepare(): Promise<boolean> {
    this.calls.push('prepare');
    return Promise.resolve(true);
  }

  open(): void {
    this.calls.push('open');
    // Abrir interrumpe un cierre en curso, como hace el animador real.
    this.finishClose?.(false);
    this.finishClose = undefined;
  }

  close(): Promise<boolean> {
    this.calls.push('close');
    return new Promise<boolean>((resolve) => (this.finishClose = resolve));
  }

  completeClose(): void {
    this.finishClose?.(true);
    this.finishClose = undefined;
  }

  reset(): void {
    this.calls.push('reset');
  }

  dispose(): void {
    // Nada que liberar.
  }
}

async function setup() {
  const animator = new RecordingAnimator();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '', component: Blank }]),
      { provide: MenuAnimator, useValue: animator },
      { provide: MediaRepository, useValue: { mediaList: () => Promise.resolve([]) } },
    ],
  });
  const fixture = TestBed.createComponent(Menu);
  await fixture.whenStable();
  const dialog = (fixture.nativeElement as HTMLElement).querySelector(
    'dialog',
  ) as HTMLDialogElement;
  // jsdom no implementa showModal()/close(): se simulan y se registran.
  dialog.showModal = () => {
    animator.calls.push('showModal');
    dialog.setAttribute('open', '');
  };
  dialog.close = () => {
    animator.calls.push('dialog.close');
    dialog.removeAttribute('open');
  };
  animator.calls.length = 0;
  const settle = async () => {
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
  };
  return { animator, dialog, state: TestBed.inject(MenuState), settle };
}

describe('Apertura del menú (regresión del doble aparecido)', () => {
  it('prepara los estados iniciales antes de mostrar el diálogo y anima una sola vez', async () => {
    const { animator, dialog, state, settle } = await setup();
    state.show();
    await settle();
    expect(animator.calls).toEqual(['prepare', 'showModal', 'open']);
    expect(dialog.open).toBe(true);
  });

  it('cada apertura y cada cierre se animan exactamente una vez', async () => {
    const { animator, state, settle } = await setup();
    state.show();
    await settle();
    state.hide();
    await settle();
    animator.completeClose();
    await settle();
    state.show();
    await settle();
    expect(animator.calls).toEqual([
      'prepare',
      'showModal',
      'open',
      'close',
      'dialog.close',
      'reset',
      'prepare',
      'showModal',
      'open',
    ]);
  });

  it('abrir y cerrar antes de que se muestre no llega a mostrar el diálogo', async () => {
    const { animator, dialog, state, settle } = await setup();
    state.show();
    TestBed.tick();
    state.hide();
    await settle();
    expect(animator.calls).not.toContain('showModal');
    expect(animator.calls).not.toContain('open');
    expect(animator.calls.at(-1)).toBe('reset');
    expect(dialog.open).toBe(false);
  });

  it('reabrir durante el cierre continúa la apertura y no deja el menú invisible', async () => {
    const { animator, dialog, state, settle } = await setup();
    state.show();
    await settle();
    state.hide();
    await settle();
    state.show();
    await settle();
    expect(animator.calls).toEqual(['prepare', 'showModal', 'open', 'close', 'open']);
    expect(dialog.open).toBe(true);
    expect(state.isOpen()).toBe(true);
  });
});
