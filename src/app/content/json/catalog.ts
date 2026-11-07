import type {
  Article,
  ArticleSummary,
  Author,
  Category,
  ContentBlock,
  Destination,
  DestinationRef,
  Gallery,
  MapPoint,
  Media,
  Project,
  SectionId,
  Tag,
  Trip,
} from '../models';
import type {
  ArticleBodyRecord,
  ArticleRecord,
  ContentBlockRecord,
  ContentCollections,
  MediaRecord,
} from '../records/records';

/**
 * Contenido resuelto e indexado en memoria. Convierte los registros (con identificadores)
 * en modelos de dominio (con las relaciones resueltas).
 */
export interface Catalog {
  categories: Category[];
  categoryById: Map<SectionId, Category>;
  tags: Tag[];
  tagBySlug: Map<string, Tag>;
  authors: Author[];
  authorById: Map<string, Author>;
  mediaById: Map<string, Media>;
  galleries: Gallery[];
  galleryById: Map<string, Gallery>;
  destinations: Destination[];
  destinationById: Map<string, Destination>;
  trips: Trip[];
  tripById: Map<string, Trip>;
  projects: Project[];
  projectById: Map<string, Project>;
  /** Más recientes primero. */
  articles: ArticleSummary[];
  articleBySlug: Map<string, ArticleSummary>;
  articleRecords: Map<string, ArticleRecord>;
}

function index<K, T>(items: readonly T[], key: (item: T) => K): Map<K, T> {
  return new Map(items.map((item) => [key(item), item]));
}

/** Busca una referencia obligatoria; si falta, el contenido está roto. */
function required<K, T>(map: ReadonlyMap<K, T>, id: K, where: string): T {
  const value = map.get(id);
  if (value === undefined) throw new Error(`Referencia rota en ${where}: «${String(id)}»`);
  return value;
}

function optionalRef<T>(map: ReadonlyMap<string, T>, id: string | undefined, where: string) {
  return id === undefined ? undefined : required(map, id, where);
}

/** El original solo sirve para recuperar la fuente: no forma parte del dominio. */
function toMedia(record: MediaRecord): Media {
  const media: Media & { original?: unknown } = { ...record };
  delete media.original;
  return media;
}

/** Referencia ligera para los listados: sin portada ni descripción. */
function toRef(d: Destination): DestinationRef {
  return {
    id: d.id,
    slug: d.slug,
    name: d.name,
    country: d.country,
    ...(d.region ? { region: d.region } : {}),
    location: d.location,
    ...(d.elevation === undefined ? {} : { elevation: d.elevation }),
    demo: d.demo,
  };
}

/** Paradas del itinerario seguidas de los destinos propios, sin repetir. */
function articleDestinations(
  record: ArticleRecord,
  tripById: ReadonlyMap<string, Trip>,
  destinationById: ReadonlyMap<string, Destination>,
): DestinationRef[] {
  const where = `artículo ${record.id}`;
  const stops = optionalRef(tripById, record.trip, where)?.stops.map((s) => s.destination) ?? [];
  const own = (record.destinations ?? []).map((id) => required(destinationById, id, where));
  return [...new Map([...stops, ...own].map((d) => [d.id, toRef(d)])).values()];
}

/** Compara fechas ISO y, a igualdad, títulos. */
export function newestFirst(a: ArticleSummary, b: ArticleSummary): number {
  return b.publishedAt.localeCompare(a.publishedAt) || a.title.localeCompare(b.title, 'es');
}

