import { PLATFORM_ID } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MotionService, cssTimeToSeconds } from './motion';

describe('Tiempos CSS a segundos', () => {
  it.each([
    ['700ms', 0.7],
    ['.7s', 0.7],
    ['1.2s', 1.2],
    ['120ms', 0.12],
    ['0.38s', 0.38],
    ['0s', 0],
    ['  220ms ', 0.22],
    ['2400MS', 2.4],
  ])('%s → %s s', (value, seconds) => {
    expect(cssTimeToSeconds(value, 9)).toBeCloseTo(seconds, 10);
  });

  it.each(['', '   ', '700', '.7', 'rápido', '-200ms', '1.2 s', '1.2sec', 'calc(1s)', 'NaNms'])(
    'vacío o no válido («%s»): duración de reserva',
    (value) => {
      expect(cssTimeToSeconds(value, 0.7)).toBe(0.7);
    },
  );
});

describe('MotionService: duraciones desde los tokens CSS', () => {
  const root = document.documentElement;
  const tokens = {
    instant: '120ms',
    fast: 'rápido',
    base: '',
    slow: '.7s',
    cinematic: '1.2s',
  } as const;

  // jsdom no implementa matchMedia y ScrollTrigger lo necesita al configurarse.
  beforeEach(() => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: () => ({
        matches: false,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
      }),
    });
  });

  afterEach(() => {
    for (const name of Object.keys(tokens)) root.style.removeProperty(`--duration-${name}`);
    Reflect.deleteProperty(window, 'matchMedia');
  });

  it('respeta la unidad de cada token (como en el CSS de producción) y reserva lo no válido', async () => {
    for (const [name, value] of Object.entries(tokens)) {
      root.style.setProperty(`--duration-${name}`, value);
    }
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const motion = TestBed.inject(MotionService);
    const api = await motion.load();
    expect(api).not.toBeNull();
    expect(motion.duration('instant')).toBeCloseTo(0.12, 10);
    expect(motion.duration('slow')).toBe(0.7);
    expect(motion.duration('cinematic')).toBe(1.2);
    // «rápido» y vacío no son tiempos: se mantienen las duraciones por defecto.
    expect(motion.duration('fast')).toBe(0.22);
    expect(motion.duration('base')).toBe(0.38);
    // La duración por defecto de GSAP es la «slow» ya convertida, no 0,0007 s.
    expect(api?.gsap.defaults().duration).toBe(0.7);
  });
});

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
