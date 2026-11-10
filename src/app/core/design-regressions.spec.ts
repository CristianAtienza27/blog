import { readFileSync } from 'node:fs';

/**
 * Regresiones de diseño que no se ven en un test de componente: se comprueban sobre los
 * estilos fuente. Contraste del texto pequeño sobre fotografía (en el peor caso: un píxel
 * blanco bajo el texto), reservas de fuentes con métricas ajustadas y hoja global cargada
 * antes del primer pintado (sin ella, el texto se recolocaba al llegar: CLS).
 */
const read = (path: string) => readFileSync(path, 'utf8');

/** Luminancia relativa WCAG de un color #rrggbb. */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const v = parseInt(hex.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

const contrast = (a: number, b: number) => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);

/** Velo de color hex con opacidad alpha sobre blanco (el peor fondo para tinta clara). */
function overWhite(hex: string, alpha: number): number {
  const mixed = [1, 3, 5]
    .map((i) => Math.round(parseInt(hex.slice(i, i + 2), 16) * alpha + 255 * (1 - alpha)))
    .map((v) => v.toString(16).padStart(2, '0'))
    .join('');
  return luminance(`#${mixed}`);
}

/** Valor oscuro (segundo del par claro/oscuro) de un token neutro de _tokens.scss. */
function darkToken(name: string): string {
  const tokens = read('src/styles/abstracts/_tokens.scss');
  const match = new RegExp(`'${name}': \\(\\s*(#[0-9a-f]{6}),\\s*(#[0-9a-f]{6})`).exec(tokens);
  if (!match) throw new Error(`token ${name}`);
  return match[2];
}

