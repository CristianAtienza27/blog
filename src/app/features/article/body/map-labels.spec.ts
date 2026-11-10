import { readFileSync } from 'node:fs';
import type { MapPoint } from '../../../content/models';
import { MIN_MAP_PX, WIDE_MAP_PX, mapLabelReport } from './map-labels';
import { mapLayout } from './map-layout';

interface DestinationRecord {
  id: string;
  name: string;
  location: { lat: number; lng: number };
}

/** Puntos de los mapas de referencia, con las coordenadas reales de src/content. */
function referenceMaps(): Record<string, { points: MapPoint[]; route: boolean }> {
  const read = <T>(name: string): T => JSON.parse(readFileSync(`src/content/${name}`, 'utf8'));
  const destinations = new Map(
    read<DestinationRecord[]>('destinations.json').map((d) => [d.id, d]),
  );
  const trips = read<{ id: string; stops: { destination: string }[] }[]>('trips.json');
  const point = (id: string): MapPoint => {
    const d = destinations.get(id);
    if (!d) throw new Error(`destino ${id}`);
    return { label: d.name, location: d.location, destination: { id } } as unknown as MapPoint;
  };
  const trip = (id: string) =>
    trips.find((t) => t.id === id)?.stops.map((s) => point(s.destination)) ?? [];
  return {
    'Costa Brava': { points: trip('costa-brava'), route: true },
    Stelvio: { points: trip('stelvio'), route: true },
    Ciudades: { points: ['lisboa', 'florencia', 'praga', 'oporto'].map(point), route: false },
    Calas: { points: [point('cala-s-almunia')], route: false },
    Alicante: { points: [point('alicante')], route: false },
  };
}

describe('Mapas: etiquetas y coordenadas sin desbordar ni solaparse', () => {
  const maps = referenceMaps();

  it('el mapa más estrecho previsto es el de una pantalla de 320 px', () => {
    expect(MIN_MAP_PX).toBe(320);
  });

  it('el CSS cambia a la colocación amplia en el mismo ancho que map-labels.ts', () => {
    const scss = readFileSync('src/app/features/article/body/route-map.scss', 'utf8');
    expect(scss).toContain(`$wide-map: ${WIDE_MAP_PX}px;`);
  });

  // Anchos de pantalla pedidos, los bordes de cada tramo y anchos reales del mapa en tableta
  // y escritorio (entre unos 480 y 700 px según la plantilla y el alto de la pantalla).
  const widths = [320, 390, 414, WIDE_MAP_PX - 1, WIDE_MAP_PX, 480, 540, 614, 700, 768, 1440];
  for (const [name, { points, route }] of Object.entries(maps)) {
    it(`${name}: nada se sale ni se solapa a 320, 390, 768 y 1440 px (y entre medias)`, () => {
      const layout = mapLayout(points, route);
      for (const width of widths) {
        const report = mapLabelReport(layout.markers, layout.grid, layout, layout.ratio, width);
        expect(report, `${name} @${width}`).toEqual({ outside: [], overlaps: [] });
      }
    });
  }

  // Pruebas negativas: piezas en el centro horizontal del mapa (dentro por los lados) que se
  // salen solo por arriba o solo por abajo. Las debe detectar el límite vertical.
  it('detecta lo que se sale por arriba', () => {
    const { points, route } = maps['Costa Brava'];
    const layout = mapLayout(points, route);
    // Cadaqués pegado al borde superior y la latitud más al norte en la primera franja.
    const markers = layout.markers.map((m, i) => (i === 3 ? { ...m, y: 0.01 } : m));
    const grid = layout.grid.map((g, i) =>
      i === 2
        ? { ...g, at: 0.045, row: 'top' as const, wide: { ...g.wide, row: 'top' as const } }
        : g,
    );
    expect(grid[2].label).toBe('42.2° N');
    const { outside } = mapLabelReport(markers, grid, layout, layout.ratio, 320);
    expect(outside).toEqual(['Cadaqués (disco)', 'Cadaqués', '42.2° N']);
  });

  it('detecta lo que se sale por abajo', () => {
    const { points, route } = maps['Costa Brava'];
    const layout = mapLayout(points, route);
    // Tossa de Mar pegado al borde inferior, en un mapa ancho.
    const markers = layout.markers.map((m, i) => (i === 0 ? { ...m, y: 0.995 } : m));
    const { outside } = mapLabelReport(markers, layout.grid, layout, layout.ratio, 1440);
    expect(outside).toEqual(['Tossa de Mar (disco)', 'Tossa de Mar']);
  });

  // En mapas anchos, la disposición aprobada: latitudes arriba a la izquierda, longitudes
  // abajo a la derecha de su línea y la flecha arriba a la derecha. Solo cambia lo que de
  // verdad no cabe (la última longitud del Stelvio, pegada al borde).
  for (const name of ['Costa Brava', 'Stelvio', 'Ciudades']) {
    it(`${name}: en mapas anchos vuelve la colocación habitual`, () => {
      const { points, route } = maps[name];
      const layout = mapLayout(points, route);
      expect(layout.northWide).toEqual({ anchor: 'end', row: 'top' });
      for (const line of layout.grid) {
        const usual = line.axis === 'lat' ? 'top' : 'bottom';
        const anchor = name === 'Stelvio' && line.label === '10.6° E' ? 'end' : 'start';
        expect(line.wide, line.label).toEqual({ anchor, row: usual });
      }
    });
  }

  it('Stelvio: la última coordenada, pegada al borde, va a la izquierda de su línea', () => {
    const { points, route } = maps['Stelvio'];
    const lng = mapLayout(points, route).grid.filter((g) => g.axis === 'lng');
    expect(lng.at(-1)).toMatchObject({ label: '10.6° E', anchor: 'end' });
  });

  it('las coordenadas reales no cambian: solo su colocación', () => {
    const { points, route } = maps['Costa Brava'];
    const layout = mapLayout(points, route);
    expect(layout.grid.filter((g) => g.axis === 'lng').map((g) => g.label)).toEqual([
      '2.8° E',
      '3.0° E',
      '3.2° E',
      '3.4° E',
    ]);
    expect(layout.markers.map((m) => m.label)).toEqual([
      'Tossa de Mar',
      'Calella de Palafrugell',
      'Begur',
      'Cadaqués',
    ]);
  });

  it('cada etiqueta sabe cuánto sitio tiene hasta el borde hacia el que va', () => {
    const { points, route } = maps['Costa Brava'];
    for (const m of mapLayout(points, route).markers) {
      expect(m.room).toBeCloseTo(m.side === 'end' ? 1 - m.x : m.x, 6);
    }
  });
});
