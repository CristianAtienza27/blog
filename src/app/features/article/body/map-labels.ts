import type { GridLine, MapMarker } from './map-layout';

/**
 * Colocación de las coordenadas de la retícula y de la flecha del norte sin chocar con los
 * puntos, sus etiquetas ni entre sí. Se resuelve dos veces, cada una para el mapa más estrecho
 * de su tramo (con más ancho las piezas, de tamaño fijo en px, solo se separan más):
 * - compacta, desde MIN_MAP_PX: el mapa ocupa todo el ancho en móvil y el más estrecho es el
 *   de una pantalla de 320 px;
 * - amplia, desde WIDE_MAP_PX: tableta y escritorio, donde casi siempre caben las posiciones
 *   habituales y no hay por qué heredar las que solo hacen falta en móvil.
 * El CSS elige una u otra con una consulta de contenedor al ancho del mapa (route-map.scss,
 * con el mismo umbral). Las medidas son las del CSS del mapa, medidas en el navegador y
 * redondeadas al alza.
 */
export const MIN_MAP_PX = 320;
export const WIDE_MAP_PX = 460;
/** Margen de las coordenadas al borde (var(--space-2)) y de la flecha (var(--space-3)). */
const INSET = 8;
const NORTH_INSET = 12;
const NORTH_WIDTH = 24;
/** Margen libre a cada lado de la flecha: que nunca se lea junto a una coordenada. */
const NORTH_GAP = 8;
/** Coordenada: alto de línea, ancho medio por carácter y separación con su línea. */
const COORD_HEIGHT = 16;
const COORD_CHAR = 7.3;
const COORD_GAP = 4;
/** Disco del punto: radio del activo (1,5rem × 1,2 / 2). */
const BADGE_RADIUS = 14;
/** Nombre del punto: ancho medio por carácter, alto de línea, máximo (9em) y separación. */
const LABEL_CHAR = 6.4;
const LABEL_LINE = 20;
const LABEL_MAX = 126;
const LABEL_GAP = 14;

/** Colocación de una coordenada (ver GridLine). */
export type LabelPlace = Pick<GridLine, 'anchor' | 'row'>;
type Place = LabelPlace;

/** Esquina de la flecha del norte: arriba (top) o abajo (bottom), a la derecha (end) o a la izquierda (start). */
export interface NorthPlace {
  anchor: 'start' | 'end';
  row: 'top' | 'bottom';
}

/** Esquinas de la flecha, en orden de preferencia: abajo solo si arriba no cabe. */
const NORTH_PLACES: readonly NorthPlace[] = [
  { anchor: 'end', row: 'top' },
  { anchor: 'start', row: 'top' },
  { anchor: 'end', row: 'bottom' },
  { anchor: 'start', row: 'bottom' },
];

interface Box {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

/** Área común de dos cajas (0 si no se tocan); medio píxel de tolerancia por el redondeo. */
const overlap = (a: Box, b: Box) =>
  Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left) - 0.5) *
  Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) - 0.5);

/** Disco y nombre de cada punto, en px, a un ancho dado. */
function markerBoxes(markers: readonly MapMarker[], width: number, height: number): Box[] {
  return markers.flatMap((m) => {
    const cx = m.x * width;
    const cy = m.y * height;
    const badge = {
      left: cx - BADGE_RADIUS,
      right: cx + BADGE_RADIUS,
      top: cy - BADGE_RADIUS,
      bottom: cy + BADGE_RADIUS,
    };
    const natural = m.label.length * LABEL_CHAR;
    const room = m.room * width - LABEL_GAP - BADGE_RADIUS - INSET;
    const labelWidth = Math.max(1, Math.min(natural, LABEL_MAX, room));
    const lines = Math.ceil(natural / labelWidth);
    const edge = m.side === 'end' ? cx + BADGE_RADIUS + LABEL_GAP : cx - BADGE_RADIUS - LABEL_GAP;
    const label = {
      left: m.side === 'end' ? edge : edge - labelWidth,
      right: m.side === 'end' ? edge + labelWidth : edge,
      top: cy - (lines * LABEL_LINE) / 2,
      bottom: cy + (lines * LABEL_LINE) / 2,
    };
    return [badge, label];
  });
}

