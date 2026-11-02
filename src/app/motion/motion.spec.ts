import { PLATFORM_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MotionService } from './motion';

describe('MotionService', () => {
  afterEach(() => {
    Reflect.deleteProperty(window, 'matchMedia');
  });

  it('en el servidor no carga GSAP', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: PLATFORM_ID, useValue: 'server' }],
    });
    const motion = TestBed.inject(MotionService);
    expect(await motion.load()).toBeNull();
    expect(motion.reducedMotion()).toBe(false);
  });

  it('refleja prefers-reduced-motion y reacciona a sus cambios', () => {
    let listener: ((event: { matches: boolean }) => void) | undefined;
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: () => ({
        matches: true,
        addEventListener: (_: string, fn: typeof listener) => (listener = fn),
        removeEventListener: () => undefined,
      }),
    });
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const motion = TestBed.inject(MotionService);
    expect(motion.reducedMotion()).toBe(true);

    listener?.({ matches: false });
    expect(motion.reducedMotion()).toBe(false);
  });

  it('usa duraciones por defecto mientras GSAP no se ha cargado', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    expect(TestBed.inject(MotionService).duration('slow')).toBe(0.7);
  });
});
