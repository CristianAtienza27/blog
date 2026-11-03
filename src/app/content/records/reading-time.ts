import type { ContentBlockRecord } from './records';

/** Velocidad media de lectura en español (palabras por minuto). */
export const WORDS_PER_MINUTE = 200;

function words(text: string | undefined): number {
  if (!text) return 0;
  return text
    .replace(/<[^>]*>/g, ' ')
    .split(/\s+/)
    .filter((word) => /[\p{L}\p{N}]/u.test(word)).length;
}

/** Palabras legibles de un cuerpo: texto, citas, capítulos, fichas y código. */
export function countWords(blocks: readonly ContentBlockRecord[]): number {
  let total = 0;
  for (const block of blocks) {
    switch (block.type) {
      case 'text':
        total += words(block.html);
        break;
      case 'quote':
        total += words(block.text) + words(block.cite);
        break;
      case 'chapter':
        total += words(block.title) + words(block.kicker);
        break;
      case 'specs':
        total += words(block.title) + words(block.note);
        for (const item of block.items) total += words(item.label) + words(item.value);
        break;
      case 'code':
        total += words(block.code) + words(block.caption);
        break;
      case 'image':
      case 'map':
        total += words(block.caption);
        break;
    }
  }
  return total;
}

/** Minutos de lectura, como mínimo uno. */
export function readingMinutes(blocks: readonly ContentBlockRecord[]): number {
  return Math.max(1, Math.round(countWords(blocks) / WORDS_PER_MINUTE));
}
