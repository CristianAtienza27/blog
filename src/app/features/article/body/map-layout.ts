import type { MapPoint } from '../../../content/models';
import { formatCoordinates } from '../../../editorial/geo';

/** Ancho del viewBox del SVG; el alto sale de la proporción del encuadre. */
export const MAP_WIDTH = 1000;
/** Proporciones límite (ancho / alto) del encuadre: ni una tira ni una columna. */
const RATIO_MIN = 0.8;
const RATIO_MAX = 1.6;
/** Margen alrededor de los puntos (fracción del lado), para que quepan las etiquetas. */
const PAD = 0.16;
/** Encuadre de un único punto: unos 0,3° de lado (el punto, en su entorno). */
const SINGLE_SPAN = 0.3;
/** Pasos de la retícula, en grados. */
const STEPS = [0.01, 0.02, 0.05, 0.1, 0.2, 0.25, 0.5, 1, 2, 5, 10, 20];
/** Distancia vertical (fracción del alto) por debajo de la cual dos etiquetas chocarían. */
const LABEL_GAP = 0.09;

export interface MapMarker {
  /** Destino del punto (para destacarlo al leer su capítulo), si lo hay. */
  destination: string | null;
  /** «01», «02»… en el orden de los puntos (el mismo número que en la ruta). */
  number: string;
  label: string;
  /** Posición en el encuadre (0–1 desde la esquina superior izquierda). */
  x: number;
  y: number;
  /** Lado de la etiqueta respecto al punto. */
  side: 'start' | 'end';
  /** «41.72° N · 2.93° E» (en la esquemática, null: no se muestran coordenadas). */
  coords: string | null;
}

export interface GridLine {
  axis: 'lat' | 'lng';
  /** Posición (0–1) en el eje correspondiente. */
  at: number;
  label: string;
}

export interface MapLayout {
  /** geographic: posiciones a partir de las coordenadas; schematic: orden sin geografía. */
  kind: 'geographic' | 'schematic';
  /** Ancho / alto del encuadre. */
  ratio: number;
  height: number;
  markers: MapMarker[];
  /** Trazo del recorrido en coordenadas del viewBox (null sin ruta). */
  path: string | null;
  grid: GridLine[];
  /** Descripción para lectores de pantalla (<desc>). */
  description: string;
}

const decimals = (step: number) => Math.max(0, -Math.floor(Math.log10(step) + 1e-9));

/** «41.72° N», «9.14° O» (mismo formato que formatCoordinates, con los decimales del paso). */
export function formatCoord(value: number, axis: 'lat' | 'lng', digits = 2): string {
  // El ecuador y el meridiano cero no tienen hemisferio.
  if (Math.abs(value) < 10 ** -(digits + 1)) return `${(0).toFixed(digits)}°`;
  const hemisphere = axis === 'lat' ? (value >= 0 ? 'N' : 'S') : value >= 0 ? 'E' : 'O';
  return `${Math.abs(value).toFixed(digits)}° ${hemisphere}`;
}

/** Paso de retícula con entre 2 y 5 líneas en el lado mayor del encuadre. */
export function gridStep(span: number): number {
  return STEPS.find((step) => span / step <= 5) ?? STEPS[STEPS.length - 1];
}

/**
 * Lados de las etiquetas: hacia el centro del encuadre y, si dos etiquetas quedarían a la
 * misma altura en el mismo lado, una cambia de lado (la que tiene sitio).
 */
function placeLabels(markers: MapMarker[]): void {
  for (const m of markers) m.side = m.x > 0.55 ? 'start' : 'end';
  const byY = [...markers].sort((a, b) => a.y - b.y);
  for (let i = 1; i < byY.length; i++) {
    const [a, b] = [byY[i - 1], byY[i]];
    if (a.side !== b.side || b.y - a.y >= LABEL_GAP) continue;
    // Cambia la que esté más lejos del borde hacia el que se iría su etiqueta.
    const room = (m: MapMarker) => (m.side === 'start' ? 1 - m.x : m.x);
    const flip = room(a) >= room(b) ? a : b;
    flip.side = flip.side === 'start' ? 'end' : 'start';
  }
}

function describe(points: readonly MapPoint[], route: boolean, kind: MapLayout['kind']): string {
  const list = points
    .map(
      (p, i) =>
        `${i + 1}. ${p.label}${kind === 'geographic' ? ` (${formatCoordinates(p.location)})` : ''}`,
    )
    .join('; ');
  const what =
    points.length === 1
      ? 'Un lugar'
      : route
        ? `${points.length} paradas unidas en orden`
        : `${points.length} lugares`;
  return `${what}: ${list}.`;
}

