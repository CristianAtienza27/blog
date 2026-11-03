/**
 * Wikimedia Commons: metadatos de autoría y licencia, y descarga verificada del original.
 */
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import { USER_AGENT } from './config.mjs';

const API = 'https://commons.wikimedia.org/w/api.php';

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Espera máxima aceptable antes de un reintento; si Commons pide más, se abandona. */
const MAX_WAIT_MS = 120_000;
const ATTEMPTS = 2;

/**
 * Petición respetuosa con los límites de Wikimedia: ante un 429 o 503 reintenta una sola
 * vez si la espera pedida es corta. Si Commons pide esperar más de 2 minutos, se abandona
 * con un error claro: nunca se deja un proceso esperando ni se insiste.
 */
async function politeFetch(url) {
  for (let attempt = 1; ; attempt++) {
    const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (response.ok || ![429, 503].includes(response.status)) return response;
    const retryAfter = Number(response.headers.get('retry-after'));
    const delay = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 30_000;
    if (attempt >= ATTEMPTS || delay > MAX_WAIT_MS) {
      throw new Error(
        `Commons limita las descargas (${response.status}); pide esperar ${Math.round(delay / 1000)} s. Queda pendiente.`,
      );
    }
    console.log(`  Commons pide esperar ${Math.round(delay / 1000)} s; un único reintento`);
    await wait(delay);
  }
}

/** URL del archivo sin parámetros de seguimiento. */
const cleanUrl = (url) => url.split('?')[0];

/** Licencias de Commons → licencias admitidas por el modelo de contenido. */
const LICENSES = [
  [/^cc0/i, 'CC0-1.0'],
  [/^public domain|^pd\b|^pdm/i, 'PDM-1.0'],
  [/^cc by-sa 4\.0/i, 'CC-BY-SA-4.0'],
  [/^cc by-sa 3\.0/i, 'CC-BY-SA-3.0'],
  [/^cc by-sa 2\.\d/i, 'CC-BY-SA-2.0'],
  [/^cc by 4\.0/i, 'CC-BY-4.0'],
  [/^cc by 3\.0/i, 'CC-BY-3.0'],
  [/^cc by 2\.\d/i, 'CC-BY-2.0'],
];

const strip = (html = '') =>
  html
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();

/** Autoría legible: «This Photo was taken by Wolfgang Moroder. Feel free…» → «Wolfgang Moroder». */
function author(artist) {
  const text = strip(artist);
  const taken = text.match(/taken by ([^.]+)/i);
  return (taken ? taken[1] : text).replace(/\s*\(.*?\)\s*$/, '').trim();
}

/** Título del archivo a partir de la URL de su página en Commons. */
export function fileTitle(pageUrl) {
  const match = decodeURIComponent(new URL(pageUrl).pathname).match(/\/wiki\/(File:.+)$/);
  if (!match) throw new Error(`No es una página de archivo de Commons: ${pageUrl}`);
  return match[1].replace(/_/g, ' ');
}

/** Metadatos de una imagen de Commons. Falla si la licencia no está admitida. */
export async function commonsInfo(pageUrl) {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    titles: fileTitle(pageUrl),
    prop: 'imageinfo',
    iiprop: 'url|size|sha1|mime|extmetadata',
  });
  const response = await politeFetch(`${API}?${params}`);
  if (!response.ok) throw new Error(`Commons respondió ${response.status}`);
  const page = Object.values((await response.json()).query.pages)[0];
  const info = page?.imageinfo?.[0];
  if (!info) throw new Error(`Archivo no encontrado en Commons: ${pageUrl}`);
  const meta = info.extmetadata ?? {};
  const shortName = meta.LicenseShortName?.value ?? '';
  const license = LICENSES.find(([pattern]) => pattern.test(shortName))?.[1];
  if (!license) throw new Error(`Licencia no admitida («${shortName}») en ${pageUrl}`);
  if (meta.Restrictions?.value) {
    throw new Error(`La imagen tiene restricciones adicionales (${meta.Restrictions.value})`);
  }
  return {
    title: page.title,
    sourceUrl: cleanUrl(info.descriptionurl),
    fileUrl: cleanUrl(info.url),
    width: info.width,
    height: info.height,
    mime: info.mime,
    bytes: info.size,
    sha1: info.sha1,
    credit: {
      author: author(meta.Artist?.value),
      source: 'wikimedia',
      sourceUrl: cleanUrl(info.descriptionurl),
      license,
      ...(meta.LicenseUrl?.value
        ? { licenseUrl: meta.LicenseUrl.value.replace(/^http:/, 'https:') }
        : {}),
    },
  };
}

/** Descarga el original y verifica su SHA-1 contra el publicado por Commons. */
export async function download(info, dest) {
  const response = await politeFetch(info.fileUrl);
  if (!response.ok) throw new Error(`Descarga fallida (${response.status}): ${info.fileUrl}`);
  const buffer = Buffer.from(await response.arrayBuffer());
  const sha1 = createHash('sha1').update(buffer).digest('hex');
  if (sha1 !== info.sha1) throw new Error(`SHA-1 distinto del publicado en Commons: ${info.title}`);
  await fs.writeFile(dest, buffer, { flag: 'wx' });
  return buffer.length;
}

/** SHA-1 de un archivo local (para comprobar originales descargados a mano). */
export async function sha1File(file) {
  return createHash('sha1')
    .update(await fs.readFile(file))
    .digest('hex');
}
