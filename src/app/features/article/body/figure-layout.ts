import type {
  FigureItem,
  FiguresBlock,
  FiguresLayout,
  FocalPoint,
  ImageAlign,
  ImageBlock,
  ImageRatio,
  Media,
} from '../../../content/models';
import { countryName } from '../../../editorial/geo';

/**
 * Dónde se coloca una fotografía en la retícula del artículo:
 * - column: en la columna de lectura.
 * - side: rompe la columna hacia un margen (align) y deja el pie en el margen contrario.
 * - edge: llega hasta el borde de la pantalla por un lado (align).
 * - wide: más ancha que el contenido, centrada.
 * - full: a sangre.
 * Una foto vertical nunca va más allá de «side»: a lo ancho mediría más que la pantalla.
 */
export type FigurePlacement = 'column' | 'side' | 'edge' | 'wide' | 'full';

export interface FigurePlan {
  placement: FigurePlacement;
  align: ImageAlign | null;
  orientation: 'portrait' | 'landscape';
  /** Foto con el punto focal del bloque, si lo trae. */
  media: Media;
  /** Proporción del marco (CSS) si el bloque recorta la foto; si no, la de la foto. */
  ratio: string | null;
  cropped: boolean;
  sizes: string;
  /** Desenfoque de carga solo en fotos que no pasan de unos 1000 px. */
  blur: boolean;
  caption: string | null;
  location: string | null;
}

export function ratioValue(ratio: ImageRatio): number {
  const [w, h] = ratio.split('/').map(Number);
  return w / h;
}

const photoRatio = (media: Media) => media.width / media.height;

/** Cuánto más ancha debe pedirse la foto que su marco para cubrirlo al recortarla. */
export function cropFactor(photo: number, frame: number): number {
  return Math.max(1, photo / frame);
}

/**
 * El marco recorta la foto si su proporción difiere de la de la foto (más de un 1 %). Solo
 * entonces se quita «auto» de sizes (app-media `cropped`): sin recorte, el marco mide lo
 * mismo que la imagen.
 */
const isCropped = (media: Media, frame: number) =>
  Math.abs(photoRatio(media) - frame) / frame > 0.01;

const withFocal = (media: Media, focal: FocalPoint | undefined): Media =>
  focal ? { ...media, focal } : media;

/** «Cadaqués · Girona · España», solo con los datos que tenga la foto. */
export function mediaLocation(media: Media): string | null {
  const place = media.location;
  if (!place) return null;
  const country = place.country ? countryName(place.country) : undefined;
  return [place.name, place.region, country].filter(Boolean).join(' · ') || null;
}

const round = (n: number) => Math.ceil(n);
const vw = (n: number, f: number) => `${round(n * f)}vw`;
const px = (n: number, f: number) => `${round(n * f)}px`;

/**
 * Anchos de cada colocación (escritorio desde lg, 1200 px; tableta desde md, 900 px):
 * columnas de 100 px con el contenido a 1200 px; en tableta el contenido es ~92vw.
 */
function placementSizes(plan: Omit<FigurePlan, 'sizes' | 'blur'>, f: number): string {
  const portrait = plan.orientation === 'portrait';
  const start = plan.align === 'start';
  switch (plan.placement) {
    case 'column':
      return `(width < 900px) ${vw(90, f)}, (width < 1200px) ${vw(62, f)}, ${px(600, f)}`;
    case 'side':
      if (portrait)
        return `(width < 900px) ${vw(100, f)}, (width < 1200px) ${vw(46, f)}, ${px(600, f)}`;
      return `(width < 900px) ${vw(100, f)}, (width < 1200px) ${vw(start ? 62 : 92, f)}, ${px(900, f)}`;
    case 'edge':
      return `(width < 900px) ${vw(100, f)}, (width < 1200px) ${vw(start ? 66 : 96, f)}, calc((50vw + 300px) * ${f.toFixed(3)})`;
    case 'wide':
      return `(width < 900px) ${vw(100, f)}, (width >= 1568px) ${px(1440, f)}, ${vw(92, f)}`;
    case 'full':
      return vw(100, f);
  }
}

/** Plan de una fotografía individual del relato. */
export function figurePlan(block: ImageBlock): FigurePlan {
  const media = withFocal(block.media, block.focal);
  const frame = block.ratio ? ratioValue(block.ratio) : photoRatio(media);
  const orientation = frame < 1 ? 'portrait' : 'landscape';
  let placement: FigurePlacement;
  let align: ImageAlign | null = block.layout === 'full' ? null : (block.align ?? null);
  if (block.layout === 'content') placement = align ? 'side' : 'column';
  else if (block.layout === 'wide') placement = align ? 'edge' : 'wide';
  else placement = 'full';
  if (orientation === 'portrait' && placement !== 'column' && placement !== 'side') {
    placement = 'side';
    align ??= 'end';
  }
  const base = {
    placement,
    align,
    orientation,
    media,
    ratio: block.ratio ?? null,
    cropped: isCropped(media, frame),
    caption: block.caption ?? media.caption ?? null,
    location: mediaLocation(media),
  } satisfies Omit<FigurePlan, 'sizes' | 'blur'>;
  const f = block.ratio ? cropFactor(photoRatio(media), frame) : 1;
  return {
    ...base,
    sizes: placementSizes(base, f),
    blur: placement === 'column' || placement === 'side',
  };
}

export interface FiguresItemPlan {
  media: Media;
  ratio: string;
  cropped: boolean;
  sizes: string;
  caption: string | null;
  location: string | null;
  /** «01», «02»… para la secuencia. */
  index: string;
}

export interface FiguresPlan {
  layout: FiguresLayout;
  /** Columnas de la fila (díptico y tríptico): cada foto, tan ancha como su proporción. */
  columns: string;
  items: FiguresItemPlan[];
}

/**
 * Plan de una composición. En díptico y tríptico las fotos van a la misma altura: cada
 * columna mide en proporción a su relación de aspecto, así que la fila conserva sus
 * proporciones a cualquier ancho. En móvil se apilan. La secuencia va en escalera.
 */
export function figuresPlan(block: FiguresBlock): FiguresPlan {
  const items = block.items;
  const frames = items.map((item) =>
    item.ratio ? ratioValue(item.ratio) : photoRatio(item.media),
  );
  const total = frames.reduce((sum, r) => sum + r, 0);
  return {
    layout: block.layout,
    columns: frames.map((r) => `minmax(0, ${r.toFixed(4)}fr)`).join(' '),
    items: items.map((item: FigureItem, i) => {
      const media = withFocal(item.media, item.focal);
      const f = item.ratio ? cropFactor(photoRatio(media), frames[i]) : 1;
      const share = frames[i] / total;
      const sizes =
        block.layout === 'sequence'
          ? `(width < 640px) ${vw(86, f)}, (width < 1200px) ${vw(62, f)}, ${px(800, f)}`
          : `(width < 640px) ${vw(i === 0 ? 100 : 82, f)}, (width >= 1568px) ${px(1440 * share, f)}, ${vw(92 * share, f)}`;
      return {
        media,
        ratio: item.ratio ?? `${media.width} / ${media.height}`,
        cropped: isCropped(media, frames[i]),
        sizes,
        caption: item.caption ?? media.caption ?? null,
        location: mediaLocation(media),
        index: String(i + 1).padStart(2, '0'),
      };
    }),
  };
}
