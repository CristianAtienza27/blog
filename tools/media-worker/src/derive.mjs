/**
 * Versiones públicas de una imagen (producción).
 *
 * - Decodifica una vez: orientación aplicada y perfil de color conservado (Display P3
 *   en el iPhone). Solo un original CMYK se convierte a sRGB, porque la web no lo admite.
 * - Genera AVIF y WebP en los anchos que correspondan, sin metadatos salvo el ICC.
 * - Los nombres llevan un hash del original y de los ajustes: si cambia cualquiera de
 *   los dos, cambia el nombre y la caché de un año no sirve versiones antiguas.
 * - Es idempotente: si los archivos ya existen con el mismo hash, no se recodifican.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import sharp from 'sharp';
import { ENCODING_FINGERPRINT, MEDIA_CONFIG, PUBLIC } from './config.mjs';
import { widthsFor } from './content.mjs';
import { exif, iccDescription } from './inspect.mjs';
import { inspectPublic } from './process.mjs';

sharp.cache(false);

export const FORMATS = ['avif', 'webp'];

/** Hash de 8 caracteres: original + ajustes de codificación. */
export function versionHash(originalSha256) {
  return createHash('sha256')
    .update(`${originalSha256}|${ENCODING_FINGERPRINT}`)
    .digest('hex')
    .slice(0, 8);
}

export const fileName = (id, hash, width, format) => `${id}.${hash}-${width}.${format}`;

async function exists(file) {
  try {
    await fs.access(file);
    return true;
  } catch {
    return false;
  }
}

/**
 * Genera (o reutiliza) las versiones de un registro y devuelve sus campos técnicos.
 * `section` es la carpeta pública (viajes, motor…).
 */
export async function derive(record, originalPath, section, fullscreen, { force = false } = {}) {
  const hash = versionHash(record.original.sha256);
  const folder = path.join(PUBLIC, section);
  await fs.mkdir(folder, { recursive: true });

  const meta = await sharp(originalPath, {
    limitInputPixels: MEDIA_CONFIG.limitInputPixels,
  }).metadata();
  const cmyk = meta.space === 'cmyk';
  const originalIcc = cmyk ? null : iccDescription(await exif(originalPath));

  const start = performance.now();
  const master = path.join(os.tmpdir(), `${record.id}-${process.pid}.v`);
  let pipeline = sharp(originalPath, {
    limitInputPixels: MEDIA_CONFIG.limitInputPixels,
  }).autoOrient();
  pipeline = cmyk ? pipeline.toColourspace('srgb') : pipeline.keepIccProfile();
  const decoded = await pipeline.toFile(master);

  try {
    const widths = widthsFor(record.id, decoded.width, decoded.height, fullscreen);
    const files = [];
    for (const width of widths) {
      for (const format of FORMATS) {
        const name = fileName(record.id, hash, width, format);
        const out = path.join(folder, name);
        if (force || !(await exists(out))) {
          let p = sharp(master).resize({ width });
          if (!cmyk) p = p.keepIccProfile();
          await (format === 'avif' ? p.avif(MEDIA_CONFIG.avif) : p.webp(MEDIA_CONFIG.webp)).toFile(
            out,
          );
        }
        const info = await sharp(out).metadata();
        const { size } = await fs.stat(out);
        files.push({ name, width: info.width, height: info.height, bytes: size, format });
      }
    }

    const { width: pw, quality } = MEDIA_CONFIG.placeholder;
    const preview = await sharp(master).resize({ width: pw }).webp({ quality }).toBuffer();
    const largest = files.at(-1);
    return {
      path: `${section}/${record.id}.${hash}`,
      width: largest.width,
      height: largest.height,
      widths,
      placeholder: `data:image/webp;base64,${preview.toString('base64')}`,
      files: files.map((f) => ({ ...f, file: path.join(folder, f.name) })),
      originalIcc,
      ms: Math.round(performance.now() - start),
    };
  } finally {
    await fs.rm(master, { force: true });
  }
}

/** Verifica con exiftool que una versión pública no lleva GPS, EXIF ni XMP. */
export function checkPublic(file, originalIcc) {
  return inspectPublic(file, originalIcc);
}
