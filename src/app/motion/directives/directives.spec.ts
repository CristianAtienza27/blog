import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MotionApi, MotionService } from '../motion';
import { Horizontal } from './horizontal';
import { Parallax } from './parallax';
import { Reveal } from './reveal';
import { SplitText } from './split-text';

@Component({
  imports: [Horizontal, Parallax, Reveal, SplitText],
  template: `
    <p appReveal>Texto</p>
    <div appParallax="0.2"><span>Imagen</span></div>
    <h2 appSplitText>Titular</h2>
    <section appHorizontal><div data-horizontal-track></div></section>
  `,
})
class Host {}

/** GSAP simulado: registra llamadas y permite comprobar la limpieza. */
function fakeMotion(conditions: { reduce: boolean; mobile: boolean }) {
  const revert = vi.fn();
  const gsap = {
    matchMedia: () => {
      let cleanup: void | (() => void);
      return {
        add: (
          _: unknown,
          setup: (context: { conditions: typeof conditions }) => void | (() => void),
        ) => {
          cleanup = setup({ conditions });
        },
        revert: () => {
          revert();
          if (typeof cleanup === 'function') cleanup();
        },
      };
    },
    fromTo: vi.fn(),
    from: vi.fn(),
    to: vi.fn(),
  };
  const splitText = { create: vi.fn() };
  const api = { gsap, ScrollTrigger: {}, SplitText: splitText } as unknown as MotionApi;
  const service: Partial<MotionService> = {
    load: () => Promise.resolve(api),
    clientNavigation: (() => true) as MotionService['clientNavigation'],
    duration: () => 0.5,
  };
  return { service, gsap, splitText, revert };
}

async function render(conditions: { reduce: boolean; mobile: boolean }) {
  const motion = fakeMotion(conditions);
  TestBed.configureTestingModule({
    providers: [{ provide: MotionService, useValue: motion.service }],
  });
  const fixture = TestBed.createComponent(Host);
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve));
  return { fixture, ...motion };
}

describe('Directivas de movimiento', () => {
  it('montan sus animaciones tras el render', async () => {
    const { gsap, splitText } = await render({ reduce: false, mobile: false });
    // appReveal + appParallax usan fromTo; appSplitText usa SplitText.create.
    expect(gsap.fromTo).toHaveBeenCalledTimes(2);
    expect(splitText.create).toHaveBeenCalledTimes(1);
  });

  it('con reduced motion no animan nada', async () => {
    const { gsap, splitText } = await render({ reduce: true, mobile: false });
    expect(gsap.fromTo).not.toHaveBeenCalled();
    expect(gsap.to).not.toHaveBeenCalled();
    expect(splitText.create).not.toHaveBeenCalled();
  });

  it('al destruirse revierten todo lo creado', async () => {
    const { fixture, revert } = await render({ reduce: false, mobile: false });
    fixture.destroy();
    expect(revert).toHaveBeenCalledTimes(4);
  });

  it('la galería horizontal funciona sin JavaScript (clase base para scroll-snap)', async () => {
    const { fixture } = await render({ reduce: false, mobile: true });
    const section = (fixture.nativeElement as HTMLElement).querySelector('section');
    expect(section?.classList.contains('horizontal')).toBe(true);
    expect(section?.classList.contains('is-pinned')).toBe(false);
  });
});
