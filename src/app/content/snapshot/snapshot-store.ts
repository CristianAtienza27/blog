import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, open, readdir, realpath } from 'node:fs/promises';
import { join, relative, sep } from 'node:path';
import type { ContentSource } from '../json/content-source';
import {
  COLLECTION_NAMES,
  type ArticleBodyRecord,
  type CollectionName,
  type ContentCollections,
  type MediaRecord,
} from '../records/records';
import { SLUG } from '../records/validate';
import { projectMedia } from './public-projection';

/**
 * Almacén de una instantánea en el servidor (fase 6.3.2). Solo para Node: lo importan el
 * servidor Express y los proveedores del SSR del build de instantáneas, nunca el navegador.
 *
 * Antes de declararse lista, una versión se comprueba ENTERA, archivo a archivo y sin guardarlos
 * todos: el formato del manifiesto, que estén exactamente los archivos declarados (sin enlaces ni
 * nada más), el tamaño y el SHA-256 de cada uno y la huella del contenido. Así, una versión con un
 * artículo alterado no arranca aunque ese artículo nunca se haya pedido. Después, cada cuerpo se
 * vuelve a comprobar al leerlo. La puerta autoritativa, validateContent(), está en activation.ts.
 *
 * Lectura sin seguir enlaces (6.3.3, H1): cada archivo se abre con O_NOFOLLOW y se comprueba con
 * fstat() sobre el descriptor abierto que es un archivo normal y que es el MISMO (dispositivo e
 * inodo) que se comprobó al cargar. Ver readVerified() para las garantías de cada sistema.
 */

export const SNAPSHOT_SCHEMA_VERSION = 1;
export const VERSION = /^v[1-9][0-9]{0,8}$/;
const MANIFEST = 'manifest.json';
const ARTICLES = 'articles';
/** Cuerpos analizados que se guardan; el resto se lee del disco (y se comprueba) al pedirlo. */
const BODY_CACHE_SIZE = 64;

export class SnapshotIntegrityError extends Error {
  constructor(readonly issues: readonly string[]) {
    super(`Instantánea no válida:\n- ${issues.join('\n- ')}`);
    this.name = 'SnapshotIntegrityError';
  }
}

interface ManifestFile {
  path: string;
  bytes: number;
  sha256: string;
}

/** Identidad de un archivo comprobado al cargar: dispositivo e inodo (BigInt, exactos). */
interface FileIdentity {
  dev: bigint;
  ino: bigint;
}

export interface SnapshotManifest {
  schemaVersion: number;
  version: string;
  createdAt: string;
  contentSha256: string;
  articles: number;
  files: ManifestFile[];
}

/** Una versión cargada y comprobada. Inmutable: cada petición fija la suya al empezar. */
export interface LoadedSnapshot {
  readonly version: string;
  readonly contentSha256: string;
  /** Slugs de los artículos (los cuerpos), para la puerta. */
  readonly slugs: readonly string[];
  /** Fuente del SSR: el contrato interno completo (con `original`). */
  readonly source: ContentSource;
  /** Lo que recibe el navegador: la proyección pública, ya serializada. */
  publicCollection(name: string): Buffer | null;
  publicBody(slug: string): Promise<Buffer | null>;
}

const sha256 = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

const isCollection = (name: string): name is CollectionName =>
  (COLLECTION_NAMES as readonly string[]).includes(name);

/** Ruta relativa permitida: una colección del contrato o el cuerpo de un artículo con slug válido. */
function allowedPath(path: string): boolean {
  if (path.startsWith(`${ARTICLES}/`)) {
    const file = path.slice(ARTICLES.length + 1);
    return file.endsWith('.json') && SLUG.test(file.slice(0, -'.json'.length));
  }
  return path.endsWith('.json') && isCollection(path.slice(0, -'.json'.length));
}

