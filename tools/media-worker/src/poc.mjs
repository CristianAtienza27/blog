/**
 * Prueba de concepto del procesador multimedia.
 *
 *   node src/poc.mjs [--entrada DIR] [--originales DIR] [--salida DIR] [--manifiesto FILE]
 *
 * Para cada foto de la carpeta de entrada:
 * 1. Archiva el original sin modificar (SHA-256 verificado).
 * 2. Guarda su EXIF completo, GPS incluido, solo en el manifiesto privado.
 * 3. Genera versiones AVIF y WebP sin metadatos, conservando el perfil de color.
 * 4. Verifica con exiftool que las versiones públicas no contienen GPS ni EXIF.
 * 5. Mide tiempos, pesos, fidelidad de color y nitidez, y genera un informe.
 *
 * Un DNG (ProRAW) solo se archiva; si hay una versión exportada con el mismo nombre
 * (IMG_0001.DNG + IMG_0001.HEIC/JPG), las versiones públicas salen de esa exportación.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import sharp from 'sharp';
import { DIRS, MANIFEST, RASTER_EXT, RAW_EXT } from './config.mjs';
import {
  exif,
  gpsPoint,
  gpsTags,
  hdrGainMap,
  iccDescription,
  publicCamera,
  takenAt,
} from './inspect.mjs';
import { archiveOriginal, processImage, slugify } from './process.mjs';
import { writeReport } from './report.mjs';

const PREFERRED = ['.heic', '.heif', '.hif', '.jpg', '.jpeg', '.png', '.webp'];

/** Agrupa los archivos por nombre base: un DNG y su exportación forman un grupo. */
async function groups(inbox) {
  const files = (await fs.readdir(inbox)).filter((f) => !f.startsWith('.'));
  const map = new Map();
  for (const file of files) {
    const ext = path.extname(file).toLowerCase();
    if (!RASTER_EXT.has(ext) && !RAW_EXT.has(ext)) continue;
    const base = path.basename(file, path.extname(file));
    const group = map.get(base) ?? { base, raw: null, rasters: [] };
    if (RAW_EXT.has(ext)) group.raw = file;
    else group.rasters.push(file);
    map.set(base, group);
  }
  for (const group of map.values()) {
    group.rasters.sort(
      (a, b) =>
        PREFERRED.indexOf(path.extname(a).toLowerCase()) -
        PREFERRED.indexOf(path.extname(b).toLowerCase()),
    );
  }
  return [...map.values()].sort((a, b) => a.base.localeCompare(b.base));
}

/** Resumen de un original: formato, dimensiones, perfil, HDR y presencia de GPS. */
async function describeOriginal(file) {
  const tags = await exif(file);
  const meta = await sharp(file)
    .metadata()
    .catch(() => null);
  return {
    tags,
    summary: {
      format: tags['File:FileType'] ?? path.extname(file).slice(1).toUpperCase(),
      mime: tags['File:MIMEType'] ?? null,
      width: meta?.width ?? tags['IFD0:ImageWidth'] ?? null,
      height: meta?.height ?? tags['IFD0:ImageHeight'] ?? null,
      megapixels: meta ? Number(((meta.width * meta.height) / 1e6).toFixed(1)) : null,
      depth: meta?.depth ?? null,
      orientation: meta?.orientation ?? null,
      icc: iccDescription(tags),
      hdrGainMap: await hdrGainMap(file, tags),
      hasGps: gpsTags(tags).length > 0,
      compression: tags['IFD0:Compression'] ?? tags['SubIFD:Compression'] ?? null,
      camera: publicCamera(tags),
      takenAt: takenAt(tags),
      readError: tags._error ?? null,
    },
  };
}

export async function runPoc(options = {}) {
  const dirs = {
    inbox: options.inbox ?? DIRS.inbox,
    originals: options.originals ?? DIRS.originals,
    output: options.output ?? DIRS.output,
    manifest: options.manifest ?? MANIFEST,
  };
  await Promise.all(
    [dirs.inbox, dirs.originals, dirs.output].map((d) => fs.mkdir(d, { recursive: true })),
  );

  let manifest = { aviso: 'PRIVADO: contiene GPS y EXIF completo. Nunca se publica.', fotos: {} };
  try {
    manifest = JSON.parse(await fs.readFile(dirs.manifest, 'utf8'));
  } catch {
    // Manifiesto nuevo.
  }

  const results = [];
  for (const group of await groups(dirs.inbox)) {
    const id = slugify(group.base);
    console.log(`→ ${group.base}`);
    const result = { id, base: group.base, raw: null, original: null, processed: null, status: '' };
    const entry = { id, importadoEn: new Date().toISOString(), archivos: [] };

    if (group.raw) {
      const src = path.join(dirs.inbox, group.raw);
      const { tags, summary } = await describeOriginal(src);
      const archived = await archiveOriginal(src, id, dirs.originals);
      result.raw = { name: group.raw, ...summary, ...archived };
      entry.archivos.push({
        papel: 'raw',
        nombre: group.raw,
        ...archived,
        exif: tags,
        gps: gpsPoint(tags),
      });
    }

    const rendition = group.rasters[0];
    if (rendition) {
      const src = path.join(dirs.inbox, rendition);
      const { tags, summary } = await describeOriginal(src);
      const archived = await archiveOriginal(src, id, dirs.originals);
      result.original = { name: rendition, ...summary, ...archived };
      entry.archivos.push({
        papel: group.raw ? 'exportacion' : 'original',
        nombre: rendition,
        ...archived,
        exif: tags,
        gps: gpsPoint(tags),
      });
      try {
        result.processed = await processImage(src, id, dirs.output, {
          originalIcc: summary.icc,
          hdrSource: summary.hdrGainMap,
        });
        result.status = group.raw
          ? 'DNG archivado; versiones públicas desde la exportación'
          : 'procesada';
      } catch (error) {
        result.status = `error: ${error.message}`;
      }
    } else {
      result.status = 'DNG archivado; falta la versión exportada (JPEG/HEIC) para publicarla';
    }

    manifest.fotos[id] = entry;
    results.push(result);
    console.log(`  ${result.status}`);
  }

  await fs.writeFile(dirs.manifest, `${JSON.stringify(manifest, null, 2)}\n`);
  await writeReport(results, dirs.output);
  return results;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      entrada: { type: 'string' },
      originales: { type: 'string' },
      salida: { type: 'string' },
      manifiesto: { type: 'string' },
    },
  });
  const results = await runPoc({
    inbox: values.entrada,
    originals: values.originales,
    output: values.salida,
    manifest: values.manifiesto,
  });
  const failed = results.filter((r) => r.status.startsWith('error'));
  console.log(
    `\n${results.length} grupos · ${failed.length} errores · informe en ${values.salida ?? DIRS.output}/informe.html`,
  );
  if (failed.length) process.exit(1);
}
