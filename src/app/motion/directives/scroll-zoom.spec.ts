import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MotionApi, MotionService } from '../motion';
import { ScrollZoom } from './scroll-zoom';

@Component({
  imports: [ScrollZoom],
  template: `<div appScrollZoom expand>
    <div class="media__frame"><img alt="" /></div>
  </div>`,
})
class Host {}

function fakeMotion(conditions: { reduce: boolean; mobile: boolean }) {
  const timeline = { fromTo: vi.fn() };
  const gsap = {
    matchMedia: () => ({
      add: (_: unknown, setup: (context: { conditions: typeof conditions }) => void) =>
        setup({ conditions }),
      revert: vi.fn(),
    }),
    timeline: vi.fn(() => timeline),
  };
  const service: Partial<MotionService> = {
    load: () => Promise.resolve({ gsap } as unknown as MotionApi),
    clientNavigation: (() => false) as MotionService['clientNavigation'],
    duration: () => 0.5,
  };
  return { service, gsap, timeline };
}

async function render(conditions: { reduce: boolean; mobile: boolean }) {
  const motion = fakeMotion(conditions);
  TestBed.configureTestingModule({
    providers: [{ provide: MotionService, useValue: motion.service }],
  });
  const fixture = TestBed.createComponent(Host);
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve));
  return motion;
}

describe('appScrollZoom', () => {
  it('en escritorio la foto se asienta y el marco pasa de los márgenes a sangre', async () => {
    const { gsap, timeline } = await render({ reduce: false, mobile: false });
    expect(gsap.timeline).toHaveBeenCalledTimes(1);
    const [[image, from], [frame, frameFrom]] = timeline.fromTo.mock.calls;
    expect((image as Element[])[0].tagName).toBe('IMG');
    expect(from).toEqual({ scale: 1.08 });
    expect((frame as Element).hasAttribute('appscrollzoom')).toBe(true);
    expect(frameFrom).toEqual({ clipPath: 'inset(0% 4% 0% 4%)' });
  });

  it('en móvil, la mitad de zoom y sin expansión', async () => {
    const { timeline } = await render({ reduce: false, mobile: true });
    expect(timeline.fromTo).toHaveBeenCalledTimes(1);
    expect(timeline.fromTo.mock.calls[0][1]).toEqual({ scale: 1.04 });
  });

  it('con reduced motion no anima nada', async () => {
    const { gsap } = await render({ reduce: true, mobile: false });
    expect(gsap.timeline).not.toHaveBeenCalled();
  });
});
