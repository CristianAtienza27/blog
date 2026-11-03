import { SECTION_IDS } from '../models';
import { readingMinutes } from './reading-time';
import type { ArticleBodyRecord, ContentCollections } from './records';
import { validateArticleBody, validateCollection } from './validate';
import { COLLECTION_NAMES } from './records';

export interface IntegrityOptions {
  /** Imágenes usadas fuera del contenido (por ejemplo, el menú). */
  externalMedia?: readonly string[];
}

/** Error con todas las incidencias encontradas, una por línea. */
export class ContentValidationError extends Error {
  constructor(readonly issues: readonly string[]) {
    super(`Contenido no válido (${issues.length}):\n- ${issues.join('\n- ')}`);
    this.name = 'ContentValidationError';
  }
}

function duplicates(values: readonly string[]): string[] {
  const seen = new Set<string>();
  return [...new Set(values.filter((value) => seen.size === seen.add(value).size))];
}

/**
 * Comprueba las relaciones entre colecciones: referencias existentes, identificadores
 * únicos, orden de las paradas, cuerpos de artículo, tiempo de lectura e imágenes
 * sin usar o duplicadas.
 */
export function checkIntegrity(
  data: ContentCollections,
  bodies: ReadonlyMap<string, ArticleBodyRecord>,
  options: IntegrityOptions = {},
): string[] {
  const issues: string[] = [];
  const ids = <T extends { id: string }>(items: readonly T[]) => new Set(items.map((i) => i.id));
  const media = ids(data.media);
  const tags = ids(data.tags);
  const galleries = ids(data.galleries);
  const destinations = ids(data.destinations);
  const trips = ids(data.trips);
  const projects = ids(data.projects);
  const authors = ids(data.authors);
  const usedMedia = new Set(options.externalMedia ?? []);

  const ref = (set: Set<string>, id: string | undefined, where: string, kind: string) => {
    if (id !== undefined && !set.has(id)) issues.push(`${where}: ${kind} inexistente «${id}»`);
  };
  const useMedia = (id: string | undefined, where: string) => {
    if (id === undefined) return;
    ref(media, id, where, 'imagen');
    usedMedia.add(id);
  };

  // Identificadores y slugs únicos en cada colección.
  for (const name of COLLECTION_NAMES) {
    const items = data[name] as readonly { id: string; slug?: string }[];
    for (const id of duplicates(items.map((i) => i.id)))
      issues.push(`${name}: id repetido «${id}»`);
    const slugs = items.flatMap((i) => (i.slug ? [i.slug] : []));
    for (const slug of duplicates(slugs)) issues.push(`${name}: slug repetido «${slug}»`);
  }

  // Las categorías son exactamente las secciones de la revista.
  const categoryIds = data.categories.map((c) => c.id);
  if (categoryIds.join() !== SECTION_IDS.join()) {
    issues.push(`categories: deben ser ${SECTION_IDS.join(', ')}, en ese orden`);
  }

  // Imágenes repetidas (mismo original).
  for (const sha of duplicates(data.media.map((m) => m.original.sha256))) {
    issues.push(`media: original duplicado (sha256 ${sha.slice(0, 12)}…)`);
  }
  for (const source of duplicates(data.media.flatMap((m) => m.credit.sourceUrl ?? []))) {
    issues.push(`media: fuente duplicada ${source}`);
  }

  // Las fotografías propias se atribuyen a un autor de la revista.
  const authorNames = new Set(data.authors.map((a) => a.name));
  data.media.forEach((m, i) => {
    if (m.credit.source === 'propia' && !authorNames.has(m.credit.author)) {
      issues.push(`media[${i}].credit.author: «${m.credit.author}» no es un autor de la revista`);
    }
  });

  data.authors.forEach((a, i) => useMedia(a.avatar, `authors[${i}].avatar`));
  data.galleries.forEach((g, i) =>
    g.items.forEach((item, j) => useMedia(item.media, `galleries[${i}].items[${j}]`)),
  );
  data.destinations.forEach((d, i) => useMedia(d.cover, `destinations[${i}].cover`));
  data.projects.forEach((p, i) => {
    useMedia(p.cover, `projects[${i}].cover`);
    p.stack.forEach((t, j) => ref(tags, t, `projects[${i}].stack[${j}]`, 'etiqueta'));
  });
  data.trips.forEach((t, i) => {
    useMedia(t.cover, `trips[${i}].cover`);
    t.stops.forEach((stop, j) => {
      ref(destinations, stop.destination, `trips[${i}].stops[${j}]`, 'destino');
      if (stop.order !== j + 1) {
        issues.push(`trips[${i}].stops[${j}]: orden ${stop.order}, se esperaba ${j + 1}`);
      }
    });
  });

  // Artículos y sus cuerpos.
  const articleSlugs = new Set(data.articles.map((a) => a.slug));
  for (const slug of bodies.keys()) {
    if (!articleSlugs.has(slug)) issues.push(`articles/${slug}.json: cuerpo sin artículo`);
  }
  data.articles.forEach((a, i) => {
    const where = `articles[${i}]`;
    a.tags.forEach((t, j) => ref(tags, t, `${where}.tags[${j}]`, 'etiqueta'));
    ref(authors, a.author, `${where}.author`, 'autor');
    ref(trips, a.trip, `${where}.trip`, 'itinerario');
    ref(projects, a.project, `${where}.project`, 'proyecto');
    useMedia(a.cover, `${where}.cover`);
    if (a.updatedAt && a.updatedAt < a.publishedAt) {
      issues.push(`${where}.updatedAt: anterior a la publicación`);
    }

    const body = bodies.get(a.slug);
    if (!body) {
      issues.push(`${where}: falta el cuerpo articles/${a.slug}.json`);
      return;
    }
    if (body.slug !== a.slug) issues.push(`articles/${a.slug}.json: slug «${body.slug}» distinto`);

    const chapters: string[] = [];
    body.blocks.forEach((block, j) => {
      const at = `articles/${a.slug}.json.blocks[${j}]`;
      switch (block.type) {
        case 'image':
          useMedia(block.media, at);
          break;
        case 'gallery':
          ref(galleries, block.gallery, at, 'galería');
          break;
        case 'map':
          ref(trips, block.trip, at, 'itinerario');
          block.destinations?.forEach((d) => ref(destinations, d, at, 'destino'));
          break;
        case 'chapter':
          chapters.push(block.id);
          break;
      }
    });
    for (const id of duplicates(chapters))
      issues.push(`articles/${a.slug}.json: capítulo repetido «${id}»`);

    const minutes = readingMinutes(body.blocks);
    if (a.readingMinutes !== minutes) {
      issues.push(`${where}.readingMinutes: ${a.readingMinutes}, calculado ${minutes}`);
    }
  });

  for (const m of data.media) {
    if (!usedMedia.has(m.id)) issues.push(`media: imagen sin usar «${m.id}»`);
  }

  return issues;
}

/**
 * Validación completa: primero la forma de cada colección y cuerpo; si es correcta,
 * las relaciones. Devuelve la lista de incidencias (vacía si todo es válido).
 */
export function validateContent(
  raw: Record<keyof ContentCollections, unknown>,
  rawBodies: ReadonlyMap<string, unknown>,
  options?: IntegrityOptions,
): string[] {
  const issues: string[] = [];
  for (const name of COLLECTION_NAMES) validateCollection(name, raw[name], issues);
  for (const [slug, body] of rawBodies) validateArticleBody(`articles/${slug}.json`, body, issues);
  if (issues.length) return issues;
  return checkIntegrity(
    raw as unknown as ContentCollections,
    rawBodies as ReadonlyMap<string, ArticleBodyRecord>,
    options,
  );
}
