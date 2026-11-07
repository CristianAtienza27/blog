import type { Destination, TravelMode, Trip } from '../../../content/models';
import { placeArea } from '../../../editorial/article-meta';
import { formatElevation } from '../../../editorial/geo';

export const MODE_LABELS: Record<TravelMode, string> = {
  coche: 'en coche',
  moto: 'en moto',
  tren: 'en tren',
  barco: 'en barco',
  'a-pie': 'a pie',
};

/** Capítulo que trata un destino: su ancla y su número, si lo tiene. */
export interface ChapterRef {
  id: string;
  number?: number;
}

/** Destino → capítulo del artículo que lo trata. */
export type ChapterIndex = Readonly<Record<string, ChapterRef>>;

export interface PlacePlan {
  destination: string;
  /** «01», «02»… (el número del mapa). */
  number: string;
  name: string;
  /** «Girona · España» y la altitud si la hay («2757 m»). */
  area: string;
  summary: string;
  /** Capítulo que trata el lugar, si lo hay. */
  chapter: ChapterRef | null;
}

export interface StopPlan extends PlacePlan {
  /** Tramo desde la parada anterior, solo con los datos que existan: «42 km», «en moto». */
  leg: string | null;
}

export interface TripPlan {
  title: string;
  summary: string;
  /** «4 paradas · 117 km aprox. · en coche» (cada dato, solo si existe). */
  facts: string[];
  stops: StopPlan[];
}

const pad = (n: number) => String(n).padStart(2, '0');

const areaOf = (d: Destination) => {
  // Sin repetir el nombre como región («Lisboa · Lisboa · Portugal»).
  const area = placeArea({
    region: d.region === d.name ? undefined : d.region,
    country: d.country,
  });
  return d.elevation === undefined ? area : `${area} · ${formatElevation(d.elevation)}`;
};

/** Lugares relacionados con el artículo, en orden, con su capítulo si lo tienen. */
export function placesPlan(
  destinations: readonly Destination[],
  chapters: ChapterIndex,
): PlacePlan[] {
  return destinations.map((d, i) => ({
    destination: d.id,
    number: pad(i + 1),
    name: d.name,
    area: areaOf(d),
    summary: d.summary,
    chapter: chapters[d.id] ?? null,
  }));
}

/**
 * Itinerario: paradas en orden y, solo si los datos existen, distancia total (si todos los
 * tramos la tienen), medio de transporte (si es el mismo en todos) y datos de cada tramo.
 */
export function tripPlan(trip: Trip, chapters: ChapterIndex): TripPlan {
  const stops = [...trip.stops].sort((a, b) => a.order - b.order);
  const legs = stops.slice(1);
  const modes = new Set(stops.map((s) => s.mode));
  const sharedMode = modes.size === 1 ? [...modes][0] : undefined;
  const total =
    legs.length && legs.every((s) => s.distanceKm !== undefined)
      ? legs.reduce((sum, s) => sum + (s.distanceKm ?? 0), 0)
      : null;

  const facts = [
    stops.length === 1 ? '1 parada' : `${stops.length} paradas`,
    ...(total !== null ? [`${Math.round(total)} km aprox.`] : []),
    ...(sharedMode ? [MODE_LABELS[sharedMode]] : []),
  ];

  return {
    title: trip.title,
    summary: trip.summary,
    facts,
    stops: stops.map((stop, i) => {
      const [place] = placesPlan([stop.destination], chapters);
      const leg = [
        ...(stop.distanceKm !== undefined ? [`${stop.distanceKm} km`] : []),
        ...(stop.mode && !sharedMode ? [MODE_LABELS[stop.mode]] : []),
      ];
      return {
        ...place,
        number: pad(i + 1),
        summary: stop.note ?? stop.destination.summary,
        leg: i > 0 && leg.length ? leg.join(' · ') : null,
      };
    }),
  };
}
