/**
 * Medición del procesamiento de imágenes (fase 6.1.4). Se monta en tiempo de ejecución en la
 * imagen del media-worker (/app/src/bench.mjs); el media-worker no se modifica.
 *
 * Seguridad:
 *   - Solo lee /bench-in, montada en solo lectura, que contiene COPIAS de prueba (fuera del
 *     repositorio). El contenedor no monta media-src/, public/media/ ni src/content/.
 *   - Toda la salida va a /tmp/bench-* dentro del contenedor. Aborta si no es así.
 *
 * Modos (MODE):
 *   derive     el código real: derive() con force, como media:propia / media:sync
 *   fases      mismo pipeline y ajustes, cronometrando decodificación, AVIF y WebP por separado
 *   verificar  intenta decodificar cada archivo de /bench-in (tras una interrupción)
 *
 * Variables: IMAGES (archivos de /bench-in, separados por comas; se procesan en paralelo,
 * pueden repetirse), CONCURRENCY (hilos de libvips; vacío = valor por defecto).
 * Resultado: una línea «RESULTADO {json}» en la salida estándar.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import sharp from 'sharp';
import { MEDIA_CONFIG, PUBLIC } from './config.mjs';
import { derive } from './derive.mjs';
import { widthsFor } from './content.mjs';

const IN = '/bench-in';
const SAFE = '/tmp/bench-';
for (const [name, value] of [
  ['MEDIA_PUBLIC', PUBLIC],
  ['TMPDIR', os.tmpdir()],
]) {
  if (!value.startsWith(SAFE)) {
    console.error(`ABORTADO: ${name}=${value} no está bajo ${SAFE}*`);
    process.exit(3);
  }
}
for (const forbidden of ['/data', '/content', '/public-media']) {
  if (fs.existsSync(forbidden)) {
    console.error(`ABORTADO: ${forbidden} existe en el contenedor (no debe montarse)`);
    process.exit(3);
  }
}

const mode = process.env.MODE ?? 'derive';
const images = (process.env.IMAGES ?? '').split(',').filter(Boolean);
if (process.env.CONCURRENCY) sharp.concurrency(Number(process.env.CONCURRENCY));
sharp.cache(false);

const cgroup = (file) => {
  try {
    return Number(fs.readFileSync(`/sys/fs/cgroup/${file}`, 'utf8').trim());
  } catch {
    return null;
  }
};
const mib = (bytes) => (bytes === null ? null : Math.round((bytes / 1048576) * 10) / 10);
const sha256 = async (file) =>
  createHash('sha256')
    .update(await fsp.readFile(file))
    .digest('hex');
const ms = (start) => Math.round(performance.now() - start);

async function runDerive(file, index) {
  const src = path.join(IN, file);
  const id = `bench-${index}-${path
    .basename(file, path.extname(file))
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')}`;
  const record = { id, original: { sha256: await sha256(src) } };
  const meta = await sharp(src, { limitInputPixels: MEDIA_CONFIG.limitInputPixels }).metadata();
  const start = performance.now();
  // Peor caso: se trata como imagen a pantalla completa (hasta 2400 px).
  const result = await derive(record, src, `bench-${index}`, new Set([id]), { force: true });
  return {
    archivo: file,
    megapixeles: Math.round(((meta.width * meta.height) / 1e6) * 10) / 10,
    formato: meta.format,
    anchos: result.widths,
    ms: ms(start),
    bytesSalida: result.files.reduce((sum, f) => sum + f.bytes, 0),
    archivos: result.files.length,
  };
}

async function runPhases(file, index) {
  const src = path.join(IN, file);
  const options = { limitInputPixels: MEDIA_CONFIG.limitInputPixels };
  const meta = await sharp(src, options).metadata();
  const out = path.join(PUBLIC, `fases-${index}`);
  await fsp.mkdir(out, { recursive: true });
  const master = path.join(os.tmpdir(), `fases-${index}-${process.pid}.v`);
  let t = performance.now();
  const decoded = await sharp(src, options).autoOrient().keepIccProfile().toFile(master);
  const phases = { decodificacionMs: ms(t), avifMs: 0, webpMs: 0, porAncho: [] };
  const widths = widthsFor('x', decoded.width, decoded.height, new Set(['x']));
  for (const width of widths) {
    t = performance.now();
    await sharp(master)
      .resize({ width })
      .keepIccProfile()
      .avif(MEDIA_CONFIG.avif)
      .toFile(path.join(out, `${width}.avif`));
    const avif = ms(t);
    t = performance.now();
    await sharp(master)
      .resize({ width })
      .keepIccProfile()
      .webp(MEDIA_CONFIG.webp)
      .toFile(path.join(out, `${width}.webp`));
    const webp = ms(t);
    phases.avifMs += avif;
    phases.webpMs += webp;
    phases.porAncho.push({ ancho: width, avifMs: avif, webpMs: webp });
  }
  const masterBytes = (await fsp.stat(master)).size;
  await fsp.rm(master, { force: true });
  return {
    archivo: file,
    megapixeles: Math.round(((meta.width * meta.height) / 1e6) * 10) / 10,
    masterMiB: mib(masterBytes),
    ...phases,
  };
}

async function verify() {
  const files = [];
  for (const entry of await fsp.readdir(IN, { recursive: true, withFileTypes: true })) {
    if (!entry.isFile()) continue;
    const file = path.join(entry.parentPath, entry.name);
    const { size } = await fsp.stat(file);
    try {
      const { info } = await sharp(file).raw().toBuffer({ resolveWithObject: true });
      files.push({
        archivo: path.relative(IN, file),
        bytes: size,
        valido: true,
        ancho: info.width,
      });
    } catch (error) {
      files.push({
        archivo: path.relative(IN, file),
        bytes: size,
        valido: false,
        error: error.message.split('\n')[0],
      });
    }
  }
  return files;
}

await fsp.mkdir(PUBLIC, { recursive: true });
await fsp.mkdir(os.tmpdir(), { recursive: true });
const start = performance.now();
let items;
if (mode === 'verificar') items = await verify();
else
  items = await Promise.all(
    images.map((file, i) => (mode === 'fases' ? runPhases(file, i) : runDerive(file, i))),
  );

const usage = process.resourceUsage();
console.log(
  `RESULTADO ${JSON.stringify({
    modo: mode,
    imagenes: images,
    paralelo: images.length,
    concurrenciaLibvips: sharp.concurrency(),
    cpusVisibles: os.availableParallelism(),
    totalMs: ms(start),
    picoCgroupMiB: mib(cgroup('memory.peak')),
    limiteCgroupMiB: mib(cgroup('memory.max')),
    rssMaxMiB: Math.round((usage.maxRSS / 1024) * 10) / 10,
    cpuUsuarioS: Math.round(usage.userCPUTime / 1e5) / 10,
    cpuSistemaS: Math.round(usage.systemCPUTime / 1e5) / 10,
    resultados: items,
  })}`,
);
