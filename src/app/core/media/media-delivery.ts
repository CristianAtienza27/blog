import {
  EnvironmentProviders,
  InjectionToken,
  TransferState,
  inject,
  makeEnvironmentProviders,
  makeStateKey,
} from '@angular/core';
import { IMAGE_CONFIG, IMAGE_LOADER, ImageLoaderConfig } from '@angular/common';

/** Anchos que genera el procesador multimedia (tools/media-worker/src/config.mjs). */
export const MEDIA_WIDTHS = [640, 1024, 1600, 2400] as const;

export type MediaFormat = 'avif' | 'webp';

const MEDIA_BASE_URL_KEY = makeStateKey<string>('MEDIA_BASE_URL');
const DEFAULT_MEDIA_BASE_URL = '/media';

/**
 * Origen de las versiones públicas: /media (public/media) en local o, en producción,
 * la URL de un contenedor de Azure Blob Storage o de su CDN. Se configura con la
 * variable de entorno MEDIA_BASE_URL y llega al navegador mediante TransferState.
 */
export const MEDIA_BASE_URL = new InjectionToken<string>('MEDIA_BASE_URL', {
  providedIn: 'root',
  factory: () => inject(TransferState).get(MEDIA_BASE_URL_KEY, DEFAULT_MEDIA_BASE_URL),
});

/** Acepta una ruta absoluta (/media) o una URL https; elimina la barra final. */
export function normalizeMediaBaseUrl(value: string | undefined | null): string {
  const trimmed = value?.trim();
  if (!trimmed) return DEFAULT_MEDIA_BASE_URL;
  if (/^\/[^/]/.test(trimmed)) return trimmed.replace(/\/+$/, '');
  try {
    const url = new URL(trimmed);
    if (url.protocol === 'https:') return (url.origin + url.pathname).replace(/\/+$/, '');
  } catch {
    // Valor no válido: se usa el origen local.
  }
  return DEFAULT_MEDIA_BASE_URL;
}

/** Proveedor para el servidor: lee la variable de entorno y la transfiere al navegador. */
export function provideMediaBaseUrl(value: string | undefined) {
  return {
    provide: MEDIA_BASE_URL,
    useFactory: () => {
      const url = normalizeMediaBaseUrl(value);
      inject(TransferState).set(MEDIA_BASE_URL_KEY, url);
      return url;
    },
  };
}

/** El ancho disponible más pequeño que cubre el pedido (o el mayor, si ninguno llega). */
export function pickWidth(widths: readonly number[], requested?: number): number {
  const sorted = [...widths].sort((a, b) => a - b);
  if (!requested) return sorted.at(-1) ?? 0;
  return sorted.find((w) => w >= requested) ?? sorted.at(-1) ?? 0;
}

/** URL de una versión pública: `${base}/${path}-${ancho}.${formato}`. */
export function mediaUrl(base: string, path: string, width: number, format: MediaFormat): string {
  return `${base}/${path}-${width}.${format}`;
}

/** srcset con descriptores de ancho, solo con los anchos generados. */
export function mediaSrcset(
  base: string,
  path: string,
  widths: readonly number[],
  format: MediaFormat,
): string {
  return widths.map((w) => `${mediaUrl(base, path, w, format)} ${w}w`).join(', ');
}

/**
 * Imágenes optimizadas: loader propio para NgOptimizedImage (WebP; el AVIF va en un
 * <source> del <picture>) y los mismos anchos que genera el procesador.
 */
export function provideMediaDelivery(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: IMAGE_LOADER,
      useFactory: () => {
        const base = inject(MEDIA_BASE_URL);
        return (config: ImageLoaderConfig) => {
          const widths = (config.loaderParams?.['widths'] as number[] | undefined) ?? [
            ...MEDIA_WIDTHS,
          ];
          return mediaUrl(base, config.src, pickWidth(widths, config.width), 'webp');
        };
      },
    },
    { provide: IMAGE_CONFIG, useValue: { breakpoints: [...MEDIA_WIDTHS] } },
  ]);
}