export function buildCatalog(data: ContentCollections): Catalog {
  const categories = data.categories;
  const categoryById = index(categories, (c) => c.id);
  const tags = data.tags;
  const tagById = index(tags, (t) => t.id);
  const mediaById = index(data.media.map(toMedia), (m) => m.id);

  const authors: Author[] = data.authors.map(({ avatar, ...author }) => ({
    ...author,
    ...(avatar ? { avatar: required(mediaById, avatar, `autor ${author.id}`) } : {}),
  }));
  const authorById = index(authors, (a) => a.id);

  const galleries: Gallery[] = data.galleries.map((g) => ({
    ...g,
    items: g.items.map((item) => ({
      ...item,
      media: required(mediaById, item.media, `galería ${g.id}`),
    })),
  }));
  const galleryById = index(galleries, (g) => g.id);

  const destinations: Destination[] = data.destinations.map(({ cover, ...d }) => ({
    ...d,
    ...(cover ? { cover: required(mediaById, cover, `destino ${d.id}`) } : {}),
  }));
  const destinationById = index(destinations, (d) => d.id);

  const trips: Trip[] = data.trips.map(({ cover, stops, ...t }) => ({
    ...t,
    ...(cover ? { cover: required(mediaById, cover, `itinerario ${t.id}`) } : {}),
    stops: [...stops]
      .sort((a, b) => a.order - b.order)
      .map((stop) => ({
        ...stop,
        destination: required(destinationById, stop.destination, `itinerario ${t.id}`),
      })),
  }));
  const tripById = index(trips, (t) => t.id);

  const projects: Project[] = data.projects.map(({ cover, stack, ...p }) => ({
    ...p,
    ...(cover ? { cover: required(mediaById, cover, `proyecto ${p.id}`) } : {}),
    stack: stack.map((tag) => required(tagById, tag, `proyecto ${p.id}`)),
  }));
  const projectById = index(projects, (p) => p.id);

  const articles: ArticleSummary[] = data.articles
    .map((a) => ({
      id: a.id,
      slug: a.slug,
      title: a.title,
      dek: a.dek,
      excerpt: a.excerpt,
      category: required(categoryById, a.category, `artículo ${a.id}`),
      tags: a.tags.map((tag) => required(tagById, tag, `artículo ${a.id}`)),
      format: a.format,
      cover: required(mediaById, a.cover, `artículo ${a.id}`),
      author: required(authorById, a.author, `artículo ${a.id}`),
      publishedAt: a.publishedAt,
      ...(a.updatedAt ? { updatedAt: a.updatedAt } : {}),
      readingMinutes: a.readingMinutes,
      featured: a.featured,
      destinations: articleDestinations(a, tripById, destinationById),
      demo: a.demo,
    }))
    .sort(newestFirst);

  return {
    categories,
    categoryById,
    tags,
    tagBySlug: index(tags, (t) => t.slug),
    authors,
    authorById,
    mediaById,
    galleries,
    galleryById,
    destinations,
    destinationById,
    trips,
    tripById,
    projects,
    projectById,
    articles,
    articleBySlug: index(articles, (a) => a.slug),
    articleRecords: index(data.articles, (a) => a.slug),
  };
}

function mapPoints(catalog: Catalog, block: ContentBlockRecord & { type: 'map' }, where: string) {
  const destinations = block.trip
    ? required(catalog.tripById, block.trip, where).stops.map((stop) => stop.destination)
    : (block.destinations ?? []).map((id) => required(catalog.destinationById, id, where));
  return destinations.map((d): MapPoint => ({
    label: d.name,
    location: d.location,
    slug: d.slug,
    destination: d,
  }));
}

function resolveBlock(catalog: Catalog, block: ContentBlockRecord, where: string): ContentBlock {
  switch (block.type) {
    case 'image':
      return { ...block, media: required(catalog.mediaById, block.media, where) };
    case 'figures':
      return {
        ...block,
        items: block.items.map((item) => ({
          ...item,
          media: required(catalog.mediaById, item.media, where),
        })),
      };
    case 'gallery':
      return { type: 'gallery', gallery: required(catalog.galleryById, block.gallery, where) };
    case 'map':
      return {
        type: 'map',
        points: mapPoints(catalog, block, where),
        route: block.route,
        ...(block.caption ? { caption: block.caption } : {}),
        ...(block.trip ? { trip: required(catalog.tripById, block.trip, where) } : {}),
      };
    default:
      return block;
  }
}

/** Artículo completo: resumen del catálogo + cuerpo + relaciones. */
export function resolveArticle(catalog: Catalog, body: ArticleBodyRecord): Article | null {
  const summary = catalog.articleBySlug.get(body.slug);
  const record = catalog.articleRecords.get(body.slug);
  if (!summary || !record) return null;
  const where = `artículo ${record.id}`;
  return {
    ...summary,
    blocks: body.blocks.map((block) => resolveBlock(catalog, block, where)),
    ...(record.trip ? { trip: optionalRef(catalog.tripById, record.trip, where) } : {}),
    ...(record.project ? { project: optionalRef(catalog.projectById, record.project, where) } : {}),
    ...(record.seo ? { seo: record.seo } : {}),
  };
}
