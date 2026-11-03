import type { ContentSource } from '../json/content-source';
import { readingMinutes } from '../records/reading-time';
import type {
  ArticleBodyRecord,
  ArticleRecord,
  CollectionName,
  ContentCollections,
  MediaRecord,
} from '../records/records';

/**
 * Datos de prueba pequeños y coherentes, independientes del contenido real.
 * Cubren todas las relaciones y todos los tipos de bloque.
 */

const PLACEHOLDER = 'data:image/webp;base64,UklGRhIAAABXRUJQVlA4TAYAAAAvAAAAAAfQ//73v/+BiOh/AAA=';

function media(id: string, section: string, focal = { x: 0.5, y: 0.5 }): MediaRecord {
  const hash = id.length.toString(16).padStart(8, '0');
  return {
    id,
    path: `${section}/${id}.${hash}`,
    width: 2400,
    height: 1600,
    widths: [640, 1024, 1600, 2400],
    alt: `Imagen de prueba ${id}`,
    focal,
    placeholder: PLACEHOLDER,
    credit: {
      author: 'Autoría de prueba',
      source: 'wikimedia',
      sourceUrl: `https://commons.wikimedia.org/wiki/File:${id}.jpg`,
      license: 'CC0-1.0',
      licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
    },
    original: {
      file: `${id}.jpg`,
      format: 'jpeg',
      width: 4800,
      height: 3200,
      bytes: 5_000_000,
      sha256: Array.from(id, (c) => c.charCodeAt(0).toString(16))
        .join('')
        .padEnd(64, '0'),
      url: `https://upload.wikimedia.org/${id}.jpg`,
    },
    demo: true,
  };
}

/** Fotografía propia: sin URL de origen, con fecha, lugar y datos de cámara. */
export function ownMedia(id: string, section: string): MediaRecord {
  const base = media(id, section);
  return {
    ...base,
    width: 3024,
    height: 4032,
    widths: [640, 1024, 1600, 2400, 3024],
    title: `Fotografía propia ${id}`,
    takenAt: '2025-08-14',
    location: { name: 'Paso del Stelvio', country: 'IT', point: { lat: 46.53, lng: 10.45 } },
    camera: {
      make: 'Teléfono',
      model: 'Modelo de prueba',
      focalLength: 6.9,
      aperture: 1.8,
      exposure: '1/250',
      iso: 50,
    },
    credit: { author: 'Autor de prueba', source: 'propia', license: 'derechos-reservados' },
    original: {
      file: `${id}.heic`,
      format: 'heic',
      width: 3024,
      height: 4032,
      bytes: 2_400_000,
      sha256: base.original.sha256,
    },
    demo: false,
  };
}

export const FIXTURE_BODIES: ArticleBodyRecord[] = [
  {
    slug: 'calas-costa-brava',
    blocks: [
      { type: 'chapter', id: 'sur', number: 1, title: 'El sur', kicker: 'Primer tramo' },
      {
        type: 'text',
        html: '<p>Texto de <em>prueba</em> con un <a href="/viajes">enlace</a>.</p>',
      },
      { type: 'map', trip: 'costa-brava', route: true, caption: 'Recorrido de prueba' },
      { type: 'image', media: 'cala', layout: 'wide' },
      { type: 'chapter', id: 'norte', number: 2, title: 'El norte' },
      { type: 'gallery', gallery: 'serie-costa' },
      { type: 'quote', text: 'Una cita de prueba.', cite: 'Fuente de prueba', variant: 'pull' },
    ],
  },
  {
    slug: 'serie-costa',
    blocks: [
      { type: 'image', media: 'puerto', layout: 'full', caption: 'Pie propio del artículo' },
      { type: 'gallery', gallery: 'serie-costa' },
      { type: 'divider', variant: 'pause' },
    ],
  },
  {
    slug: 'paso-stelvio',
    blocks: [
      { type: 'text', html: '<p>Ficha de prueba.</p>' },
      {
        type: 'specs',
        title: 'Datos del puerto',
        items: [
          { label: 'Altitud', value: '2757', unit: 'm' },
          { label: 'Curvas', value: '48' },
        ],
        note: 'Datos de prueba',
      },
      { type: 'map', destinations: ['prato', 'stelvio'], route: false },
      { type: 'image', media: 'moto', layout: 'content' },
    ],
  },
  {
    slug: 'construir-revista',
    blocks: [
      { type: 'text', html: '<p>Notas de prueba sobre <code>Angular</code>.</p>' },
      {
        type: 'code',
        language: 'typescript',
        filename: 'app.config.ts',
        code: 'provideContent(withJsonContent());',
      },
      { type: 'image', media: 'codigo', layout: 'content' },
    ],
  },
  {
    slug: 'equipaje-ruta',
    blocks: [
      { type: 'text', html: '<ul><li>Primera cosa</li><li>Segunda cosa</li></ul>' },
      { type: 'divider', variant: 'line' },
    ],
  },
];

