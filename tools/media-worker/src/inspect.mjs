import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { promisify } from 'node:util';

export const run = promisify(execFile);

/** Todos los metadatos de un archivo según exiftool (grupos incluidos, valores numéricos). */
export async function exif(file) {
  try {
    const { stdout } = await run('exiftool', ['-json', '-G1', '-a', '-n', '-struct', file], {
      maxBuffer: 64 * 1024 * 1024,
    });
    return JSON.parse(stdout)[0] ?? {};
  } catch (error) {
    // exiftool termina con código 1 si el archivo no se puede leer; devuelve igualmente JSON.
    const out = error.stdout ? JSON.parse(error.stdout)[0] : {};
    return { ...out, _error: String(error.stderr || error.message).trim() };
  }
}

/** Etiquetas de geolocalización presentes. */
export function gpsTags(tags) {
  return Object.keys(tags).filter((key) => /gps/i.test(key));
}

/** Coordenadas del original (solo para el manifiesto privado). */
export function gpsPoint(tags) {
  const lat = tags['Composite:GPSLatitude'] ?? tags['GPS:GPSLatitude'];
  const lng = tags['Composite:GPSLongitude'] ?? tags['GPS:GPSLongitude'];
  return typeof lat === 'number' && typeof lng === 'number' ? { lat, lng } : null;
}

/** Grupos de metadatos presentes, sin los que describen el propio archivo. */
export function metadataGroups(tags) {
  const ignore = new Set(['SourceFile', 'ExifTool', 'System', 'File', 'Composite']);
  return [...new Set(Object.keys(tags).map((key) => key.split(':')[0]))].filter(
    (group) => !ignore.has(group),
  );
}

/** Dos decimales: los valores EXIF racionales llegan con ruido (6.764999866). */
const round = (value) => (typeof value === 'number' ? Math.round(value * 100) / 100 : value);

/** Datos de la toma que pueden publicarse (sin información personal). */
export function publicCamera(tags) {
  const pick = (...keys) => keys.map((k) => tags[k]).find((v) => v !== undefined && v !== '');
  const exposure = pick('ExifIFD:ExposureTime');
  const camera = {
    make: pick('IFD0:Make'),
    model: pick('IFD0:Model'),
    lens: pick('ExifIFD:LensModel'),
    focalLength: round(pick('ExifIFD:FocalLength')),
    aperture: round(pick('ExifIFD:FNumber')),
    exposure:
      typeof exposure === 'number'
        ? exposure >= 1
          ? String(exposure)
          : `1/${Math.round(1 / exposure)}`
        : undefined,
    iso: pick('ExifIFD:ISO'),
  };
  return Object.fromEntries(Object.entries(camera).filter(([, v]) => v !== undefined));
}

/** Fecha de la toma (AAAA-MM-DD), sin hora. */
export function takenAt(tags) {
  const value = tags['ExifIFD:DateTimeOriginal'] ?? tags['ExifIFD:CreateDate'];
  const match = typeof value === 'string' && value.match(/^(\d{4}):(\d{2}):(\d{2})/);
  return match ? `${match[1]}-${match[2]}-${match[3]}` : undefined;
}

/** Descripción del perfil de color embebido. */
export function iccDescription(tags) {
  return tags['ICC_Profile:ProfileDescription'] ?? null;
}

/** Indicios de mapa de ganancia HDR (Apple o ISO 21496-1). */
export async function hdrGainMap(file, tags) {
  const fromTags = Object.keys(tags).some((key) => /gainmap|hdrgain/i.test(key));
  let fromHeif = false;
  try {
    const { stdout } = await run('heif-info', [file], { maxBuffer: 8 * 1024 * 1024 });
    fromHeif = /hdrgainmap|21496|tmap|gain map/i.test(stdout);
  } catch {
    // No es un archivo HEIF.
  }
  return fromTags || fromHeif;
}

export function sha256(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256');
    createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', reject);
  });
}
