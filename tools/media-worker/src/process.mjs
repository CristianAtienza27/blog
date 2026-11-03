import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import sharp from 'sharp';
import { AVIF, WEBP, WIDTHS } from './config.mjs';
import { exif, gpsTags, iccDescription, metadataGroups, run, sha256 } from './inspect.mjs';

// Sin caché de libvips: los tiempos medidos son los reales de cada conversión.
sharp.cache(false);

const elapsed = (start) => Math.round(performance.now() - start);

export function slugify(name) {
  return name
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

/**
 * Copia el original sin modificarlo al almacén de originales y verifica que la copia
 * es idéntica byte a byte (SHA-256).
 */
export async function archiveOriginal(src, id, originalsDir) {
  const ext = path.extname(src).toLowerCase().replace('.jpeg', '.jpg').replace('.hif', '.heif');
  const file = `${id}${ext}`;
  const dest = path.join(originalsDir, file);
  const hash = await sha256(src);
  try {
    if ((await sha256(dest)) !== hash) throw new Error(`Ya existe ${file} con otro contenido`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await fs.copyFile(src, dest, fs.constants.COPYFILE_EXCL);
  }
  const copy = await sha256(dest);
  const { size } = await fs.stat(dest);
  return { file, bytes: size, sha256: hash, identical: copy === hash };
}

/** Grupos de metadatos que nunca deben aparecer en una versión pública. */
const PRIVATE_GROUPS = /^(IFD\d|ExifIFD|GPS|InteropIFD|XMP|IPTC|MakerNotes|Apple|Photoshop)/;

/** Comprueba con exiftool qué metadatos contiene una versión pública. */
export async function inspectPublic(file, expectedIcc) {
  const tags = await exif(file);
  const gps = gpsTags(tags);
  const groups = metadataGroups(tags);
  const privateGroups = groups.filter((group) => PRIVATE_GROUPS.test(group));
  const icc = iccDescription(tags);
  return {
    gps,
    groups,
    privateGroups,
    icc,
    orientationTag: tags['IFD0:Orientation'] ?? null,
    ok:
      gps.length === 0 && privateGroups.length === 0 && (expectedIcc ? icc === expectedIcc : true),
  };
}

/** Decodifica una vez (orientación aplicada, perfil ICC conservado) a formato nativo de libvips. */
async function decodeMaster(src, id) {
  const master = path.join(os.tmpdir(), `${id}-${process.pid}.v`);
  const start = performance.now();
  const info = await sharp(src, { limitInputPixels: 200_000_000 })
    .autoOrient()
    .keepIccProfile()
    .toFile(master);
  return { master, width: info.width, height: info.height, ms: elapsed(start) };
}

/** Versiones públicas AVIF y WebP en cada ancho (sin ampliar nunca el original). */
async function encodeDerivatives(master, width, id, outDir) {
  const widths = WIDTHS.filter((w) => w <= width);
  if (!widths.length) widths.push(width);
  const derivatives = [];
  for (const w of widths) {
    for (const format of ['avif', 'webp']) {
      const file = `${id}-${w}.${format}`;
      const start = performance.now();
      const pipeline = sharp(master).resize({ width: w }).keepIccProfile();
      const info = await (format === 'avif' ? pipeline.avif(AVIF) : pipeline.webp(WEBP)).toFile(
        path.join(outDir, file),
      );
      derivatives.push({
        format,
        width: info.width,
        height: info.height,
        bytes: info.size,
        ms: elapsed(start),
        file,
      });
    }
  }
  return { widths, derivatives };
}

/** Diferencia de color media y máxima (CIEDE2000) entre dos imágenes del mismo tamaño. */
async function deltaE(reference, candidate, tmp) {
  const lab = async (file, name) => {
    const out = path.join(tmp, `${name}.v`);
    const meta = await sharp(file).metadata();
    const args = ['icc_import', file, out];
    args.push(...(meta.hasProfile ? ['--embedded'] : ['--input-profile', 'srgb']));
    await run('vips', args);
    return out;
  };
  const [a, b] = await Promise.all([lab(reference, 'a'), lab(candidate, 'b')]);
  const diff = path.join(tmp, 'de.v');
  await run('vips', ['dE00', a, b, diff]);
  const [{ stdout: avg }, { stdout: max }] = await Promise.all([
    run('vips', ['avg', diff]),
    run('vips', ['max', diff]),
  ]);
  return { avg: Number(Number(avg).toFixed(2)), max: Number(Number(max).toFixed(2)) };
}

/**
 * Nivel de detalle: desviación típica del laplaciano en escala de grises.
 * Se calcula sobre los píxeles decodificados (stats() de sharp ignora las operaciones previas).
 */
async function detail(file) {
  const { data, info } = await sharp(file).greyscale().raw().toBuffer({ resolveWithObject: true });
  const { width, height } = info;
  let sum = 0;
  let sumSq = 0;
  let n = 0;
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x;
      const v = data[i - width] + data[i + width] + data[i - 1] + data[i + 1] - 4 * data[i];
      sum += v;
      sumSq += v * v;
      n++;
    }
  }
  const mean = sum / n;
  return Math.sqrt(sumSq / n - mean * mean);
}

