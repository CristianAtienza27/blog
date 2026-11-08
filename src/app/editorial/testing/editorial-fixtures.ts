import type { ArticleSummary, Destination, DestinationRef, Media } from '../../content/models';

/** Modelos de dominio de prueba para los componentes editoriales (sin pasar por el contenido). */

export function photo(id: string, overrides: Partial<Media> = {}): Media {
  return {
    id,
    path: `viajes/${id}.0a1b2c3d`,
    width: 1600,
    height: 1067,
    widths: [640, 1024, 1600],
    alt: `Fotografía de prueba ${id}`,
    focal: { x: 0.5, y: 0.5 },
    placeholder: 'data:image/webp;base64,UklGRhIAAABXRUJQVlA4TAYAAAAvAAAAAAfQ//73v/+BiOh/AAA=',
    credit: {
      author: 'Autoría de prueba',
      source: 'wikimedia',
      sourceUrl: `https://commons.wikimedia.org/wiki/File:${id}.jpg`,
      license: 'CC-BY-SA-4.0',
      licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/',
    },
    demo: true,
    ...overrides,
  };
}

export function destination(
  id: string,
  name: string,
  overrides: Partial<Destination> = {},
): Destination {
  return {
    id,
    slug: id,
    name,
    country: 'ES',
    region: 'Girona',
    location: { lat: 42, lng: 3 },
    summary: `Destino de prueba ${name}`,
    demo: true,
    ...overrides,
  };
}

export function ref(d: Destination): DestinationRef {
  return {
    id: d.id,
    slug: d.slug,
    name: d.name,
    country: d.country,
    ...(d.region ? { region: d.region } : {}),
    location: d.location,
    demo: d.demo,
  };
}

/** Resumen de prueba con portada (como las piezas de la home); overrides puede quitarla. */
export function summary(
  slug: string,
  overrides: Partial<ArticleSummary> = {},
): ArticleSummary & { cover: Media } {
  return {
    id: slug,
    slug,
    title: `Artículo ${slug}`,
    dek: `Entradilla de ${slug}`,
    excerpt: `Extracto de ${slug}`,
    category: { id: 'viajes', slug: 'viajes', name: 'Viajes', description: 'Viajes' },
    tags: [],
    format: 'guia',
    cover: photo(`${slug}-portada`),
    author: { id: 'autor', name: 'Autoría de prueba', role: 'Autor' },
    order: 1,
    publishedAt: '2026-01-01',
    readingMinutes: 2,
    featured: false,
    destinations: [],
    demo: true,
    ...overrides,
  };
}