describe('Contraste del texto pequeño sobre fotografía (WCAG AA, peor caso)', () => {
  const ink = luminance(darkToken('ink'));
  const veil = darkToken('bg');
  const plateAlpha =
    Number(/\$photo-plate-alpha: (\d+)%/.exec(read('src/styles/abstracts/_mixins.scss'))?.[1]) /
    100;

  it('la lámina local basta sola: 4,5:1 aunque bajo el texto haya un píxel blanco', () => {
    expect(plateAlpha).toBeGreaterThan(0);
    expect(contrast(ink, overWhite(veil, plateAlpha))).toBeGreaterThanOrEqual(4.5);
  });

  it('claqueta del hero y crédito de la cabecera cinema llevan su lámina', () => {
    expect(read('src/app/features/home/hero/home-hero.html')).toMatch(
      /class="hero__meta[^"]*photo-plate-corner-sm/,
    );
    expect(read('src/app/features/article/header/header-cinema.html')).toMatch(
      /class="cinema__credit[^"]*photo-plate-md/,
    );
    const plates = read('src/styles/themes/_photo-plate.scss');
    expect(plates).toMatch(/\.photo-plate-md \{\s*@include mq\(md\) \{\s*@include photo-plate;/);
    expect(plates).toMatch(/\.photo-plate-corner-sm \{\s*@include mq\(sm\)/);
    expect(read('src/styles.scss')).toContain("@use 'themes/photo-plate';");
  });

  it('el crédito sobre la foto va en tinta clara, no en la atenuada', () => {
    const cinema = read('src/app/features/article/header/header-cinema.scss');
    const credit = /\.cinema__credit \{[^}]*\}/.exec(
      cinema.slice(cinema.lastIndexOf('.cinema__credit {')),
    );
    expect(credit?.[0]).toContain('color: var(--color-ink);');
  });

  it('menú sobre la foto: la sección activa en tinta clara y la franja del header opaca', () => {
    const header = read('src/app/layout/header/header.scss');
    const overlay = /data-header-overlay[^{]*\{([\s\S]*?)\n\}/.exec(header)?.[1] ?? '';
    expect(overlay).toMatch(/\.header__nav a\.is-active \{\s*color: inherit;/);
    // El hover conserva el acento aprobado (no se reescribe sobre la foto).
    expect(overlay).not.toContain(':hover');
    expect(header).toMatch(/&:hover \{\s*color: var\(--accent\);/);
    // El velo de la cabecera cinema mantiene su opacidad en toda la altura del header.
    const cinema = read('src/app/features/article/header/header-cinema.scss');
    const band =
      /color-mix\(in oklab, var\(--color-bg\) (\d+)%, transparent\) var\(--header-height\)/.exec(
        cinema,
      );
    expect(band).not.toBeNull();
    expect(contrast(ink, overWhite(veil, Number(band?.[1]) / 100))).toBeGreaterThanOrEqual(4.5);
  });
});

describe('Fuentes: reservas con métricas ajustadas (sin saltos al cargar)', () => {
  // En línea en el <head>: disponibles desde el primer pintado.
  const head = read('src/index.html').split('</head>')[0];
  const inline = /<style>([\s\S]*?)<\/style>/.exec(head)?.[1] ?? '';
  const faces = [...inline.matchAll(/@font-face \{([^}]*)\}/g)].map(([, body]) => body);

  const descriptor = (face: string, name: string) =>
    new RegExp(`${name}: ([^;]+);`).exec(face)?.[1];

  it('cada familia tiene su reserva local, normal y cursiva, con todas las métricas', () => {
    const named = faces.map((f) =>
      [
        descriptor(f, 'font-family')?.replaceAll("'", ''),
        descriptor(f, 'font-style'),
        descriptor(f, 'font-weight'),
        descriptor(f, 'font-stretch'),
      ]
        .filter(Boolean)
        .join(' '),
    );
    expect(named).toEqual([
      'Fraunces Fallback normal',
      'Fraunces Fallback italic',
      'Instrument Sans Fallback normal 100 550',
      'Instrument Sans Fallback normal 551 900',
      'Instrument Sans Fallback normal 50% 85%',
      'Instrument Sans Fallback italic',
      'JetBrains Mono Fallback normal 100 550',
      'JetBrains Mono Fallback normal 551 900',
    ]);
    for (const face of faces) {
      expect(face).toMatch(/src: local\('/);
      for (const descriptor of [
        'size-adjust',
        'ascent-override',
        'descent-override',
        'line-gap-override',
      ]) {
        expect(face, descriptor).toMatch(new RegExp(`${descriptor}: [\\d.]+%;`));
      }
      expect(face).toContain('font-display: swap;');
    }
  });

  it('las métricas son razonables (la reserva no cambia el tamaño aparente)', () => {
    for (const face of faces) {
      const size = Number(/size-adjust: ([\d.]+)%/.exec(face)?.[1]);
      // La estrecha (antetítulos al 78 %) encoge más: Arial Narrow es más ancha que Instrument
      // Sans condensada; el alto de línea lo mantienen ascent/descent-override.
      const min = descriptor(face, 'font-stretch') ? 80 : 85;
      expect(size).toBeGreaterThan(min);
      expect(size).toBeLessThan(115);
    }
  });

  it('los pesos y la anchura en uso caen en la cara que les corresponde', () => {
    const range = (face: string, name: string) =>
      descriptor(face, name)?.replaceAll('%', '').split(' ').map(Number) ?? [];
    const sans = faces.filter((f) => f.includes("'Instrument Sans Fallback'"));
    const weights = /\$font-weights: \(([^)]*)\)/.exec(read('src/styles/abstracts/_tokens.scss'));
    const used = [...(weights?.[1] ?? '').matchAll(/: (\d+)/g)].map(([, w]) => Number(w));
    expect(used).toEqual([320, 400, 520, 620]);
    for (const weight of used) {
      const matching = sans.filter((f) => {
        const [low, high] = range(f, 'font-weight');
        return low <= weight && weight <= high;
      });
      // Regular hasta 550 (incluido el 520 de las etiquetas) y negrita desde 551.
      expect(matching, `peso ${weight}`).toHaveLength(1);
      expect(descriptor(matching[0], 'src')).toContain(weight > 550 ? 'Bold' : "local('Arial')");
    }
    const sections = read('src/styles/themes/_sections.scss');
    const stretch = Number(/'eyebrow-stretch': (\d+)%/.exec(sections)?.[1]);
    expect(stretch).toBe(78);
    const narrow = sans.find((f) => descriptor(f, 'font-stretch'));
    const [low, high] = range(narrow ?? '', 'font-stretch');
    expect(low <= stretch && stretch <= high).toBe(true);
    expect(descriptor(narrow ?? '', 'src')).toContain('Arial Narrow');
  });

  it('cada pila tipográfica pone la reserva justo después de la fuente web', () => {
    const tokens = read('src/styles/abstracts/_tokens.scss');
    for (const [web, fallback] of [
      ['Fraunces Variable', 'Fraunces Fallback'],
      ['Instrument Sans Variable', 'Instrument Sans Fallback'],
      ['JetBrains Mono Variable', 'JetBrains Mono Fallback'],
    ]) {
      expect(tokens).toMatch(new RegExp(`'${web}',\\s*'${fallback}',`));
    }
  });

  it('no están en la hoja global: llega después del primer pintado y su CSS crítico no lleva @font-face', () => {
    expect(read('src/styles/base/_fonts.scss')).not.toMatch(
      /@font-face\s*\{|font-family:\s*'[^']*Fallback'/,
    );
    // Antes de cualquier hoja enlazada en el <head>.
    expect(head.indexOf('<style>')).toBeGreaterThan(-1);
    expect(head.indexOf('<link rel="stylesheet"')).toBe(-1);
  });
});
