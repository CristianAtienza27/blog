/**
 * Procesador multimedia de la revista.
 *
 *   node src/media.mjs externa <url de Commons> --id <id> --seccion <carpeta>
 *   node src/media.mjs propia <archivo en entrada/> --id <id> --seccion <carpeta> [--raw <DNG>] [--reemplazar]
 *   node src/media.mjs sincronizar [--forzar]
 *   node src/media.mjs verificar
 *
 * - externa: descarga el original de Wikimedia Commons (licencia comprobada, SHA-1
 *   verificado), registra autoría y licencia y genera las versiones públicas.
 * - propia: archiva una foto propia sin modificarla, guarda su EXIF completo (GPS
 *   incluido) solo en el manifiesto privado y genera las versiones públicas. Con
 *   --reemplazar sustituye una imagen existente manteniendo su id y sus textos. Con --raw
 *   archiva también el Apple ProRAW (DNG) sin modificarlo ni revelarlo, asociado a la exportación.
 * - sincronizar: regenera lo que falte o haya cambiado y elimina versiones huérfanas.
 * - verificar: comprueba que existen todas las versiones y que ninguna lleva metadatos.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import sharp from 'sharp';
import { commonsInfo, download, sha1File } from './commons.mjs';
import { DIRS, MANIFEST, MEDIA_CONFIG, PUBLIC } from './config.mjs';
import { fullscreenMedia, orderRecord, readAuthors, readMedia, writeMedia } from './content.mjs';
import { FORMATS, checkPublic, derive, fileName, versionHash } from './derive.mjs';
import { exif, gpsPoint, publicCamera, sha256, takenAt } from './inspect.mjs';

const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const FORMAT_EXT = { jpeg: 'jpg', png: 'png', webp: 'webp', heif: 'heic', avif: 'avif' };

const kb = (b) => (b >= 1e6 ? `${(b / 1e6).toFixed(2)} MB` : `${Math.round(b / 1e3)} kB`);
const sectionOf = (record) => record.path?.split('/')[0];

async function exists(file) {
  try {
    await fs.access(file);
    return true;
  } catch {
    return false;
  }
}

function requireSlug(value, name) {
  if (!value || !SLUG.test(value))
    throw new Error(`--${name} debe ser un slug (minúsculas y guiones)`);
  return value;
}

/** Formato real del original según libvips (HEIC del iPhone = heif con HEVC). */
async function originalFormat(file) {
  const meta = await sharp(file, { limitInputPixels: MEDIA_CONFIG.limitInputPixels }).metadata();
  const format =
    meta.format === 'heif'
      ? path.extname(file).toLowerCase() === '.heic'
        ? 'heic'
        : 'heif'
      : meta.format;
  return { format, width: meta.width, height: meta.height };
}

/** Garantiza que el original está archivado y es el registrado (SHA-256). */
async function ensureOriginal(record) {
  const file = path.join(DIRS.originals, record.original.file);
  if (!(await exists(file))) {
    if (record.credit.source === 'propia' || !record.credit.sourceUrl) {
      throw new Error(
        `${record.id}: falta el original ${record.original.file} en media-src/originales`,
      );
    }
    console.log(`  ${record.id}: recuperando el original desde ${record.credit.sourceUrl}`);
    await download(await commonsInfo(record.credit.sourceUrl), file);
  }
  const hash = await sha256(file);
  if (hash !== record.original.sha256) {
    throw new Error(`${record.id}: el original archivado no coincide con el registrado (SHA-256)`);
  }
  return file;
}

/** Aplica los campos técnicos generados a un registro. */
function withDerived(record, derived) {
  const { files: _files, originalIcc: _icc, ms: _ms, ...fields } = derived;
  return orderRecord({ ...record, ...fields });
}

async function saveRecord(record) {
  const records = await readMedia();
  const index = records.findIndex((r) => r.id === record.id);
  if (index >= 0) records[index] = record;
  else records.push(record);
  await writeMedia(records.map(orderRecord));
}

async function processRecord(record, section, { force = false } = {}) {
  const originalPath = await ensureOriginal(record);
  const derived = await derive(record, originalPath, section, await fullscreenMedia(), { force });
  const checks = await Promise.all(
    derived.files.map((f) => checkPublic(f.file, derived.originalIcc)),
  );
  const bad = checks.filter((c) => !c.ok);
  if (bad.length) throw new Error(`${record.id}: versiones con metadatos no permitidos`);
  const total = derived.files.reduce((s, f) => s + f.bytes, 0);
  console.log(
    `  ${record.id}: ${derived.widths.join('/')} px · ${derived.files.length} archivos · ${kb(total)} · ${derived.ms} ms`,
  );
  return withDerived(record, derived);
}

