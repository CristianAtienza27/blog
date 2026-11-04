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
  /** Altitud en metros sobre el nivel del mar. */
  elevation?: number;
  summary: string;
  cover?: Media;
}

/**
 * Referencia ligera a un destino (sin portada ni descripción), para listados de artículos.
 * El destino completo se obtiene de TravelRepository.
 */
export type DestinationRef = Omit<Destination, 'cover' | 'summary'>;

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
