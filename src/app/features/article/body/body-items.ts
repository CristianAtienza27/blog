import {
  SPECS_ID_PREFIX,
  type Article,
  type ArticleFormat,
  type ChapterBlock,
  type DividerBlock,
  type FiguresBlock,
  type ImageBlock,
  type QuoteBlock,
  type SpecsBlock,
} from '../../../content/models';
import { articleUrl } from '../../../editorial/article-meta';
import { RETIRED_MEDIA } from '../../../editorial/retired-media';
import { renderRichText } from './rich-text-render';

/** Lo que el cuerpo sabe mostrar en esta fase; el resto de bloques llega después. */
export type BodyItem =
  | { kind: 'prose'; html: string }
  | { kind: 'chapter'; block: ChapterBlock }
  | { kind: 'quote'; block: QuoteBlock }
  | { kind: 'divider'; block: DividerBlock }
  | { kind: 'specs'; block: SpecsBlock; level: 2 | 3; headingId: string }
  | { kind: 'figure'; block: ImageBlock }
  | { kind: 'figures'; block: FiguresBlock };

/**
 * Fotografías de una composición que se pueden mostrar. Si quedan menos de las que pide su
 * disposición: un tríptico con dos pasa a díptico y una sola foto se muestra individual.
 */
function visibleFigures(block: FiguresBlock, hidden: ReadonlySet<string>): BodyItem | null {
  const items = block.items.filter((item) => !hidden.has(item.media.id)).slice(0, 3);
  if (items.length === 0) return null;
  if (items.length === 1) {
    const [only] = items;
    return { kind: 'figure', block: { type: 'image', layout: 'wide', ...only } };
  }
  const layout = block.layout === 'triptych' && items.length === 2 ? 'diptych' : block.layout;
  return { kind: 'figures', block: { ...block, layout, items } };
}

/** Formatos narrativos: abren con capitular. Notas, fichas y tecnología, no. */
const DROPCAP_FORMATS: readonly ArticleFormat[] = ['reportaje', 'guia', 'ensayo-fotografico'];

/**
 * Bloques del artículo listos para mostrar, en orden:
 * - Los títulos de fichas y los subtítulos del texto son h2 hasta el primer capítulo y h3
 *   dentro de un capítulo (la jerarquía no salta niveles).
 * - Capitular en el primer bloque de texto de los formatos narrativos.
 * - Fotografías: nunca las retiradas ni la de la portada (ya está en la cabecera).
 * - Galerías, mapas y código se omiten hasta sus fases.
 */
export function bodyItems(article: Article): BodyItem[] {
  const basePath = articleUrl(article);
  const hidden = new Set([...RETIRED_MEDIA, article.cover.id]);
  const dropcap = DROPCAP_FORMATS.includes(article.format) && article.category.id !== 'tecnologia';
  let inChapter = false;
  let firstText = true;
  const items: BodyItem[] = [];

  // Ids de las fichas: «datos-<posición del bloque>», nunca el de un capítulo ni el de otra
  // ficha (la validación lo impide, pero el contenido puede llegar sin validar). Solo
  // depende del artículo: el mismo en el servidor y al hidratar.
  const taken = new Set(
    article.blocks.flatMap((block) => (block.type === 'chapter' ? [block.id] : [])),
  );
  const uniqueId = (base: string) => {
    let id = base;
    for (let n = 2; taken.has(id); n++) id = `${base}-${n}`;
    taken.add(id);
    return id;
  };

  article.blocks.forEach((block, index) => {
    switch (block.type) {
      case 'text':
        items.push({
          kind: 'prose',
          html: renderRichText(block.html, {
            basePath,
            headingLevel: inChapter ? 3 : 2,
            dropcap: dropcap && firstText,
          }),
        });
        firstText = false;
        break;
      case 'chapter':
        inChapter = true;
        items.push({ kind: 'chapter', block });
        break;
      case 'quote':
        items.push({ kind: 'quote', block });
        break;
      case 'divider':
        items.push({ kind: 'divider', block });
        break;
      case 'specs':
        items.push({
          kind: 'specs',
          block,
          level: inChapter ? 3 : 2,
          headingId: uniqueId(`${SPECS_ID_PREFIX}${index}`),
        });
        break;
      case 'image':
        if (!hidden.has(block.media.id)) items.push({ kind: 'figure', block });
        break;
      case 'figures': {
        const item = visibleFigures(block, hidden);
        if (item) items.push(item);
        break;
      }
    }
  });
  return items;
}
