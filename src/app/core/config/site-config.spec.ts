import { normalizeSiteUrl } from './site-config';

describe('normalizeSiteUrl', () => {
  it('acepta URL https y elimina la barra final', () => {
    expect(normalizeSiteUrl('https://dominio.test/')).toBe('https://dominio.test');
    expect(normalizeSiteUrl(' https://dominio.test/revista// ')).toBe(
      'https://dominio.test/revista',
    );
  });

  it('sin valor o con un valor no válido devuelve null (no se inventa un dominio)', () => {
    expect(normalizeSiteUrl(undefined)).toBeNull();
    expect(normalizeSiteUrl('')).toBeNull();
    expect(normalizeSiteUrl('dominio.test')).toBeNull();
    expect(normalizeSiteUrl('ftp://dominio.test')).toBeNull();
  });
});
