import type { Gallery, GalleryItem, Media } from '../../../content/models';
import { cropFactor, mediaLocation } from './figure-layout';

/** Proporción común de las diapositivas del carrusel en móvil (el visor muestra la foto entera). */
export const SLIDE_RATIO = 3 / 2;

export interface GalleryItemPlan {
  media: Media;
  /** Posición (0…n−1) en la galería y en el visor. */
  position: number;
  /** «01», «02»… */
  number: string;
  /** Nombre del botón que amplía la foto. */
  label: string;
  /** Proporción de la foto (CSS): en escritorio se muestra sin recortar. */
  ratio: string;
  sizes: string;
  caption: string | null;
  location: string | null;
}

/**
 * Grupos de la composición de escritorio:
 * - pair: dos fotos juntas a la misma altura, cada una tan ancha como su proporción.
 * - single: una foto grande desplazada hacia un borde (align), con el pie en el margen opuesto.
 */
export interface GalleryGroup {
  kind: 'pair' | 'single';
  align: 'start' | 'end' | null;
  /** Columnas de la pareja: una por foto, en proporción a su relación de aspecto. */
  columns: string | null;
  items: GalleryItemPlan[];
}

export interface GalleryPlan {
  title: string;
  description: string | null;
  /** «3 fotografías». */
  count: string;
  items: GalleryItemPlan[];
  groups: GalleryGroup[];
}

const ratioOf = (media: Media) => media.width / media.height;

/**
 * Reparto en grupos: «2 + 1» y se repite (2 + 1 + 2 + 1…). Si sobra una, va sola; si sobran
 * dos, en pareja. Las fotos sueltas alternan el borde hacia el que se desplazan.
 */
export function galleryGroups(count: number): { kind: 'pair' | 'single'; size: number }[] {
  const groups: { kind: 'pair' | 'single'; size: number }[] = [];
  let left = count;
  while (left > 0) {
    if (left === 1) {
      groups.push({ kind: 'single', size: 1 });
      left -= 1;
    } else {
      groups.push({ kind: 'pair', size: 2 });
      left -= 2;
      if (left > 0 && left !== 2) {
        groups.push({ kind: 'single', size: 1 });
        left -= 1;
      }
    }
  }
  return groups;
}

const round = (n: number) => Math.ceil(n);

/**
 * Anchos pedidos (sizes):
 * - Móvil y tableta (< 900 px): diapositiva de min(84vw, 34rem) con recorte 3:2.
 * - Pareja: su parte de la fila ancha (92vw; 1440 px como máximo).
 * - Suelta: ocho columnas más el margen exterior (unos 66vw en tableta, 920 px en escritorio).
 */
function itemSizes(media: Media, slot: 'pair' | 'single', share: number): string {
  const f = cropFactor(ratioOf(media), SLIDE_RATIO);
  const mobile = `(width < 640px) ${round(84 * f)}vw, (width < 900px) ${round(544 * f)}px`;
  if (slot === 'single') return `${mobile}, (width < 1200px) 66vw, 920px`;
  return `${mobile}, (width >= 1568px) ${round(1440 * share)}px, ${round(92 * share)}vw`;
}

const countLabel = (n: number) => (n === 1 ? '1 fotografía' : `${n} fotografías`);

/** Plan de una galería del relato: fotos en orden, grupos de escritorio y textos. */
export function galleryPlan(gallery: Gallery): GalleryPlan {
  const total = gallery.items.length;
  const shape = galleryGroups(total);
  const items: GalleryItemPlan[] = [];
  const groups: GalleryGroup[] = [];
  let position = 0;
  let singles = 0;

  for (const { kind, size } of shape) {
    const members = gallery.items.slice(position, position + size);
    const ratios = members.map((item) => ratioOf(item.media));
    const sum = ratios.reduce((a, b) => a + b, 0);
    const plans = members.map((item: GalleryItem, k) => {
      const at = position + k;
      const plan: GalleryItemPlan = {
        media: item.media,
        position: at,
        number: String(at + 1).padStart(2, '0'),
        label: `Ampliar la fotografía ${at + 1} de ${total}`,
        ratio: `${item.media.width} / ${item.media.height}`,
        sizes: itemSizes(item.media, kind, kind === 'pair' ? ratios[k] / sum : 1),
        caption: item.caption ?? item.media.caption ?? null,
        location: mediaLocation(item.media),
      };
      return plan;
    });
    items.push(...plans);
    groups.push({
      kind,
      align: kind === 'single' ? (singles++ % 2 === 0 ? 'end' : 'start') : null,
      columns: kind === 'pair' ? ratios.map((r) => `minmax(0, ${r.toFixed(4)}fr)`).join(' ') : null,
      items: plans,
    });
    position += size;
  }

  return {
    title: gallery.title,
    description: gallery.description ?? null,
    count: countLabel(total),
    items,
    groups,
  };
}
