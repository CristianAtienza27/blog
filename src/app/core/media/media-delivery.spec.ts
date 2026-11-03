import { IMAGE_LOADER } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import {
  MEDIA_BASE_URL,
  mediaSrcset,
  mediaUrl,
  normalizeMediaBaseUrl,
  pickWidth,
  provideMediaBaseUrl,
  provideMediaDelivery,
} from './media-delivery';

describe('Entrega de imágenes', () => {
  it('elige el ancho generado más pequeño que cubre el pedido', () => {
    expect(pickWidth([640, 1024, 1600], 800)).toBe(1024);
    expect(pickWidth([640, 1024, 1600], 640)).toBe(640);
    expect(pickWidth([640, 1024, 1600], 2400)).toBe(1600);
    expect(pickWidth([1024, 640], undefined)).toBe(1024);
  });

  it('construye URL y srcset solo con los anchos generados', () => {
    expect(mediaUrl('/media', 'viajes/cala.0a1b2c3d', 1024, 'avif')).toBe(
      '/media/viajes/cala.0a1b2c3d-1024.avif',
    );
    expect(mediaSrcset('/media', 'viajes/cala.0a1b2c3d', [640, 1024], 'webp')).toBe(
      '/media/viajes/cala.0a1b2c3d-640.webp 640w, /media/viajes/cala.0a1b2c3d-1024.webp 1024w',
    );
  });

  it('acepta /media o una URL https (Azure Blob Storage) y descarta el resto', () => {
    expect(normalizeMediaBaseUrl(undefined)).toBe('/media');
    expect(normalizeMediaBaseUrl('/media/')).toBe('/media');
    expect(normalizeMediaBaseUrl('https://cuenta.blob.core.windows.net/media/')).toBe(
      'https://cuenta.blob.core.windows.net/media',
    );
    expect(normalizeMediaBaseUrl('http://inseguro.test/media')).toBe('/media');
    expect(normalizeMediaBaseUrl('//cdn.test/media')).toBe('/media');
  });

  it('el loader de NgOptimizedImage usa el origen configurado y WebP', () => {
    TestBed.configureTestingModule({
      providers: [provideMediaDelivery(), provideMediaBaseUrl('https://cdn.test/media')],
    });
    expect(TestBed.inject(MEDIA_BASE_URL)).toBe('https://cdn.test/media');
    const loader = TestBed.inject(IMAGE_LOADER);
    expect(
      loader({
        src: 'motor/stelvio.12345678',
        width: 1100,
        loaderParams: { widths: [640, 1024, 1600] },
      }),
    ).toBe('https://cdn.test/media/motor/stelvio.12345678-1600.webp');
  });
});