function article(
  record: Omit<ArticleRecord, 'readingMinutes' | 'author' | 'demo' | 'excerpt' | 'dek'>,
): ArticleRecord {
  const body = FIXTURE_BODIES.find((b) => b.slug === record.slug);
  return {
    dek: `Entradilla de ${record.title}`,
    excerpt: `Resumen de ${record.title}`,
    author: 'autor-demo',
    demo: true,
    readingMinutes: readingMinutes(body?.blocks ?? []),
    ...record,
  };
}

export function fixtureCollections(): ContentCollections {
  return {
    categories: [
      { id: 'viajes', slug: 'viajes', name: 'Viajes', description: 'Viajes' },
      { id: 'fotografia', slug: 'fotografia', name: 'Fotografía', description: 'Fotografía' },
      { id: 'motor', slug: 'motor', name: 'Motor', description: 'Motor' },
      {
        id: 'experiencias',
        slug: 'experiencias',
        name: 'Experiencias',
        description: 'Experiencias',
      },
      { id: 'tecnologia', slug: 'tecnologia', name: 'Tecnología', description: 'Tecnología' },
    ],
    tags: [
      { id: 'costa', slug: 'costa', label: 'Costa' },
      { id: 'montana', slug: 'montana', label: 'Montaña' },
      { id: 'motocicletas', slug: 'motocicletas', label: 'Motocicletas' },
      { id: 'angular', slug: 'angular', label: 'Angular' },
      { id: 'fotografia-nocturna', slug: 'fotografia-nocturna', label: 'Fotografía nocturna' },
    ],
    authors: [{ id: 'autor-demo', name: 'Autor de prueba', role: 'Pruebas' }],
    media: [
      media('cala', 'viajes', { x: 0.3, y: 0.6 }),
      media('puerto', 'viajes'),
      ownMedia('moto', 'motor'),
      media('codigo', 'tecnologia'),
    ],
    galleries: [
      {
        id: 'serie-costa',
        slug: 'serie-costa',
        title: 'Serie de prueba',
        layout: 'horizontal',
        items: [{ media: 'cala', caption: 'Primera' }, { media: 'puerto' }],
        demo: true,
      },
    ],
    destinations: [
      {
        id: 'tossa',
        slug: 'tossa',
        name: 'Tossa de Mar',
        country: 'ES',
        location: { lat: 41.72, lng: 2.93 },
        summary: 'Destino de prueba',
        demo: true,
      },
      {
        id: 'begur',
        slug: 'begur',
        name: 'Begur',
        country: 'ES',
        location: { lat: 41.95, lng: 3.21 },
        summary: 'Destino de prueba',
        cover: 'cala',
        demo: true,
      },
      {
        id: 'prato',
        slug: 'prato',
        name: 'Prato allo Stelvio',
        country: 'IT',
        location: { lat: 46.62, lng: 10.59 },
        summary: 'Destino de prueba',
        demo: true,
      },
      {
        id: 'stelvio',
        slug: 'stelvio',
        name: 'Paso del Stelvio',
        country: 'IT',
        location: { lat: 46.53, lng: 10.45 },
        elevation: 2757,
        summary: 'Destino de prueba',
        demo: true,
      },
    ],
    trips: [
      {
        id: 'costa-brava',
        slug: 'costa-brava',
        title: 'Ruta de prueba por la costa',
        summary: 'Itinerario de prueba',
        stops: [
          { order: 1, destination: 'tossa', mode: 'coche' },
          { order: 2, destination: 'begur', distanceKm: 50, mode: 'coche' },
        ],
        demo: true,
      },
      {
        id: 'alpes',
        slug: 'alpes',
        title: 'Ruta de prueba por los Alpes',
        summary: 'Itinerario de prueba',
        stops: [
          { order: 1, destination: 'prato', mode: 'moto' },
          { order: 2, destination: 'stelvio', distanceKm: 25, mode: 'moto' },
        ],
        demo: true,
      },
    ],
    projects: [
      {
        id: 'revista',
        slug: 'revista',
        name: 'Revista de prueba',
        summary: 'Proyecto de prueba',
        status: 'activo',
        year: 2026,
        stack: ['angular'],
        demo: true,
      },
    ],
    articles: [
      article({
        id: 'calas-costa-brava',
        slug: 'calas-costa-brava',
        title: 'Calas de la Costa Brava',
        category: 'viajes',
        tags: ['costa'],
        format: 'guia',
        cover: 'cala',
        publishedAt: '2026-03-10',
        featured: true,
        trip: 'costa-brava',
      }),
      article({
        id: 'serie-costa',
        slug: 'serie-costa',
        title: 'Serie fotográfica de costa',
        category: 'fotografia',
        tags: ['costa', 'fotografia-nocturna'],
        format: 'ensayo-fotografico',
        cover: 'puerto',
        publishedAt: '2026-02-01',
        featured: false,
      }),
      article({
        id: 'paso-stelvio',
        slug: 'paso-stelvio',
        title: 'Paso del Stelvio en moto',
        category: 'motor',
        tags: ['montana', 'motocicletas'],
        format: 'ficha',
        cover: 'moto',
        publishedAt: '2026-01-15',
        updatedAt: '2026-02-20',
        featured: true,
        trip: 'alpes',
      }),
      article({
        id: 'construir-revista',
        slug: 'construir-revista',
        title: 'Cómo se construye una revista',
        category: 'tecnologia',
        tags: ['angular'],
        format: 'reportaje',
        cover: 'codigo',
        publishedAt: '2026-04-01',
        featured: false,
        project: 'revista',
        seo: { description: 'Descripción SEO de prueba' },
      }),
      article({
        id: 'equipaje-ruta',
        slug: 'equipaje-ruta',
        title: 'Equipaje para una ruta en moto',
        category: 'experiencias',
        tags: ['motocicletas'],
        format: 'nota',
        cover: 'moto',
        publishedAt: '2025-12-20',
        featured: false,
      }),
    ],
  };
}

/** Origen en memoria que cuenta las cargas, para comprobar la carga perezosa. */
export function fixtureSource(collections = fixtureCollections(), bodies = FIXTURE_BODIES) {
  const loads = { collections: new Map<CollectionName, number>(), bodies: [] as string[] };
  const source: ContentSource = {
    async collection(name) {
      loads.collections.set(name, (loads.collections.get(name) ?? 0) + 1);
      return structuredClone(collections[name]);
    },
    async body(slug) {
      loads.bodies.push(slug);
      const body = bodies.find((b) => b.slug === slug);
      return body ? structuredClone(body) : null;
    },
  };
  return { source, loads };
}