const coordWidth = (line: GridLine) => line.label.length * COORD_CHAR;

/** Posiciones posibles de una coordenada, en orden de preferencia. */
function candidates(line: GridLine, width: number): Place[] {
  if (line.axis === 'lat') {
    return [
      { anchor: 'start', row: 'top' },
      { anchor: 'end', row: 'top' },
      { anchor: 'start', row: 'bottom' },
      { anchor: 'end', row: 'bottom' },
    ];
  }
  // A la derecha de su línea, salvo que ahí no quepa (cerca del borde derecho).
  const fits = line.at * width + COORD_GAP + coordWidth(line) <= width - INSET;
  const near = fits ? 'start' : 'end';
  const far = fits ? 'end' : 'start';
  return [
    { anchor: near, row: 'bottom' },
    { anchor: far, row: 'bottom' },
    { anchor: near, row: 'top' },
    { anchor: far, row: 'top' },
  ];
}

/**
 * Caja de una coordenada como la coloca el CSS. Latitud: en el borde izquierdo (start) o
 * derecho (end), encima (top) o debajo (bottom) de su línea. Longitud: a la derecha (start)
 * o a la izquierda (end) de su línea, en el borde inferior (bottom) o superior (top).
 */
function coordBox(line: GridLine, place: Place, width: number, height: number): Box {
  const w = coordWidth(line);
  if (line.axis === 'lat') {
    const y = line.at * height;
    const left = place.anchor === 'start' ? INSET : width - INSET - w;
    const top = place.row === 'top' ? y - 1.2 * COORD_HEIGHT : y + 0.2 * COORD_HEIGHT;
    return { left, right: left + w, top, bottom: top + COORD_HEIGHT };
  }
  const x = line.at * width;
  const left = place.anchor === 'start' ? x + COORD_GAP : x - COORD_GAP - w;
  const top = place.row === 'bottom' ? height - INSET - COORD_HEIGHT : INSET;
  return { left, right: left + w, top, bottom: top + COORD_HEIGHT };
}

/** Pieza que se coloca: sus posiciones posibles (en orden de preferencia) y la elegida. */
interface Piece {
  boxes: Box[];
  chosen: number;
}

/** Solape de una caja con los obstáculos fijos y con las demás piezas ya colocadas. */
function cost(box: Box, fixed: readonly Box[], pieces: readonly Piece[], self: Piece): number {
  let area = fixed.reduce((sum, b) => sum + overlap(box, b), 0);
  for (const p of pieces) if (p !== self) area += overlap(box, p.boxes[p.chosen]);
  return area;
}

/** Flecha del norte en su esquina, con su margen libre a los lados. */
function northBox(place: NorthPlace, width: number, height: number): Box {
  const left = place.anchor === 'end' ? width - NORTH_INSET - NORTH_WIDTH : NORTH_INSET;
  const top = place.row === 'top' ? INSET : height - INSET - COORD_HEIGHT;
  return {
    left: left - NORTH_GAP,
    right: left + NORTH_WIDTH + NORTH_GAP,
    top,
    bottom: top + COORD_HEIGHT,
  };
}

/** La primera posición sin choques; si todas chocan, la que menos se solapa. */
function best(piece: Piece, fixed: readonly Box[], pieces: readonly Piece[]): number {
  let choice = piece.chosen;
  let least = cost(piece.boxes[choice], fixed, pieces, piece);
  for (let i = 0; i < piece.boxes.length; i++) {
    const area = cost(piece.boxes[i], fixed, pieces, piece);
    if (area < least || (area === least && i < choice)) [choice, least] = [i, area];
  }
  return choice;
}

/**
 * Coloca las coordenadas y la flecha del norte a un ancho dado. Para cada esquina posible de
 * la flecha: cada coordenada en su primera posición libre, en orden, y después unas pocas
 * pasadas en las que cada una que aún choca prueba sus otras posiciones contra todas las
 * demás. Gana la disposición con menos solape (a igualdad, la esquina preferida: arriba a la
 * derecha).
 */
