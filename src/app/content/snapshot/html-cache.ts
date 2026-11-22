/**
 * Caché en memoria del HTML de la portada y los artículos (fase 6.3.3, Q3). La clave es la versión
 * que fijó la petición más la ruta: al activar otra versión, las páginas viejas dejan de pedirse
 * y salen solas por el LRU (no hay que purgar nada). Acotada en entradas y en bytes.
 *
 * Solo guarda lo que es igual para cualquiera: GET sin parámetros de consulta, estado 200 y HTML.
 * Guarda exactamente lo que se envió, así que no puede contener nada que no se hubiera servido ya.
 */
export interface CachedPage {
  status: number;
  headers: [string, string][];
  body: Buffer;
}

export class HtmlCache {
  private readonly pages = new Map<string, CachedPage>();
  private used = 0;

  constructor(
    readonly maxEntries = 500,
    readonly maxBytes = 64 * 1024 * 1024,
  ) {}

  static key(version: string, path: string): string {
    return `${version} ${path}`;
  }

  get(key: string): CachedPage | undefined {
    const page = this.pages.get(key);
    if (page) {
      // Reciente: al final del orden de inserción.
      this.pages.delete(key);
      this.pages.set(key, page);
    }
    return page;
  }

  set(key: string, page: CachedPage): void {
    if (page.body.length > this.maxBytes) return;
    this.delete(key);
    this.pages.set(key, page);
    this.used += page.body.length;
    while (this.pages.size > this.maxEntries || this.used > this.maxBytes) {
      this.delete(this.pages.keys().next().value as string);
    }
  }

  get size(): number {
    return this.pages.size;
  }

  get bytes(): number {
    return this.used;
  }

  entries(): IterableIterator<[string, CachedPage]> {
    return this.pages.entries();
  }

  private delete(key: string): void {
    const page = this.pages.get(key);
    if (!page) return;
    this.pages.delete(key);
    this.used -= page.body.length;
  }
}
