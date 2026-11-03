import {
  ApplicationRef,
  Injectable,
  PLATFORM_ID,
  TransferState,
  inject,
  makeStateKey,
} from '@angular/core';
import { isPlatformServer } from '@angular/common';

/** Clave estable para una consulta: el orden de las propiedades no importa. */
export function cacheKey(name: string, params?: object): string {
  if (!params) return name;
  const entries = Object.entries(params)
    .filter(([, value]) => value !== undefined)
    .sort(([a], [b]) => a.localeCompare(b));
  return entries.length ? `${name}:${JSON.stringify(entries)}` : name;
}

/**
 * Transfiere al navegador lo que el servidor ya consultó (TransferState).
 * Durante la hidratación, el navegador reutiliza esos resultados sin volver a cargarlos,
 * así que la página no parpadea. Después, las consultas se resuelven con normalidad.
 *
 * El adaptador HTTP no lo necesitará: HttpClient ya tiene su propia caché de transferencia.
 */
@Injectable({ providedIn: 'root' })
export class ContentCache {
  private readonly state = inject(TransferState);
  private readonly server = isPlatformServer(inject(PLATFORM_ID));
  private hydrating = !this.server;

  constructor() {
    if (this.hydrating) {
      void inject(ApplicationRef)
        .whenStable()
        .then(() => (this.hydrating = false));
    }
  }

  async get<T>(key: string, load: () => Promise<T>): Promise<T> {
    const stateKey = makeStateKey<T | null>(`content:${key}`);
    if (this.hydrating && this.state.hasKey(stateKey)) {
      return this.state.get(stateKey, null) as T;
    }
    const value = await load();
    // TransferState solo admite JSON: undefined se guarda como null.
    if (this.server) this.state.set(stateKey, value ?? null);
    return value;
  }
}
