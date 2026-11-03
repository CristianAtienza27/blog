import type { Gallery, Media } from '../models';

export abstract class MediaRepository {
  abstract media(id: string): Promise<Media | null>;
  /** Varias imágenes en el orden pedido; omite las que no existen. */
  abstract mediaList(ids: readonly string[]): Promise<Media[]>;
  abstract galleries(): Promise<Gallery[]>;
  abstract gallery(slug: string): Promise<Gallery | null>;
}
