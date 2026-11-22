import type { MediaRecord } from '../records/records';

/** Imagen tal como se publica para el navegador: el contrato sin los datos del original. */
export type PublicMediaRecord = Omit<MediaRecord, 'original'>;

/**
 * Proyección pública de media.json (fase 6.3.2, M3′). La instantánea conserva `original`
 * (contrato interno exacto, que validateContent() exige); el navegador nunca lo usa
 * (catalog.toMedia lo descarta), así que lo que se sirve no lo lleva: ni el archivo del almacén
 * privado, ni su hash, ni el DNG, ni la URL de la fuente.
 */
export function projectMedia(media: readonly MediaRecord[]): PublicMediaRecord[] {
  return media.map((record) => {
    const copy: Partial<MediaRecord> = { ...record };
    delete copy.original;
    return copy as PublicMediaRecord;
  });
}

/**
 * Valores privados de los originales: lo que nunca debe aparecer en nada que reciba el navegador
 * (bundles, respuestas HTTP, HTML, TransferState ni cachés). Lo usan las comprobaciones de fugas.
 */
export function privateValues(media: readonly MediaRecord[]): string[] {
  const values = new Set<string>();
  for (const { original } of media) {
    values.add(original.file);
    values.add(original.sha256);
    if (original.url) values.add(original.url);
    if (original.raw) {
      values.add(original.raw.file);
      values.add(original.raw.sha256);
    }
  }
  return [...values];
}
