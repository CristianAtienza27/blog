/**
 * Lectura y escritura del contenido (src/content) desde el procesador.
 * media.json es la fuente única de las imágenes: los campos editoriales (alt, pie,
 * punto focal…) se escriben a mano; los técnicos los rellena el procesador.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { CONTENT, MEDIA_CONFIG } from './config.mjs';

const file = (name) => path.join(CONTENT, name);

async function readJson(name, fallback) {
  try {
    return JSON.parse(await fs.readFile(file(name), 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT' && fallback !== undefined) return fallback;
    throw error;
  }
}

export const readMedia = () => readJson('media.json', []);
export const readAuthors = () => readJson('authors.json', []);

export async function writeMedia(records) {
  await fs.writeFile(file('media.json'), `${JSON.stringify(records, null, 2)}\n`);
}

/** Orden estable de las propiedades de un registro (legible en los diffs). */
const ORDER = [
  'id',
  'path',
  'width',
  'height',
  'widths',
  'alt',
  'title',
  'caption',
  'description',
  'takenAt',
  'location',
  'camera',
  'focal',
  'placeholder',
  'credit',
  'original',
  'demo',
];
export function orderRecord(record) {
  const ordered = {};
  for (const key of ORDER) if (record[key] !== undefined) ordered[key] = record[key];
  for (const key of Object.keys(record)) if (!(key in ordered)) ordered[key] = record[key];
  return ordered;
}

/**
 * Imágenes que se muestran a pantalla completa según el contenido: portadas de artículo,
 * bloques de imagen «full» y galerías horizontales. Solo estas (y las panorámicas)
 * reciben la versión de 2400 px.
 */
export async function fullscreenMedia() {
  const ids = new Set();
  const articles = await readJson('articles.json', []);
  for (const article of articles) {
    ids.add(article.cover);
    const body = await readJson(path.join('articles', `${article.slug}.json`), null);
    for (const block of body?.blocks ?? []) {
      if (block.type === 'image' && block.layout === 'full') ids.add(block.media);
    }
  }
  for (const gallery of await readJson('galleries.json', [])) {
    if (gallery.layout === 'horizontal') for (const item of gallery.items) ids.add(item.media);
  }
  return ids;
}

/** Anchos que corresponden a una imagen (sin ampliar nunca el original). */
export function widthsFor(id, width, height, fullscreen) {
  const panorama = width / height >= MEDIA_CONFIG.panoramaRatio;
  const max = fullscreen.has(id) || panorama ? MEDIA_CONFIG.fullscreenWidth : 1600;
  const widths = MEDIA_CONFIG.widths.filter((w) => w <= Math.min(max, width));
  return widths.length ? widths : [width];
}
