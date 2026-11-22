import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { ArticleBodyRecord, ContentCollections } from '../records/records';
import { COLLECTION_NAMES } from '../records/records';

/**
 * Solo para los tests (fase 6.3.2): escribe una instantánea con el mismo formato que el generador
 * de la API (manifiesto, hashes y contentSha256), para probar el almacén sin depender de ella.
 */
const sha256 = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

/**
 * Originales con valores inconfundibles (en los datos de prueba, el nombre del archivo coincide con
 * parte de la URL pública de la obra): así, encontrar uno fuera es siempre una fuga.
 */
export function withPrivateOriginals(collections: ContentCollections): ContentCollections {
  return {
    ...collections,
    media: collections.media.map((m, i) => ({
      ...m,
      original: {
        ...m.original,
        file: `privado-${i}-${m.id}.jpg`,
        sha256: createHash('sha256').update(`privado-${m.id}`).digest('hex'),
        url: m.original.url ? `https://upload.wikimedia.org/privado/${m.id}.jpg` : undefined,
      },
    })),
  };
}

export function tempRoot(): string {
  return mkdtempSync(join(tmpdir(), 'instantaneas-'));
}

export function writeSnapshot(
  root: string,
  version: string,
  collections: ContentCollections,
  bodies: readonly ArticleBodyRecord[],
): string {
  const dir = join(root, version);
  mkdirSync(join(dir, 'articles'), { recursive: true });
  const files = new Map<string, Buffer>();
  for (const name of COLLECTION_NAMES) {
    files.set(`${name}.json`, Buffer.from(`${JSON.stringify(collections[name], null, 2)}\n`));
  }
  for (const body of bodies) {
    files.set(`articles/${body.slug}.json`, Buffer.from(`${JSON.stringify(body, null, 2)}\n`));
  }
  const entries = [...files.keys()].sort().map((path) => {
    const bytes = files.get(path) as Buffer;
    writeFileSync(join(dir, ...path.split('/')), bytes);
    return { path, bytes: bytes.length, sha256: sha256(bytes) };
  });
  const lines = entries.map((e) => `${e.path} ${e.sha256}\n`).join('');
  const manifest = {
    schemaVersion: 1,
    version,
    createdAt: '2026-11-19T10:00:00Z',
    generator: 'cms-api/snapshot-1',
    contentSha256: sha256(Buffer.from(lines)),
    articles: bodies.length,
    files: entries,
  };
  writeFileSync(join(dir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  return dir;
}
