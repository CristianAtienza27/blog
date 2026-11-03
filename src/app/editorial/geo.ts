import type { GeoPoint } from '../content/models';

/** «46.53° N · 10.45° E»: dos decimales (≈1 km), hemisferios explícitos. */
export function formatCoordinates({ lat, lng }: GeoPoint): string {
  const part = (value: number, positive: string, negative: string) =>
    `${Math.abs(value).toFixed(2)}° ${value >= 0 ? positive : negative}`;
  return `${part(lat, 'N', 'S')} · ${part(lng, 'E', 'O')}`;
}

/** «2757 m» con separador de miles español a partir de cinco cifras. */
export function formatElevation(metres: number): string {
  return `${new Intl.NumberFormat('es-ES').format(metres)} m`;
}

/** Nombre del país en español a partir de su código ISO 3166-1 alfa-2. */
export function countryName(code: string): string {
  try {
    return new Intl.DisplayNames(['es'], { type: 'region' }).of(code) ?? code;
  } catch {
    return code;
  }
}