async function externa(url, id, section) {
  requireSlug(id, 'id');
  requireSlug(section, 'seccion');
  const info = await commonsInfo(url);
  const records = await readMedia();
  const existing = records.find((r) => r.id === id);
  const ext = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' }[info.mime];
  if (!ext) throw new Error(`Formato no admitido para descarga: ${info.mime}`);
  const file = `${id}.${ext}`;
  const dest = path.join(DIRS.originals, file);
  await fs.mkdir(DIRS.originals, { recursive: true });
  if (!(await exists(dest))) await download(info, dest);
  else if ((await sha1File(dest)) !== info.sha1) {
    throw new Error(`${file} no coincide con el original publicado en Commons (SHA-1)`);
  }
  const hash = await sha256(dest);
  if (existing && existing.original.sha256 !== hash) {
    throw new Error(`${id} ya existe con otro original; usa otro id`);
  }
  const { format, width, height } = await originalFormat(dest);
  const record = {
    ...(existing ?? { id, alt: '', focal: { x: 0.5, y: 0.5 } }),
    credit: info.credit,
    original: { file, format, width, height, bytes: info.bytes, sha256: hash, url: info.fileUrl },
    demo: existing?.demo ?? true,
  };
  console.log(`→ ${id} · ${info.credit.author} · ${info.credit.license}`);
  await saveRecord(await processRecord(record, section));
}

/** Archiva un ProRAW (DNG) sin modificarlo; el anterior, si cambia, se conserva con otro nombre. */
async function archiveRaw(name, id) {
  if (path.extname(name).toLowerCase() !== '.dng') throw new Error('--raw debe ser un archivo DNG');
  const src = path.join(DIRS.inbox, name);
  const hash = await sha256(src);
  const file = `${id}.dng`;
  const dest = path.join(DIRS.originals, file);
  if ((await exists(dest)) && (await sha256(dest)) !== hash) {
    const previous = (await sha256(dest)).slice(0, 8);
    await fs.rename(dest, dest.replace(/\.dng$/, `.reemplazado-${previous}.dng`));
  }
  if (!(await exists(dest))) await fs.copyFile(src, dest, fs.constants.COPYFILE_EXCL);
  if ((await sha256(dest)) !== hash) throw new Error('La copia del DNG no es idéntica');
  return { file, bytes: (await fs.stat(dest)).size, sha256: hash };
}

async function propia(name, id, section, replace, rawName) {
  requireSlug(id, 'id');
  requireSlug(section, 'seccion');
  const src = path.join(DIRS.inbox, name);
  const records = await readMedia();
  const existing = records.find((r) => r.id === id);
  if (existing && !replace) throw new Error(`${id} ya existe; usa --reemplazar para sustituirla`);

  const hash = await sha256(src);
  const { format, width, height } = await originalFormat(src);
  const file = `${id}.${FORMAT_EXT[format] ?? path.extname(name).slice(1).toLowerCase()}`;
  const dest = path.join(DIRS.originals, file);
  await fs.mkdir(DIRS.originals, { recursive: true });

  // El original anterior nunca se borra: se conserva con otro nombre.
  for (const previous of [dest, existing && path.join(DIRS.originals, existing.original.file)]) {
    if (previous && (await exists(previous)) && (await sha256(previous)) !== hash) {
      const kept = previous.replace(
        /(\.[^.]+)$/,
        `.reemplazado-${(await sha256(previous)).slice(0, 8)}$1`,
      );
      await fs.rename(previous, kept);
      console.log(`  original anterior conservado como ${path.basename(kept)}`);
    }
  }
  if (!(await exists(dest))) await fs.copyFile(src, dest, fs.constants.COPYFILE_EXCL);
  if ((await sha256(dest)) !== hash) throw new Error('La copia del original no es idéntica');

  const tags = await exif(src);
  let manifest = { aviso: 'PRIVADO: contiene GPS y EXIF completo. Nunca se publica.', fotos: {} };
  try {
    manifest = JSON.parse(await fs.readFile(MANIFEST, 'utf8'));
  } catch {
    // Manifiesto nuevo.
  }
  manifest.fotos[id] = {
    nombreOriginal: name,
    archivo: file,
    sha256: hash,
    importadoEn: new Date().toISOString(),
    gps: gpsPoint(tags),
    exif: tags,
  };
  await fs.writeFile(MANIFEST, `${JSON.stringify(manifest, null, 2)}\n`);

  const [author] = await readAuthors();
  const camera = publicCamera(tags);
  const date = takenAt(tags);
  const record = {
    // Al reemplazar se vacía el texto alternativo: describía otra imagen y debe reescribirse.
    ...(existing ? { ...existing, alt: '' } : { id, alt: '', focal: { x: 0.5, y: 0.5 } }),
    ...(date ? { takenAt: date } : {}),
    ...(Object.keys(camera).length ? { camera } : {}),
    credit: { author: author.name, source: 'propia', license: 'derechos-reservados' },
    original: {
      file,
      format,
      width,
      height,
      bytes: (await fs.stat(dest)).size,
      sha256: hash,
      ...(rawName ? { raw: await archiveRaw(rawName, id) } : {}),
    },
    demo: false,
  };
  console.log(`→ ${id} · foto propia · ${name}`);
  if (existing)
    console.log(
      '  Reemplazo: escribe el nuevo texto alternativo y revisa pie y punto focal en media.json',
    );
  await saveRecord(await processRecord(record, section, { force: true }));
}

