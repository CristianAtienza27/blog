/** Coordenadas geográficas en grados decimales (WGS 84). */
export interface GeoPoint {
  lat: number;
  lng: number;
}

/** Página de resultados. Mismo contrato que devolverá la API (Spring Data Page). */
export interface Page<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

/** Marca de contenido de demostración: se muestra como tal y no se indexa. */
export interface DemoFlag {
  demo: boolean;
}
