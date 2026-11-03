import { DemoFlag, GeoPoint } from './common';
import { Media } from './media';

export const TRAVEL_MODES = ['coche', 'moto', 'tren', 'barco', 'a-pie'] as const;
export type TravelMode = (typeof TRAVEL_MODES)[number];

export interface Destination extends DemoFlag {
  id: string;
  slug: string;
  name: string;
  /** Código ISO 3166-1 alfa-2. */
  country: string;
  region?: string;
  location: GeoPoint;
  summary: string;
  cover?: Media;
}

export interface TripStop {
  /** Posición en la ruta, desde 1. */
  order: number;
  destination: Destination;
  note?: string;
  /** Distancia desde la parada anterior. */
  distanceKm?: number;
  mode?: TravelMode;
}

/** Itinerario: sin fechas, no describe un viaje realizado. */
export interface Trip extends DemoFlag {
  id: string;
  slug: string;
  title: string;
  summary: string;
  stops: TripStop[];
  cover?: Media;
}