/** Carga y comprueba la versión `version` de la carpeta `root`. Lanza SnapshotIntegrityError. */
export async function loadSnapshot(root: string, version: string): Promise<LoadedSnapshot> {
  if (!VERSION.test(version)) throw new SnapshotIntegrityError(['versión no válida']);
  const rootReal = await realpath(root);
  const dir = join(rootReal, version);
  const issues: string[] = [];

  const dirStat = await lstat(dir).catch(() => null);
  if (dirStat?.isSymbolicLink()) throw new SnapshotIntegrityError(['la versión es un enlace']);
  if (!dirStat?.isDirectory())
    throw new SnapshotIntegrityError([`la versión ${version} no existe`]);
  if ((await realpath(dir)) !== dir) throw new SnapshotIntegrityError(['la versión es un enlace']);

  const manifest = await readManifest(dir, version);

  // Solo los archivos declarados, sin enlaces ni otros tipos.
  const declared = new Map<string, ManifestFile>();
  for (const file of manifest.files) {
    if (typeof file?.path !== 'string' || !allowedPath(file.path)) {
      issues.push('files: ruta no permitida');
    } else if (declared.has(file.path)) {
      issues.push(`files: ruta repetida («${file.path}»)`);
    } else {
      declared.set(file.path, file);
    }
  }
  const present = await listFiles(dir, issues);
  for (const path of present) {
    if (!declared.has(path)) issues.push(`archivo no declarado en el manifiesto: «${path}»`);
  }
  for (const path of declared.keys()) {
    if (!present.includes(path)) issues.push(`falta el archivo «${path}»`);
  }
  for (const name of COLLECTION_NAMES) {
    if (!declared.has(`${name}.json`)) issues.push(`falta la colección «${name}»`);
  }
  if (issues.length) throw new SnapshotIntegrityError(issues);

  // Tamaño y hash de TODOS los archivos, uno a uno. Las colecciones se guardan; los cuerpos, no.
  const collections: Partial<Record<CollectionName, unknown>> = {};
  const identities = new Map<string, FileIdentity>();
  const lines: string[] = [];
  for (const path of [...declared.keys()].sort()) {
    const entry = declared.get(path) as ManifestFile;
    const { bytes, identity } = await readVerified(join(dir, ...path.split('/')), path);
    identities.set(path, identity);
    const hash = sha256(bytes);
    if (bytes.length !== entry.bytes) issues.push(`tamaño distinto del manifiesto: «${path}»`);
    if (hash !== entry.sha256) issues.push(`hash distinto del manifiesto: «${path}»`);
    lines.push(`${path} ${hash}\n`);
    const name = path.slice(0, -'.json'.length);
    if (isCollection(name)) collections[name] = parseJson(bytes, path, issues);
  }
  if (sha256(Buffer.from(lines.join(''), 'utf8')) !== manifest.contentSha256) {
    issues.push('contentSha256 no coincide con los archivos');
  }
  const bodies = new Map(
    [...declared.values()]
      .filter((f) => f.path.startsWith(`${ARTICLES}/`))
      .map((f) => [f.path.slice(ARTICLES.length + 1, -'.json'.length), f] as const),
  );
  if (manifest.articles !== bodies.size) issues.push('articles no coincide con los cuerpos');
  const articles = collections.articles;
  if (Array.isArray(articles)) {
    const slugs = new Set(articles.map((a: { slug?: unknown }) => a.slug));
    for (const slug of slugs)
      if (!bodies.has(slug as string)) issues.push(`falta el cuerpo de «${String(slug)}»`);
    for (const slug of bodies.keys())
      if (!slugs.has(slug)) issues.push(`cuerpo sin artículo: «${slug}»`);
  } else {
    issues.push('articles: se esperaba una lista');
  }
  for (const name of COLLECTION_NAMES) {
    if (!Array.isArray(collections[name])) issues.push(`${name}: se esperaba una lista`);
  }
  if (issues.length) throw new SnapshotIntegrityError(issues);

  return createLoaded(dir, manifest, collections as ContentCollections, bodies, identities);
}

