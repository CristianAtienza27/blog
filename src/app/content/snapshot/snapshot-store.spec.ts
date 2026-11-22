import { cpSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { MediaRecord } from '../records/records';
import { FIXTURE_BODIES, fixtureCollections, ownMedia } from '../testing/fixtures';
import { privateValues } from './public-projection';
import {
  SnapshotIntegrityError,
  currentSnapshot,
  loadSnapshot,
  setCurrentSnapshot,
} from './snapshot-store';
import { tempRoot, withPrivateOriginals, writeSnapshot } from './snapshot-testing';

/** Colecciones de prueba con una foto propia que tiene DNG: todos los datos privados posibles. */
function collections() {
  const data = fixtureCollections();
  const own = ownMedia('foto-propia-dng', 'fotografia');
  own.original = {
    ...own.original,
    raw: { file: 'foto-propia-dng.dng', bytes: 25_000_000, sha256: 'ab'.repeat(32) },
  };
  data.media = [...data.media, own];
  return withPrivateOriginals(data);
}

async function rejection(root: string, version = 'v1'): Promise<string[]> {
  try {
    await loadSnapshot(root, version);
  } catch (error) {
    if (error instanceof SnapshotIntegrityError) return [...error.issues];
    throw error;
  }
  throw new Error('la instantánea se cargó');
}

/** Un archivo de la instantánea con un byte cambiado, sin tocar el manifiesto. */
function tamper(file: string) {
  const text = readFileSync(file, 'utf8');
  writeFileSync(file, text.replace('"', '" '));
}

function canSymlinkFiles(dir: string): boolean {
  try {
    writeFileSync(join(dir, 'destino.txt'), 'x');
    symlinkSync(join(dir, 'destino.txt'), join(dir, 'enlace.txt'), 'file');
    rmSync(join(dir, 'enlace.txt'));
    rmSync(join(dir, 'destino.txt'));
    return true;
  } catch {
    rmSync(join(dir, 'destino.txt'), { force: true });
    return false;
  }
}

describe('Almacén de instantáneas (servidor)', () => {
  let root: string;
  beforeEach(() => {
    root = tempRoot();
  });
  afterEach(() => rmSync(root, { recursive: true, force: true }));

  it('carga una versión correcta: el SSR recibe el contrato completo', async () => {
    const data = collections();
    writeSnapshot(root, 'v1', data, FIXTURE_BODIES);
    const snapshot = await loadSnapshot(root, 'v1');

    expect(snapshot.version).toBe('v1');
    expect(snapshot.contentSha256).toMatch(/^[0-9a-f]{64}$/);
    // El contrato interno exacto, con original: validateContent() lo necesita (6.3.3).
    expect(await snapshot.source.collection('media')).toEqual(data.media);
    expect(await snapshot.source.collection('articles')).toEqual(data.articles);
    const slug = FIXTURE_BODIES[0].slug;
    expect(await snapshot.source.body(slug)).toEqual(FIXTURE_BODIES[0]);
    expect(await snapshot.source.body('no-existe')).toBeNull();
    expect(await snapshot.source.body('../media')).toBeNull();
  });

  it('la proyección pública (lo que se guarda para el navegador) no lleva nada del original', async () => {
    const data = collections();
    writeSnapshot(root, 'v1', data, FIXTURE_BODIES);
    const snapshot = await loadSnapshot(root, 'v1');

    const media = snapshot.publicCollection('media')?.toString('utf8') ?? '';
    const parsed = JSON.parse(media) as Record<string, unknown>[];
    expect(parsed).toHaveLength(data.media.length);
    expect(parsed.every((m) => !('original' in m))).toBe(true);
    expect(media).not.toContain('"original"');
    expect(media).not.toMatch(/[0-9a-f]{64}/);
    const secrets = privateValues(data.media as MediaRecord[]);
    expect(secrets).toContain('foto-propia-dng.dng');
    const served = [
      ...[
        'categories',
        'tags',
        'authors',
        'media',
        'galleries',
        'destinations',
        'trips',
        'projects',
        'articles',
      ].map((name) => snapshot.publicCollection(name)?.toString('utf8') ?? ''),
      ...(await Promise.all(
        FIXTURE_BODIES.map(async (b) => (await snapshot.publicBody(b.slug))?.toString('utf8')),
      )),
    ].join('\n');
    for (const value of secrets) expect(served).not.toContain(value);
    // El resto, igual que en la instantánea.
    expect(JSON.parse(snapshot.publicCollection('tags')?.toString('utf8') ?? '')).toEqual(
      data.tags,
    );
    expect(snapshot.publicCollection('manifest')).toBeNull();
    expect(snapshot.publicCollection('../media')).toBeNull();
  });

  it('comprueba TODOS los archivos antes de estar lista: un artículo alterado que nadie ha pedido impide cargarla', async () => {
    const dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    const last = FIXTURE_BODIES[FIXTURE_BODIES.length - 1].slug;
    tamper(join(dir, 'articles', `${last}.json`));
    expect(await rejection(root)).toEqual(
      expect.arrayContaining([`hash distinto del manifiesto: «articles/${last}.json»`]),
    );
  });

  it('rechaza colecciones alteradas, archivos de más o de menos y carpetas ajenas', async () => {
    let dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    tamper(join(dir, 'media.json'));
    expect(await rejection(root)).toEqual(
      expect.arrayContaining(['hash distinto del manifiesto: «media.json»']),
    );

    dir = writeSnapshot(root, 'v2', collections(), FIXTURE_BODIES);
    writeFileSync(join(dir, 'secreto.json'), '[]');
    expect(await rejection(root, 'v2')).toContain(
      'archivo no declarado en el manifiesto: «secreto.json»',
    );

    dir = writeSnapshot(root, 'v3', collections(), FIXTURE_BODIES);
    rmSync(join(dir, 'tags.json'));
    expect(await rejection(root, 'v3')).toContain('falta el archivo «tags.json»');

    dir = writeSnapshot(root, 'v4', collections(), FIXTURE_BODIES);
    mkdirSync(join(dir, 'privado'));
    expect(await rejection(root, 'v4')).toContain('carpeta no permitida: «privado»');

    dir = writeSnapshot(root, 'v5', collections(), FIXTURE_BODIES);
    const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
    manifest.contentSha256 = '0'.repeat(64);
    writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest));
    expect(await rejection(root, 'v5')).toEqual(['contentSha256 no coincide con los archivos']);

    dir = writeSnapshot(root, 'v6', collections(), FIXTURE_BODIES);
    rmSync(join(dir, 'articles', `${FIXTURE_BODIES[0].slug}.json`));
    expect(await rejection(root, 'v6')).toContain(
      `falta el archivo «articles/${FIXTURE_BODIES[0].slug}.json»`,
    );
  });

  it('rechaza un manifiesto de otra versión o de un contrato desconocido', async () => {
    const dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'));
    writeFileSync(join(dir, 'manifest.json'), JSON.stringify({ ...manifest, version: 'v9' }));
    expect(await rejection(root)).toEqual(['la versión del manifiesto no coincide con la carpeta']);
    writeFileSync(join(dir, 'manifest.json'), JSON.stringify({ ...manifest, schemaVersion: 2 }));
    expect(await rejection(root)).toEqual(['schemaVersion desconocida (se esperaba 1)']);
    rmSync(join(dir, 'manifest.json'));
    expect(await rejection(root)).toEqual(['falta manifest.json']);
  });

  it('solo acepta nombres de versión válidos', async () => {
    writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    for (const version of [
      '',
      'v0',
      'v01',
      'V1',
      '../v1',
      'v1/..',
      'v1\\..',
      '%2e%2e',
      'v1\0',
      'manifest.json',
    ]) {
      expect(await rejection(root, version)).toEqual(['versión no válida']);
    }
    expect(await rejection(root, 'v2')).toEqual(['la versión v2 no existe']);
  });

  it('rechaza enlaces: la versión, una carpeta y un archivo dentro de ella', async () => {
    const real = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    // La versión es un enlace (junction en Windows, sin privilegios) a otra carpeta válida.
    symlinkSync(real, join(root, 'v2'), 'junction');
    expect(await rejection(root, 'v2')).toEqual(['la versión es un enlace']);

    // Una carpeta enlazada dentro de la versión.
    const dir = writeSnapshot(root, 'v3', collections(), FIXTURE_BODIES);
    symlinkSync(join(root, 'v1'), join(dir, 'fuera'), 'junction');
    expect(await rejection(root, 'v3')).toContain('enlace no permitido: «fuera»');

    // Un cuerpo sustituido por un enlace a un archivo de fuera (necesita permiso en Windows).
    if (canSymlinkFiles(root)) {
      const v4 = writeSnapshot(root, 'v4', collections(), FIXTURE_BODIES);
      const body = join(v4, 'articles', `${FIXTURE_BODIES[0].slug}.json`);
      rmSync(body);
      symlinkSync(join(real, 'articles', `${FIXTURE_BODIES[0].slug}.json`), body, 'file');
      expect(await rejection(root, 'v4')).toContain(
        `enlace no permitido: «articles/${FIXTURE_BODIES[0].slug}.json»`,
      );
    }
  });

  it('un cuerpo alterado después de cargar no se sirve', async () => {
    const dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    const snapshot = await loadSnapshot(root, 'v1');
    const slug = FIXTURE_BODIES[1].slug;
    tamper(join(dir, 'articles', `${slug}.json`));
    await expect(snapshot.publicBody(slug)).rejects.toThrow(SnapshotIntegrityError);
    await expect(snapshot.source.body(slug)).rejects.toThrow(SnapshotIntegrityError);
  });

  it('H1: un cuerpo cambiado por un enlace DESPUÉS de cargar no se abre ni se sirve', async () => {
    const outside = join(root, 'fuera');
    mkdirSync(outside);
    const dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    const snapshot = await loadSnapshot(root, 'v1');
    const slug = FIXTURE_BODIES[0].slug;
    const body = join(dir, 'articles', `${slug}.json`);
    // Una copia exacta fuera: con los mismos bytes, el hash coincidiría si se siguiera el enlace.
    writeFileSync(join(outside, 'copia.json'), readFileSync(body));

    if (canSymlinkFiles(root)) {
      rmSync(body);
      symlinkSync(join(outside, 'copia.json'), body, 'file');
      await expect(snapshot.publicBody(slug)).rejects.toThrow(SnapshotIntegrityError);
      await expect(snapshot.source.body(slug)).rejects.toThrow(SnapshotIntegrityError);
      rmSync(body);
    }

    // La carpeta articles/ entera cambiada por un enlace (junction) a una copia exacta: el
    // archivo abierto no es el que se comprobó al cargar (otro inodo) y no se sirve.
    const other = FIXTURE_BODIES[1].slug;
    cpSync(join(dir, 'articles'), join(outside, 'articles'), { recursive: true });
    rmSync(join(dir, 'articles'), { recursive: true });
    symlinkSync(join(outside, 'articles'), join(dir, 'articles'), 'junction');
    await expect(snapshot.publicBody(other)).rejects.toThrow('ha cambiado desde la carga');
  });

  it('un cuerpo sustituido por otro archivo con los mismos bytes después de cargar tampoco se sirve', async () => {
    const dir = writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    const snapshot = await loadSnapshot(root, 'v1');
    const slug = FIXTURE_BODIES[0].slug;
    const body = join(dir, 'articles', `${slug}.json`);
    const bytes = readFileSync(body);
    rmSync(body);
    writeFileSync(body, bytes); // mismo contenido, otro archivo (otro inodo)
    await expect(snapshot.publicBody(slug)).rejects.toThrow('ha cambiado desde la carga');
  });

  it('la versión del proceso: solo una cargada, y cada lectura devuelve la vigente', async () => {
    writeSnapshot(root, 'v1', collections(), FIXTURE_BODIES);
    writeSnapshot(root, 'v2', collections(), FIXTURE_BODIES);
    const v1 = await loadSnapshot(root, 'v1');
    const v2 = await loadSnapshot(root, 'v2');
    setCurrentSnapshot(v1);
    const pinned = currentSnapshot();
    setCurrentSnapshot(v2);
    expect(pinned.version).toBe('v1');
    expect(currentSnapshot().version).toBe('v2');
  });
});