/** Disposición sin geografía: puntos en diagonal, en orden (solo si no hay posiciones útiles). */
function schematic(points: readonly MapPoint[], route: boolean): MapLayout {
  const n = points.length;
  const markers = points.map((p, i): MapMarker => ({
    destination: p.destination?.id ?? null,
    number: String(i + 1).padStart(2, '0'),
    label: p.label,
    x: n === 1 ? 0.5 : 0.2 + (0.6 * i) / (n - 1),
    y: n === 1 ? 0.5 : 0.75 - (0.5 * i) / (n - 1),
    side: 'end',
    coords: null,
  }));
  placeLabels(markers);
  const ratio = 1.4;
  const height = Math.round(MAP_WIDTH / ratio);
  return {
    kind: 'schematic',
    ratio,
    height,
    markers,
    path: route && n > 1 ? pathOf(markers, height) : null,
    grid: [],
    description: describe(points, route, 'schematic'),
  };
}

const pathOf = (markers: readonly MapMarker[], height: number) =>
  markers
    .map((m, i) => `${i ? 'L' : 'M'}${(m.x * MAP_WIDTH).toFixed(1)} ${(m.y * height).toFixed(1)}`)
    .join(' ');

/** Sin posiciones útiles (todos los puntos en el mismo sitio): disposición esquemática. */
export function mapKind(points: readonly MapPoint[]): MapLayout['kind'] {
  if (points.length < 2) return points.length ? 'geographic' : 'schematic';
  const [first] = points;
  const same = points.every(
    (p) => p.location.lat === first.location.lat && p.location.lng === first.location.lng,
  );
  return same ? 'schematic' : 'geographic';
}

/**
 * Encuadre del mapa a partir de las coordenadas reales (proyección equirrectangular con la
 * longitud corregida por la latitud media: válida para zonas pequeñas, orientativa en grandes).
 * Si los puntos no se pueden separar (todos en el mismo sitio), disposición esquemática.
 */
export function mapLayout(points: readonly MapPoint[], route: boolean): MapLayout {
  if (mapKind(points) === 'schematic') return schematic(points, route);
  const lats = points.map((p) => p.location.lat);
  const lngs = points.map((p) => p.location.lng);
  const latMid = (Math.min(...lats) + Math.max(...lats)) / 2;
  const k = Math.cos((latMid * Math.PI) / 180);
  const single = points.length === 1;

  // Lados del encuadre en «grados de latitud» (la longitud, ya corregida).
  const lngMid = (Math.min(...lngs) + Math.max(...lngs)) / 2;
  let spanX = single ? SINGLE_SPAN : (Math.max(...lngs) - Math.min(...lngs)) * k;
  let spanY = single ? SINGLE_SPAN / RATIO_MAX : Math.max(...lats) - Math.min(...lats);
  spanX = Math.max(spanX, spanY * 0.05);
  spanY = Math.max(spanY, spanX * 0.05);
  spanX *= 1 + 2 * PAD;
  spanY *= 1 + 2 * PAD;
  const ratio = Math.min(RATIO_MAX, Math.max(RATIO_MIN, spanX / spanY));
  if (spanX / spanY < ratio) spanX = spanY * ratio;
  else spanY = spanX / ratio;

  const west = lngMid - spanX / k / 2;
  const north = latMid + spanY / 2;
  const toX = (lng: number) => ((lng - west) * k) / spanX;
  const toY = (lat: number) => (north - lat) / spanY;
  const height = Math.round(MAP_WIDTH / ratio);

  const markers = points.map((p, i): MapMarker => ({
    destination: p.destination?.id ?? null,
    number: String(i + 1).padStart(2, '0'),
    label: p.label,
    x: toX(p.location.lng),
    y: toY(p.location.lat),
    side: 'end',
    coords: formatCoordinates(p.location),
  }));
  placeLabels(markers);

  // Retícula: mismo paso en latitud y longitud, en grados «redondos».
  const step = gridStep(Math.max(spanY, spanX / k));
  const digits = decimals(step);
  const grid: GridLine[] = [];
  for (let lat = Math.ceil((north - spanY) / step) * step; lat <= north; lat += step) {
    const at = toY(lat);
    if (at > 0.04 && at < 0.96)
      grid.push({ axis: 'lat', at, label: formatCoord(lat, 'lat', digits) });
  }
  const east = west + spanX / k;
  for (let lng = Math.ceil(west / step) * step; lng <= east; lng += step) {
    const at = toX(lng);
    if (at > 0.04 && at < 0.96)
      grid.push({ axis: 'lng', at, label: formatCoord(lng, 'lng', digits) });
  }

  return {
    kind: 'geographic',
    ratio,
    height,
    markers,
    path: route && points.length > 1 ? pathOf(markers, height) : null,
    grid,
    description: describe(points, route, 'geographic'),
  };
}
