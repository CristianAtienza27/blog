import {
  ARTICLE_FORMATS,
  FIGURES_LAYOUTS,
  IMAGE_ALIGNS,
  IMAGE_RATIOS,
  MEDIA_LICENSES,
  MEDIA_SOURCES,
  PROJECT_STATUSES,
  SECTION_IDS,
  TRAVEL_MODES,
} from '../models';
import { htmlIssues } from '../rich-text';
import { ORIGINAL_FORMATS, type CollectionName } from './records';

/**
 * Validación estructural de los registros, sin dependencias.
 * Se usa en los tests y en desarrollo, al cargar el contenido.
 */

export const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const HTTPS_URL = /^https:\/\/[^\s"'<>]+$/;
const COUNTRY = /^[A-Z]{2}$/;
const MEDIA_PATH = /^[a-z0-9]+(?:-[a-z0-9]+)*\/[a-z0-9]+(?:-[a-z0-9]+)*\.[a-f0-9]{8}$/;
const PLACEHOLDER = /^data:image\/(?:webp|avif|png|jpeg);base64,[A-Za-z0-9+/]+=*$/;
const SHA256 = /^[a-f0-9]{64}$/;
const LANGUAGE = /^[a-z0-9+#-]+$/;
/** NgOptimizedImage solo admite placeholders de menos de 4 kB. */
const MAX_PLACEHOLDER_LENGTH = 4000;

type Raw = Record<string, unknown>;

export function isRecord(value: unknown): value is Raw {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Acumula incidencias con la ruta del dato: «articles[2].tags[0]: …». */
export class Check {
  constructor(
    readonly issues: string[],
    readonly path: string,
  ) {}

  at(key: string | number): Check {
    return new Check(
      this.issues,
      typeof key === 'number' ? `${this.path}[${key}]` : `${this.path}.${key}`,
    );
  }

  fail(message: string): void {
    this.issues.push(`${this.path}: ${message}`);
  }
}

interface Rule {
  (check: Check, value: unknown): void;
  optional?: boolean;
}

const optional = (rule: Rule): Rule =>
  Object.assign((c: Check, v: unknown) => rule(c, v), { optional: true });

const text =
  (pattern?: RegExp, label = 'formato'): Rule =>
  (c, v) => {
    if (typeof v !== 'string' || !v.trim()) return c.fail('se esperaba un texto no vacío');
    if (pattern && !pattern.test(v)) c.fail(`${label} no válido: «${v.slice(0, 60)}»`);
  };

const slug = text(SLUG, 'slug');
const url = text(HTTPS_URL, 'URL https');

const isoDate: Rule = (c, v) => {
  text(ISO_DATE, 'fecha')(c, v);
  if (typeof v === 'string' && ISO_DATE.test(v)) {
    const date = new Date(`${v}T00:00:00Z`);
    if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== v) {
      c.fail(`fecha inexistente: «${v}»`);
    }
  }
};

const oneOf =
  (values: readonly string[]): Rule =>
  (c, v) => {
    if (typeof v !== 'string' || !values.includes(v)) {
      c.fail(`valor no permitido: «${String(v)}» (admitidos: ${values.join(', ')})`);
    }
  };

const num =
  ({ min = -Infinity, max = Infinity, integer = false } = {}): Rule =>
  (c, v) => {
    if (typeof v !== 'number' || !Number.isFinite(v)) return c.fail('se esperaba un número');
    if (integer && !Number.isInteger(v)) c.fail(`se esperaba un entero: ${v}`);
    if (v < min || v > max) c.fail(`fuera de rango [${min}, ${max}]: ${v}`);
  };

const bool: Rule = (c, v) => {
  if (typeof v !== 'boolean') c.fail('se esperaba true o false');
};

const list =
  (each: Rule, min = 0): Rule =>
  (c, v) => {
    if (!Array.isArray(v)) return c.fail('se esperaba una lista');
    if (v.length < min) c.fail(`se esperaban al menos ${min} elementos`);
    v.forEach((item, index) => each(c.at(index), item));
  };

/** Objeto con campos conocidos: rechaza los que faltan y los desconocidos (erratas). */
const shape =
  (fields: Record<string, Rule>): Rule =>
  (c, v) => {
    if (!isRecord(v)) return c.fail('se esperaba un objeto');
    for (const [key, rule] of Object.entries(fields)) {
      if (v[key] === undefined) {
        if (!rule.optional) c.at(key).fail('campo obligatorio');
        continue;
      }
      rule(c.at(key), v[key]);
    }
    for (const key of Object.keys(v)) {
      if (!(key in fields)) c.at(key).fail('campo desconocido');
    }
  };

const union =
  (key: string, variants: Record<string, Rule>): Rule =>
  (c, v) => {
    if (!isRecord(v)) return c.fail('se esperaba un objeto');
    const variant = v[key];
    const rule = typeof variant === 'string' ? variants[variant] : undefined;
    if (!rule) return c.at(key).fail(`tipo desconocido: «${String(variant)}»`);
    rule(c, v);
  };

// --- Texto enriquecido -----------------------------------------------------------

// Lista blanca compartida con la página que lo muestra (content/rich-text.ts).
export { htmlIssues };

const richText: Rule = (c, v) => {
  text()(c, v);
  if (typeof v === 'string') for (const issue of htmlIssues(v)) c.fail(issue);
};

// --- Registros -------------------------------------------------------------------

const geoPoint = shape({ lat: num({ min: -90, max: 90 }), lng: num({ min: -180, max: 180 }) });

const category = shape({
  id: oneOf(SECTION_IDS),
  slug,
  name: text(),
  description: text(),
});

const tag = shape({ id: slug, slug, label: text() });

const author = shape({
  id: slug,
  name: text(),
  role: text(),
  bio: optional(text()),
  avatar: optional(slug),
});

/** Coordenadas publicables de una foto: como máximo dos decimales (~1 km). */
const coarsePoint: Rule = (c, v) => {
  geoPoint(c, v);
  if (!isRecord(v)) return;
  for (const key of ['lat', 'lng'] as const) {
    const value = v[key];
    if (typeof value === 'number' && Math.round(value * 100) / 100 !== value) {
      c.at(key).fail('como máximo dos decimales: no se publican ubicaciones precisas');
    }
  }
};

const media: Rule = (c, v) => {
  shape({
    id: slug,
    path: text(MEDIA_PATH, 'ruta'),
    width: num({ min: 1, integer: true }),
    height: num({ min: 1, integer: true }),
    widths: list(num({ min: 1, integer: true }), 1),
    alt: text(),
    title: optional(text()),
    caption: optional(text()),
    description: optional(text()),
    takenAt: optional(isoDate),
    location: optional(
      shape({
        name: text(),
        region: optional(text()),
        country: optional(text(COUNTRY, 'código de país')),
        point: optional(coarsePoint),
      }),
    ),
    camera: optional(
      shape({
        make: optional(text()),
        model: optional(text()),
        lens: optional(text()),
        focalLength: optional(num({ min: 0 })),
        aperture: optional(num({ min: 0 })),
        exposure: optional(text(/^\d+(?:\.\d+)?(?:\/\d+)?$/, 'exposición')),
        iso: optional(num({ min: 1, integer: true })),
      }),
    ),
    focal: shape({ x: num({ min: 0, max: 1 }), y: num({ min: 0, max: 1 }) }),
    placeholder: text(PLACEHOLDER, 'placeholder'),
    credit: shape({
      author: text(),
      source: oneOf(MEDIA_SOURCES),
      sourceUrl: optional(url),
      license: oneOf(MEDIA_LICENSES),
      licenseUrl: optional(url),
    }),
    original: shape({
      file: text(/^[a-z0-9]+(?:-[a-z0-9]+)*\.[a-z0-9]+$/, 'archivo'),
      format: oneOf(ORIGINAL_FORMATS),
      width: num({ min: 1, integer: true }),
      height: num({ min: 1, integer: true }),
      bytes: num({ min: 1, integer: true }),
      sha256: text(SHA256, 'hash'),
      url: optional(url),
      raw: optional(
        shape({
          file: text(/^[a-z0-9]+(?:-[a-z0-9]+)*\.dng$/, 'archivo DNG'),
          bytes: num({ min: 1, integer: true }),
          sha256: text(SHA256, 'hash'),
        }),
      ),
    }),
    demo: bool,
  })(c, v);
  if (!isRecord(v)) return;
  const { widths, width, placeholder, credit, original } = v;
  if (Array.isArray(widths) && typeof width === 'number') {
    const ascending = widths.every((w, i) => i === 0 || (w as number) > (widths[i - 1] as number));
    if (!ascending) c.at('widths').fail('los anchos deben ir de menor a mayor y sin repetirse');
    if (widths.some((w) => (w as number) > width)) {
      c.at('widths').fail('ningún ancho puede superar el de la imagen');
    }
  }
  if (typeof placeholder === 'string' && placeholder.length > MAX_PLACEHOLDER_LENGTH) {
    c.at('placeholder').fail(`demasiado grande (${placeholder.length} caracteres)`);
  }
  // Fuentes externas: obra localizable y licencia libre. Propias: sin URL de origen.
  if (isRecord(credit) && isRecord(original)) {
    if (credit['source'] === 'propia') {
      if (credit['sourceUrl'] !== undefined)
        c.at('credit.sourceUrl').fail('una foto propia no tiene URL de origen');
      if (original['url'] !== undefined)
        c.at('original.url').fail('el original de una foto propia no es público');
    } else {
      if (credit['sourceUrl'] === undefined)
        c.at('credit.sourceUrl').fail('obligatoria en fuentes externas');
      if (original['url'] === undefined)
        c.at('original.url').fail('obligatoria en fuentes externas');
      if (credit['license'] === 'derechos-reservados') {
        c.at('credit.license').fail('una fuente externa necesita una licencia libre');
      }
    }
  }
  if (isRecord(original) && original['width'] !== undefined && typeof width === 'number') {
    const longest = Math.max(Number(original['width']), Number(original['height']));
    if (width > longest) c.at('width').fail('la versión optimizada no puede superar al original');
  }
};

const gallery = shape({
  id: slug,
  slug,
  title: text(),
  description: optional(text()),
  layout: oneOf(['grid', 'horizontal', 'diptych']),
  items: list(shape({ media: slug, caption: optional(text()) }), 1),
  demo: bool,
});

const destination = shape({
  id: slug,
  slug,
  name: text(),
  country: text(COUNTRY, 'código de país'),
  region: optional(text()),
  location: geoPoint,
  elevation: optional(num({ min: -500, max: 9000, integer: true })),
  summary: text(),
  cover: optional(slug),
  demo: bool,
});

const trip = shape({
  id: slug,
  slug,
  title: text(),
  summary: text(),
  stops: list(
    shape({
      order: num({ min: 1, integer: true }),
      destination: slug,
      note: optional(text()),
      distanceKm: optional(num({ min: 0 })),
      mode: optional(oneOf(TRAVEL_MODES)),
    }),
    2,
  ),
  cover: optional(slug),
  demo: bool,
});

const project = shape({
  id: slug,
  slug,
  name: text(),
  summary: text(),
  status: oneOf(PROJECT_STATUSES),
  year: num({ min: 1990, max: 2100, integer: true }),
  stack: list(slug),
  repositoryUrl: optional(url),
  cover: optional(slug),
  demo: bool,
});

const article = shape({
  id: slug,
  slug,
  title: text(),
  dek: text(),
  excerpt: text(),
  category: oneOf(SECTION_IDS),
  tags: list(slug),
  format: oneOf(ARTICLE_FORMATS),
  cover: slug,
  author: slug,
  publishedAt: isoDate,
  updatedAt: optional(isoDate),
  readingMinutes: num({ min: 1, integer: true }),
  featured: bool,
  demo: bool,
  trip: optional(slug),
  destinations: optional(list(slug)),
  project: optional(slug),
  seo: optional(shape({ description: optional(text()) })),
});

const focalPoint = shape({ x: num({ min: 0, max: 1 }), y: num({ min: 0, max: 1 }) });

/** Fotografías de cada composición: el díptico, dos; el tríptico, tres; la secuencia, 2 o 3. */
const FIGURES_COUNT: Record<(typeof FIGURES_LAYOUTS)[number], [number, number]> = {
  diptych: [2, 2],
  triptych: [3, 3],
  sequence: [2, 3],
};

const figureItem = shape({
  media: slug,
  ratio: optional(oneOf(IMAGE_RATIOS)),
  focal: optional(focalPoint),
  caption: optional(text()),
});

const block = union('type', {
  text: shape({ type: text(), html: richText }),
  image: (c, v) => {
    shape({
      type: text(),
      media: slug,
      layout: oneOf(['content', 'wide', 'full']),
      caption: optional(text()),
      ratio: optional(oneOf(IMAGE_RATIOS)),
      align: optional(oneOf(IMAGE_ALIGNS)),
      focal: optional(focalPoint),
    })(c, v);
    // A sangre ocupa la pantalla entera: no hay margen hacia el que romper.
    if (isRecord(v) && v['layout'] === 'full' && v['align'] !== undefined) {
      c.at('align').fail('una imagen a sangre (full) no se alinea a un margen');
    }
  },
  figures: (c, v) => {
    shape({
      type: text(),
      layout: oneOf(FIGURES_LAYOUTS),
      items: list(figureItem, 2),
    })(c, v);
    if (!isRecord(v) || !Array.isArray(v['items'])) return;
    const range = FIGURES_COUNT[v['layout'] as (typeof FIGURES_LAYOUTS)[number]];
    if (!range) return;
    const [min, max] = range;
    const n = v['items'].length;
    if (n < min || n > max) {
      const expected = min === max ? String(min) : `entre ${min} y ${max}`;
      c.at('items').fail(`${String(v['layout'])}: ${expected} fotografías, no ${n}`);
    }
  },
  gallery: shape({ type: text(), gallery: slug }),
  quote: shape({
    type: text(),
    text: text(),
    cite: optional(text()),
    variant: oneOf(['pull', 'block']),
  }),
  map: (c, v) => {
    shape({
      type: text(),
      trip: optional(slug),
      destinations: optional(list(slug, 1)),
      route: bool,
      caption: optional(text()),
    })(c, v);
    if (isRecord(v) && (v['trip'] === undefined) === (v['destinations'] === undefined)) {
      c.fail('el mapa necesita un itinerario (trip) o una lista de destinos, no ambos');
    }
  },
  code: shape({
    type: text(),
    language: text(LANGUAGE, 'lenguaje'),
    code: text(),
    filename: optional(text()),
    caption: optional(text()),
  }),
  specs: shape({
    type: text(),
    title: optional(text()),
    items: list(shape({ label: text(), value: text(), unit: optional(text()) }), 1),
    note: optional(text()),
  }),
  chapter: shape({
    type: text(),
    id: slug,
    number: optional(num({ min: 1, integer: true })),
    title: text(),
    kicker: optional(text()),
    destination: optional(slug),
  }),
  divider: shape({ type: text(), variant: oneOf(['line', 'ornament', 'pause']) }),
});

const articleBody = shape({ slug, blocks: list(block, 1) });

const COLLECTION_RULES: Record<CollectionName, Rule> = {
  categories: category,
  tags: tag,
  authors: author,
  media,
  galleries: gallery,
  destinations: destination,
  trips: trip,
  projects: project,
  articles: article,
};

/** Valida la forma de una colección completa. */
export function validateCollection(name: CollectionName, value: unknown, issues: string[]): void {
  list(COLLECTION_RULES[name])(new Check(issues, name), value);
}

/** Valida la forma del cuerpo de un artículo. */
export function validateArticleBody(name: string, value: unknown, issues: string[]): void {
  articleBody(new Check(issues, name), value);
}
