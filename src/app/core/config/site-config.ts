import { InjectionToken, TransferState, inject, makeStateKey } from '@angular/core';

export const SITE_NAME = 'Cristian Atienza';
export const SITE_LOCALE = 'es_ES';

const PUBLIC_SITE_URL_KEY = makeStateKey<string | null>('PUBLIC_SITE_URL');

/**
 * Dominio público de la web (por ejemplo https://mi-dominio.example), sin barra final.
 * Se configura con la variable de entorno PUBLIC_SITE_URL al hacer el build (prerender)
 * y al arrancar el servidor SSR. Sin valor válido es null: no se generan URL canónicas
 * ni og:url, nunca con un dominio inventado.
 *
 * En el navegador llega desde el servidor mediante TransferState.
 */
export const PUBLIC_SITE_URL = new InjectionToken<string | null>('PUBLIC_SITE_URL', {
  providedIn: 'root',
  factory: () => inject(TransferState).get(PUBLIC_SITE_URL_KEY, null),
});

/** Proveedor para el servidor: lee la variable de entorno y la transfiere al navegador. */
export function providePublicSiteUrl(value: string | undefined) {
  return {
    provide: PUBLIC_SITE_URL,
    useFactory: () => {
      const url = normalizeSiteUrl(value);
      inject(TransferState).set(PUBLIC_SITE_URL_KEY, url);
      return url;
    },
  };
}

/** Acepta solo URL http(s) absolutas y elimina la barra final. */
export function normalizeSiteUrl(value: string | undefined | null): string | null {
  if (!value) return null;
  try {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
    return url.origin + url.pathname.replace(/\/+$/, '');
  } catch {
    return null;
  }
}