async function readManifest(dir: string, version: string): Promise<SnapshotManifest> {
  // Como cualquier otro archivo: sin seguir enlaces. Si no es un archivo normal, no hay manifiesto.
  const read = await readVerified(join(dir, MANIFEST), MANIFEST).catch((error: unknown) => {
    const code = (error as NodeJS.ErrnoException).code;
    if (error instanceof SnapshotIntegrityError || code === 'ENOENT' || code === 'EISDIR') {
      throw new SnapshotIntegrityError([`falta ${MANIFEST}`]);
    }
    throw error;
  });
  const issues: string[] = [];
  const manifest = parseJson(read.bytes, MANIFEST, issues) as Partial<SnapshotManifest> | null;
  if (!manifest || typeof manifest !== 'object') throw new SnapshotIntegrityError(issues);
  if (manifest.schemaVersion !== SNAPSHOT_SCHEMA_VERSION) {
    throw new SnapshotIntegrityError([
      `schemaVersion desconocida (se esperaba ${SNAPSHOT_SCHEMA_VERSION})`,
    ]);
  }
  if (manifest.version !== version)
    issues.push('la versión del manifiesto no coincide con la carpeta');
  if (typeof manifest.contentSha256 !== 'string') issues.push('falta contentSha256');
  if (!Array.isArray(manifest.files) || manifest.files.length === 0) {
    issues.push('files: se esperaba una lista no vacía');
  }
  if (issues.length) throw new SnapshotIntegrityError(issues);
  return manifest as SnapshotManifest;
}

/**
 * Lee un archivo de la versión sin seguir enlaces y devuelve su identidad.
 *
 * - Linux (y macOS): open() con O_NOFOLLOW falla (ELOOP) si el archivo es un enlace, así que el
 *   destino nunca se abre; después, fstat() SOBRE EL DESCRIPTOR ABIERTO comprueba que es un archivo
 *   normal. No hay intervalo entre comprobar y abrir. O_NOFOLLOW solo protege el último componente:
 *   si se sustituye la carpeta articles/ por un enlace, el archivo de destino sí se abre, pero su
 *   inodo no coincide con el comprobado al cargar y se rechaza sin servir nada.
 * - Windows: Node no ofrece O_NOFOLLOW. Se comprueba con lstat() y se abre después: queda un
 *   intervalo (TOCTOU) en el que el archivo podría cambiarse por un enlace y el destino llegaría a
 *   abrirse. NO es una protección equivalente; la identidad (fstat) sigue impidiendo servirlo. El
 *   entorno real (6.3.5 y 6.9) es Linux.
 */
async function readVerified(
  file: string,
  path: string,
  expected?: FileIdentity,
): Promise<{ bytes: Buffer; identity: FileIdentity }> {
  const noFollow = constants.O_NOFOLLOW as number | undefined;
  if (noFollow === undefined) {
    const stat = await lstat(file);
    if (stat.isSymbolicLink()) throw new SnapshotIntegrityError([`enlace no permitido: «${path}»`]);
  }
  const handle = await open(file, constants.O_RDONLY | (noFollow ?? 0)).catch((error: unknown) => {
    if ((error as NodeJS.ErrnoException).code === 'ELOOP') {
      throw new SnapshotIntegrityError([`enlace no permitido: «${path}»`]);
    }
    throw error;
  });
  try {
    const stat = await handle.stat({ bigint: true });
    if (!stat.isFile()) throw new SnapshotIntegrityError([`no es un archivo normal: «${path}»`]);
    const identity = { dev: stat.dev, ino: stat.ino };
    if (expected && (expected.dev !== identity.dev || expected.ino !== identity.ino)) {
      throw new SnapshotIntegrityError([`el archivo ha cambiado desde la carga: «${path}»`]);
    }
    return { bytes: await handle.readFile(), identity };
  } finally {
    await handle.close();
  }
}

/** Archivos de la versión (rutas relativas con «/»), sin seguir enlaces. */
async function listFiles(dir: string, issues: string[]): Promise<string[]> {
  const out: string[] = [];
  const walk = async (current: string) => {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      const rel = relative(dir, full).split(sep).join('/');
      if (entry.isSymbolicLink()) {
        issues.push(`enlace no permitido: «${rel}»`);
      } else if (entry.isDirectory()) {
        if (rel === ARTICLES) await walk(full);
        else issues.push(`carpeta no permitida: «${rel}»`);
      } else if (!entry.isFile()) {
        issues.push(`no es un archivo normal: «${rel}»`);
      } else if (rel !== MANIFEST) {
        out.push(rel);
      }
    }
  };
  await walk(dir);
  return out;
}