/** Archivos públicos esperados según media.json. */
async function expectedFiles(records) {
  const expected = new Set();
  for (const r of records) {
    const hash = versionHash(r.original.sha256);
    for (const w of r.widths)
      for (const f of FORMATS) expected.add(path.join(sectionOf(r), fileName(r.id, hash, w, f)));
  }
  return expected;
}

async function publicFiles() {
  const out = [];
  for (const section of await fs.readdir(PUBLIC).catch(() => [])) {
    const dir = path.join(PUBLIC, section);
    if (!(await fs.stat(dir)).isDirectory()) continue;
    for (const f of await fs.readdir(dir)) out.push(path.join(section, f));
  }
  return out;
}

/**
 * Procesa cada imagen por separado: un fallo (un original ausente, una descarga limitada)
 * no detiene el resto. Las versiones de una imagen que falla no se tocan.
 */
async function sincronizar(force) {
  const records = await readMedia();
  const updated = [];
  const failures = [];
  for (const record of records) {
    try {
      updated.push(await processRecord(record, sectionOf(record), { force }));
    } catch (error) {
      failures.push(`${record.id}: ${error.message}`);
      updated.push(record);
    }
  }
  await writeMedia(updated);
  const expected = await expectedFiles(updated);
  for (const file of await publicFiles()) {
    if (!expected.has(file)) {
      await fs.rm(path.join(PUBLIC, file));
      console.log(`  eliminada versión huérfana ${file}`);
    }
  }
  console.log(`${updated.length - failures.length} de ${updated.length} imágenes sincronizadas`);
  if (failures.length)
    throw new Error(`${failures.length} con incidencias:\n- ${failures.join('\n- ')}`);
}

async function verificar() {
  const records = await readMedia();
  const expected = await expectedFiles(records);
  const issues = [];
  let total = 0;
  const bySection = {};
  for (const r of records) {
    const original = path.join(DIRS.originals, r.original.file);
    if (!(await exists(original))) issues.push(`${r.id}: falta el original (media-src/originales)`);
    for (const w of r.widths) {
      for (const f of FORMATS) {
        const rel = path.join(sectionOf(r), fileName(r.id, versionHash(r.original.sha256), w, f));
        const file = path.join(PUBLIC, rel);
        if (!(await exists(file))) {
          issues.push(`${r.id}: falta ${rel}`);
          continue;
        }
        const { size } = await fs.stat(file);
        total += size;
        bySection[sectionOf(r)] = (bySection[sectionOf(r)] ?? 0) + size;
        const check = await checkPublic(file);
        if (!check.ok)
          issues.push(
            `${rel}: metadatos no permitidos (${[...check.gps, ...check.privateGroups].join(', ')})`,
          );
      }
    }
  }
  for (const file of await publicFiles()) if (!expected.has(file)) issues.push(`huérfano: ${file}`);
  console.log(`${records.length} imágenes · ${expected.size} versiones · ${kb(total)} en total`);
  for (const [section, bytes] of Object.entries(bySection))
    console.log(`  ${section}: ${kb(bytes)}`);
  if (issues.length) {
    console.error(`✘ ${issues.length} incidencias:\n- ${issues.join('\n- ')}`);
    process.exit(1);
  }
  console.log('✔ Todas las versiones existen y ninguna contiene GPS, EXIF ni XMP.');
}

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    id: { type: 'string' },
    seccion: { type: 'string' },
    reemplazar: { type: 'boolean', default: false },
    raw: { type: 'string' },
    forzar: { type: 'boolean', default: false },
  },
});
const [command, arg] = positionals;
try {
  if (command === 'externa') await externa(arg, values.id, values.seccion);
  else if (command === 'propia')
    await propia(arg, values.id, values.seccion, values.reemplazar, values.raw);
  else if (command === 'sincronizar') await sincronizar(values.forzar);
  else if (command === 'verificar') await verificar();
  else
    throw new Error(
      'Uso: media.mjs externa|propia|sincronizar|verificar (ver cabecera del archivo)',
    );
} catch (error) {
  console.error(`✘ ${error.message}`);
  process.exit(1);
}
