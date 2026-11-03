import { countryName, formatCoordinates, formatElevation } from './geo';

describe('Formato geográfico', () => {
  it('coordenadas con dos decimales y hemisferios en español', () => {
    expect(formatCoordinates({ lat: 46.53, lng: 10.45 })).toBe('46.53° N · 10.45° E');
    expect(formatCoordinates({ lat: -33.4, lng: -70.65 })).toBe('33.40° S · 70.65° O');
    expect(formatCoordinates({ lat: 0, lng: 0 })).toBe('0.00° N · 0.00° E');
  });

  it('altitud en metros con separador de miles español', () => {
    expect(formatElevation(2757)).toBe('2757 m');
    expect(formatElevation(0)).toBe('0 m');
    expect(formatElevation(-28)).toBe('-28 m');
    expect(formatElevation(8848)).toBe('8848 m');
  });

  it('nombre del país en español y el código si no se reconoce', () => {
    expect(countryName('IT')).toBe('Italia');
    expect(countryName('ES')).toBe('España');
    expect(countryName('no-es-un-código')).toBe('no-es-un-código');
  });
});