/** Media por canal de una región, sobre los píxeles decodificados. */
export async function regionMean(file, region) {
  const { data, info } = await sharp(file)
    .extract(region)
    .raw()
    .toBuffer({ resolveWithObject: true });
  const means = Array(info.channels).fill(0);
  for (let i = 0; i < data.length; i++) means[i % info.channels] += data[i];
  return means.map((m) => m / (data.length / info.channels));
}

/**
 * Métricas de calidad a 1600 px (o el mayor ancho disponible):
 * - ΔE00 frente a una referencia sin pérdidas con el mismo perfil (fidelidad de color).
 * - ΔE00 si se convirtiera a sRGB (lo que se perdería en pantallas P3).
 * - Detalle relativo frente a la referencia (nitidez).
 */
async function measureQuality(master, widths, id, outDir) {
  const width = widths.includes(1600) ? 1600 : widths.at(-1);
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), `${id}-q-`));
  try {
    const reference = path.join(tmp, 'ref.png');
    const srgb = path.join(tmp, 'srgb.png');
    await sharp(master)
      .resize({ width })
      .keepIccProfile()
      .png({ compressionLevel: 1 })
      .toFile(reference);
    // Salida por defecto de sharp: convierte el perfil embebido a sRGB.
    await sharp(master).resize({ width }).png({ compressionLevel: 1 }).toFile(srgb);
    const refDetail = await detail(reference);
    const result = { width, srgbConversion: await deltaE(reference, srgb, tmp) };
    for (const format of ['avif', 'webp']) {
      const file = path.join(outDir, `${id}-${width}.${format}`);
      result[format] = {
        deltaE: await deltaE(reference, file, tmp),
        detail: Number(((await detail(file)) / refDetail).toFixed(3)),
      };
    }
    return result;
  } finally {
    await fs.rm(tmp, { recursive: true, force: true });
  }
}

/** Recortes a tamaño real del centro de la imagen para comparar visualmente. */
async function crops(master, widths, id, outDir) {
  const width = widths.at(-1);
  const meta = await sharp(path.join(outDir, `${id}-${width}.avif`)).metadata();
  const w = Math.min(640, meta.width);
  const h = Math.min(480, meta.height);
  const region = {
    left: Math.round((meta.width - w) / 2),
    top: Math.round((meta.height - h) / 2),
    width: w,
    height: h,
  };
  const files = {};
  const save = async (input, name) => {
    const file = `${id}-recorte-${name}.png`;
    await sharp(input).extract(region).keepIccProfile().png().toFile(path.join(outDir, file));
    files[name] = file;
  };
  const scaled = await sharp(master)
    .resize({ width })
    .keepIccProfile()
    .png({ compressionLevel: 1 })
    .toBuffer();
  await save(scaled, 'original');
  await save(path.join(outDir, `${id}-${width}.avif`), 'avif');
  await save(path.join(outDir, `${id}-${width}.webp`), 'webp');
  return { width, region, files };
}

/** Vista previa difuminada en sRGB: unos cientos de bytes sin perfil embebido. */
async function placeholder(master) {
  const buffer = await sharp(master).resize({ width: 24 }).webp({ quality: 45 }).toBuffer();
  return `data:image/webp;base64,${buffer.toString('base64')}`;
}

/** Procesa una imagen: decodificación, versiones públicas, verificación y métricas. */
export async function processImage(src, id, outDir, { originalIcc, hdrSource = false }) {
  const decoded = await decodeMaster(src, id);
  try {
    const encoded = await encodeDerivatives(decoded.master, decoded.width, id, outDir);
    const checks = [];
    for (const d of encoded.derivatives) {
      checks.push({
        file: d.file,
        ...(await inspectPublic(path.join(outDir, d.file), originalIcc)),
      });
    }
    const quality = await measureQuality(decoded.master, encoded.widths, id, outDir);
    const comparison = await crops(decoded.master, encoded.widths, id, outDir);
    return {
      width: decoded.width,
      height: decoded.height,
      decodeMs: decoded.ms,
      encodeMs: encoded.derivatives.reduce((sum, d) => sum + d.ms, 0),
      widths: encoded.widths,
      derivatives: encoded.derivatives,
      checks,
      quality,
      comparison,
      placeholder: await placeholder(decoded.master),
      // libvips solo decodifica la imagen base: el mapa de ganancia HDR no se usa.
      dynamicRange: hdrSource
        ? 'SDR (derivada de un original HDR; el mapa de ganancia no se conserva)'
        : 'SDR',
    };
  } finally {
    await fs.rm(decoded.master, { force: true });
  }
}
