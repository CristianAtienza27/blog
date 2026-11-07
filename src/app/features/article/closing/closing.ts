import type { Article, ArticleSummary, Media, MediaCredit } from '../../../content/models';
import { plainText } from '../../../content/rich-text';
import { articleUrl } from '../../../editorial/article-meta';
import { SECTIONS } from '../../../layout/navigation';
import { articleCover } from '../article-presentation';
import { bodyItems } from '../body/body-items';

// --- Créditos ---------------------------------------------------------------------------

export interface PhotoRef {
  /** Qué se ve (el pie de la foto o, si no lo tiene, su texto alternativo). */
  label: string;
  /** Página de la obra en su fuente, si la hay. */
  href: string | null;
  cover: boolean;
}

/** Fotografías de un mismo autor, fuente y licencia (un crédito, varias fotos). */
export interface PhotoCreditGroup {
  credit: MediaCredit;
  /** Una foto representativa del grupo (para el componente de crédito existente). */
  media: Media;
  photos: PhotoRef[];
}

export interface Reference {
  label: string;
  href: string;
  /** «es.wikipedia.org». */
  host: string;
}

export interface CreditsPlan {
  author: { name: string; role: string };
  photos: PhotoCreditGroup[];
  references: Reference[];
  /** Notas de producción que se deducen del propio contenido. */
  notes: string[];
  demo: boolean;
}

const labelOf = (media: Media) => (media.caption ?? media.alt).replace(/\.$/, '');
const creditKey = (c: MediaCredit) => [c.author, c.source, c.license, c.licenseUrl].join('|');

/** Fotos que el lector ve en el artículo, en orden: la portada y las del cuerpo (sin retiradas). */
export function articlePhotos(article: Article): { media: Media; cover: boolean }[] {
  const cover = articleCover(article);
  const seen = new Set<string>();
  const out: { media: Media; cover: boolean }[] = [];
  const add = (media: Media, isCover = false) => {
    if (seen.has(media.id)) return;
    seen.add(media.id);
    out.push({ media, cover: isCover });
  };
  if (cover) add(cover, true);
  for (const item of bodyItems(article)) {
    if (item.kind === 'figure') add(item.block.media);
    else if (item.kind === 'figures') item.block.items.forEach((i) => add(i.media));
    else if (item.kind === 'gallery') item.gallery.items.forEach((i) => add(i.media));
  }
  return out;
}

/** Enlaces externos que cita el texto (https), sin repetir. */
export function articleReferences(article: Article): Reference[] {
  const refs = new Map<string, Reference>();
  for (const block of article.blocks) {
    if (block.type !== 'text') continue;
    for (const [, href, inner] of block.html.matchAll(
      /<a href="(https:\/\/[^"]+)"[^>]*>(.*?)<\/a>/g,
    )) {
      if (refs.has(href)) continue;
      refs.set(href, { label: plainText(inner), href, host: new URL(href).host });
    }
  }
  return [...refs.values()];
}

/** Créditos del artículo: autor, fotografías agrupadas por crédito, referencias y notas. */
export function creditsPlan(article: Article): CreditsPlan {
  const groups = new Map<string, PhotoCreditGroup>();
  for (const { media, cover } of articlePhotos(article)) {
    const key = creditKey(media.credit);
    const group = groups.get(key) ?? { credit: media.credit, media, photos: [] };
    group.photos.push({ label: labelOf(media), href: media.credit.sourceUrl ?? null, cover });
    groups.set(key, group);
  }
  const notes = article.blocks.some((block) => block.type === 'map')
    ? [
        'Cartografía de elaboración propia a partir de coordenadas aproximadas; orientativa, no sirve para navegar.',
      ]
    : [];
  return {
    author: { name: article.author.name, role: article.author.role },
    photos: [...groups.values()],
    references: articleReferences(article),
    notes,
    demo: article.demo,
  };
}

// --- Piezas de otros artículos -----------------------------------------------------------

export interface ArticleTeaser {
  href: string;
  title: string;
  dek: string;
  section: string;
  /** Portada, si no está retirada. */
  cover: Media | null;
  demo: boolean;
}

export function articleTeaser(article: ArticleSummary): ArticleTeaser {
  return {
    href: articleUrl(article),
    title: article.title,
    dek: article.dek,
    section: SECTIONS.find((s) => s.id === article.category.id)?.label ?? article.category.name,
    cover: articleCover(article),
    demo: article.demo,
  };
}