function arrange(
  markers: readonly MapMarker[],
  grid: readonly GridLine[],
  ratio: number,
  width: number,
): { places: Place[]; north: NorthPlace } {
  const height = width / ratio;
  const markerBoxesPx = markerBoxes(markers, width, height);

  // Solo posiciones dentro del mapa (si no cabe en ninguna, todas: mejor solapada que fuera).
  const places = grid.map((line) => {
    const all = candidates(line, width);
    const inside = all.filter((place) => {
      const b = coordBox(line, place, width, height);
      return b.left >= 0 && b.right <= width;
    });
    return inside.length ? inside : all;
  });
  const tryNorth = (north: NorthPlace) => {
    const fixed = [...markerBoxesPx, northBox(north, width, height)];
    const pieces: Piece[] = grid.map((line, i) => ({
      boxes: places[i].map((place) => coordBox(line, place, width, height)),
      chosen: 0,
    }));
    const placed: Piece[] = [];
    for (const piece of pieces) {
      piece.chosen = best(piece, fixed, placed);
      placed.push(piece);
    }
    for (let pass = 0; pass < 4; pass++) {
      let changed = false;
      for (const piece of pieces) {
        const choice = best(piece, fixed, pieces);
        if (choice !== piece.chosen) [piece.chosen, changed] = [choice, true];
      }
      if (!changed) break;
    }
    const total =
      pieces.reduce((sum, p) => sum + cost(p.boxes[p.chosen], fixed, pieces, p), 0) +
      markerBoxesPx.reduce((sum, b) => sum + overlap(b, northBox(north, width, height)), 0);
    return { north, choices: pieces.map((p) => p.chosen), total };
  };

  const result = NORTH_PLACES.map(tryNorth).reduce((a, b) => (b.total < a.total ? b : a));
  return { places: grid.map((_, i) => places[i][result.choices[i]]), north: result.north };
}

/**
 * Colocación compacta y amplia de las coordenadas y de la flecha del norte. Escribe las dos
 * en cada línea de la retícula (anchor/row y wide) y devuelve las dos esquinas de la flecha.
 */
export function placeMapLabels(
  markers: readonly MapMarker[],
  grid: GridLine[],
  ratio: number,
): { north: NorthPlace; northWide: NorthPlace } {
  const compact = arrange(markers, grid, ratio, MIN_MAP_PX);
  const wide = arrange(markers, grid, ratio, WIDE_MAP_PX);
  grid.forEach((line, i) => Object.assign(line, compact.places[i], { wide: wide.places[i] }));
  return { north: compact.north, northWide: wide.north };
}

/**
 * Comprobación de una disposición ya colocada a un ancho dado (px), con la colocación que el
 * CSS aplica a ese ancho (compacta o amplia): piezas que se salen del mapa por cualquiera de
 * los cuatro lados (izquierda, derecha, arriba o abajo) y pares de piezas que se solapan, con
 * el mismo modelo de medidas. Para los tests: a cualquier ancho desde MIN_MAP_PX, nada debería
 * salirse ni solaparse.
 */
export function mapLabelReport(
  markers: readonly MapMarker[],
  grid: readonly GridLine[],
  labels: { north: NorthPlace; northWide: NorthPlace },
  ratio: number,
  width: number,
): { outside: string[]; overlaps: string[] } {
  const height = width / ratio;
  const isWide = width >= WIDE_MAP_PX;
  const north = isWide ? labels.northWide : labels.north;
  const named = [
    ...markerBoxes(markers, width, height).map((box, i) => ({
      name: `${markers[i >> 1].label}${i % 2 ? '' : ' (disco)'}`,
      box,
    })),
    ...grid.map((line) => ({
      name: line.label,
      box: coordBox(line, isWide ? line.wide : line, width, height),
    })),
    { name: 'N', box: northBox(north, width, height) },
  ];
  const outside = named
    .filter(({ box }) => box.left < 0 || box.right > width || box.top < 0 || box.bottom > height)
    .map((n) => n.name);
  const overlaps: string[] = [];
  for (let i = 0; i < named.length; i++) {
    for (let j = i + 1; j < named.length; j++) {
      const sameMarker = i < markers.length * 2 && j < markers.length * 2 && i >> 1 === j >> 1;
      if (!sameMarker && overlap(named[i].box, named[j].box) > 0) {
        overlaps.push(`${named[i].name} × ${named[j].name}`);
      }
    }
  }
  return { outside, overlaps };
}