function parseJson(bytes: Buffer, path: string, issues: string[]): unknown {
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch {
    issues.push(`${path}: JSON no válido`);
    return null;
  }
}

function createLoaded(
  dir: string,
  manifest: SnapshotManifest,
  collections: ContentCollections,
  bodies: ReadonlyMap<string, ManifestFile>,
  identities: ReadonlyMap<string, FileIdentity>,
): LoadedSnapshot {
  const publicCollections = new Map<string, Buffer>(
    COLLECTION_NAMES.map((name) => {
      const value =
        name === 'media' ? projectMedia(collections.media as MediaRecord[]) : collections[name];
      return [name, Buffer.from(JSON.stringify(value), 'utf8')];
    }),
  );
  const parsed = new Map<string, ArticleBodyRecord>();

  /** Lee un cuerpo y comprueba de nuevo su hash: el disco pudo cambiar después de cargar. */
  const readBody = async (slug: string): Promise<Buffer | null> => {
    const entry = SLUG.test(slug) ? bodies.get(slug) : undefined;
    if (!entry) return null;
    const { bytes } = await readVerified(
      join(dir, ARTICLES, `${slug}.json`),
      entry.path,
      identities.get(entry.path),
    );
    if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256) {
      throw new SnapshotIntegrityError([`hash distinto del manifiesto: «${entry.path}»`]);
    }
    return bytes;
  };

  const source: ContentSource = {
    async collection(name) {
      return collections[name];
    },
    async body(slug) {
      const cached = parsed.get(slug);
      if (cached) return cached;
      const bytes = await readBody(slug);
      if (!bytes) return null;
      const body = JSON.parse(bytes.toString('utf8')) as ArticleBodyRecord;
      if (parsed.size >= BODY_CACHE_SIZE) parsed.delete(parsed.keys().next().value as string);
      parsed.set(slug, body);
      return body;
    },
  };

  return {
    version: manifest.version,
    contentSha256: manifest.contentSha256,
    slugs: [...bodies.keys()],
    source,
    publicCollection: (name) => publicCollections.get(name) ?? null,
    publicBody: readBody,
  };
}

// --- Versiones del proceso -------------------------------------------------------------------

/**
 * Las versiones que sirve este proceso: la activa y la anterior (Q1), para que un navegador que
 * hidrató con la anterior pueda terminar de navegar. En `globalThis` para que el servidor Express
 * y la aplicación (entradas distintas del mismo build) compartan las mismas aunque el empaquetado
 * duplique el módulo. Solo las cambia la activación (activation.ts).
 */
const SERVED = Symbol.for('cristian-atienza.content-snapshots');
interface Served {
  active?: LoadedSnapshot;
  previous?: LoadedSnapshot;
}
interface Holder {
  [SERVED]?: Served;
}
const served = (): Served => ((globalThis as Holder)[SERVED] ??= {});

/** Fija la versión activa y la anterior de una vez: nunca se ve un cambio a medias. */
export function setServedSnapshots(
  active: LoadedSnapshot | undefined,
  previous?: LoadedSnapshot,
): void {
  (globalThis as Holder)[SERVED] = {
    active,
    previous: previous && previous.version !== active?.version ? previous : undefined,
  };
}

/** Activa una versión: la que estaba activa pasa a ser la anterior. */
export function setCurrentSnapshot(snapshot: LoadedSnapshot): void {
  setServedSnapshots(snapshot, served().active);
}

/** La versión activa, o undefined si no hay ninguna (volumen vacío, o durante el build). */
export function peekCurrentSnapshot(): LoadedSnapshot | undefined {
  return served().active;
}

/** La anterior, si se sirve. */
export function peekPreviousSnapshot(): LoadedSnapshot | undefined {
  return served().previous;
}

/** Una versión que se sirve (la activa o la anterior), o undefined. */
export function servedSnapshot(version: string): LoadedSnapshot | undefined {
  const { active, previous } = served();
  if (active?.version === version) return active;
  if (previous?.version === version) return previous;
  return undefined;
}

export function currentSnapshot(): LoadedSnapshot {
  const snapshot = peekCurrentSnapshot();
  if (!snapshot) throw new Error('No hay ninguna versión activa');
  return snapshot;
}
