import { TestBed } from '@angular/core/testing';
import { MenuAnimator } from './menu-animator';
import { MotionApi, MotionService } from './motion';

/** GSAP simulado: registra set() y los tweens de los timelines. */
function fakeGsap() {
  const sets: { target: unknown; vars: Record<string, unknown> }[] = [];
  const tweens: { method: string; target: unknown; vars: Record<string, unknown> }[] = [];
  const timeline = () => {
    const tl = {
      to: (target: unknown, vars: Record<string, unknown>) => {
        tweens.push({ method: 'to', target, vars });
        return tl;
      },
      fromTo: (target: unknown, _from: unknown, vars: Record<string, unknown>) => {
        tweens.push({ method: 'fromTo', target, vars });
        return tl;
      },
      kill: () => undefined,
      eventCallback: () => tl,
    };
    return tl;
  };
  const gsap = {
    set: (target: unknown, vars: Record<string, unknown>) => sets.push({ target, vars }),
    timeline,
    utils: { selector: () => (selector: string) => selector },
  };
  return { gsap, sets, tweens };
}

function setup(reduce = false) {
  const fake = fakeGsap();
  const api = { gsap: fake.gsap } as unknown as MotionApi;
  const motion: Partial<MotionService> = {
    load: () => Promise.resolve(api),
    get loaded() {
      return api;
    },
    reducedMotion: (() => reduce) as MotionService['reducedMotion'],
    duration: () => 0.5,
  };
  TestBed.configureTestingModule({ providers: [{ provide: MotionService, useValue: motion }] });
  return { animator: TestBed.inject(MenuAnimator), ...fake };
}

describe('MenuAnimator', () => {
  const dialog = {} as HTMLDialogElement;

  it('prepare() fija todos los estados iniciales antes de la apertura', async () => {
    const { animator, sets } = setup();
    expect(await animator.prepare(dialog)).toBe(true);
    expect(sets.map((s) => [s.target, s.vars])).toEqual([
      ['.menu__curtain', { clipPath: 'inset(0% 0% 100% 0%)' }],
      ['[data-menu-line]', { yPercent: 115 }],
      ['[data-menu-fade]', { opacity: 0, y: 12 }],
      ['[data-menu-preview]', { clipPath: 'inset(100% 0% 0% 0%)' }],
    ]);
  });

  it('open() anima desde el estado actual con un único timeline (sin fromTo)', () => {
    const { animator, tweens } = setup();
    animator.open(dialog);
    expect(tweens.map((t) => t.method)).toEqual(['to', 'to', 'to', 'to']);
    expect(tweens.map((t) => t.target)).toEqual([
      '.menu__curtain',
      '[data-menu-line]',
      '[data-menu-fade]',
      '[data-menu-preview]',
    ]);
  });

  it('el estado inicial no usa visibility: el botón de cerrar puede recibir el foco', async () => {
    const { animator, sets } = setup();
    await animator.prepare(dialog);
    expect(sets.some((s) => 'autoAlpha' in s.vars || 'visibility' in s.vars)).toBe(false);
  });

  it('con reduced motion solo prepara y anima la opacidad del diálogo', async () => {
    const { animator, sets, tweens } = setup(true);
    await animator.prepare(dialog);
    animator.open(dialog);
    expect(sets).toEqual([{ target: dialog, vars: { opacity: 0 } }]);
    expect(tweens).toEqual([
      { method: 'to', target: dialog, vars: { autoAlpha: 1, duration: 0.5 } },
    ]);
  });

  it('sin motor de animación, prepare() lo indica y el menú se muestra sin animar', async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: MotionService,
          useValue: { load: () => Promise.resolve(null), loaded: null },
        },
      ],
    });
    const animator = TestBed.inject(MenuAnimator);
    expect(await animator.prepare(dialog)).toBe(false);
    expect(() => animator.open(dialog)).not.toThrow();
  });
});
