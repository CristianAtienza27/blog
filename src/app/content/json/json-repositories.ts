import { Injectable, inject } from '@angular/core';
import { ContentCache, cacheKey } from '../content-cache';
import type {
  Author,
  Category,
  Destination,
  Gallery,
  Media,
  Project,
  SectionId,
  Tag,
  Trip,
} from '../models';
import { AuthorRepository } from '../repositories/author-repository';
import { MediaRepository } from '../repositories/media-repository';
import { ProjectRepository } from '../repositories/project-repository';
import { TaxonomyRepository } from '../repositories/taxonomy-repository';
import { DestinationQuery, TravelRepository } from '../repositories/travel-repository';
import { Catalog } from './catalog';
import { JsonContentStore } from './json-content-store';

/** Base común: consulta el catálogo y pasa el resultado por la caché de transferencia. */
abstract class CatalogQueries {
  private readonly store = inject(JsonContentStore);
  private readonly cache = inject(ContentCache);

  protected query<T>(key: string, params: object | undefined, read: (c: Catalog) => T) {
    return this.cache.get(cacheKey(key, params), async () => read(await this.store.catalog()));
  }
}

const bySlug = <T extends { slug: string }>(items: readonly T[], slug: string) =>
  items.find((item) => item.slug === slug) ?? null;

@Injectable()
export class JsonTaxonomyRepository extends CatalogQueries implements TaxonomyRepository {
  categories(): Promise<Category[]> {
    return this.query('taxonomy.categories', undefined, (c) => c.categories);
  }

  category(id: SectionId): Promise<Category | null> {
    return this.query('taxonomy.category', { id }, (c) => c.categoryById.get(id) ?? null);
  }

  tags(): Promise<Tag[]> {
    return this.query('taxonomy.tags', undefined, (c) =>
      [...c.tags].sort((a, b) => a.label.localeCompare(b.label, 'es')),
    );
  }

  tag(slug: string): Promise<Tag | null> {
    return this.query('taxonomy.tag', { slug }, (c) => c.tagBySlug.get(slug) ?? null);
  }
}

@Injectable()
export class JsonMediaRepository extends CatalogQueries implements MediaRepository {
  media(id: string): Promise<Media | null> {
    return this.query('media.byId', { id }, (c) => c.mediaById.get(id) ?? null);
  }

  mediaList(ids: readonly string[]): Promise<Media[]> {
    return this.query('media.list', { ids }, (c) =>
      ids.flatMap((id) => {
        const media = c.mediaById.get(id);
        return media ? [media] : [];
      }),
    );
  }

  galleries(): Promise<Gallery[]> {
    return this.query('media.galleries', undefined, (c) => c.galleries);
  }

  gallery(slug: string): Promise<Gallery | null> {
    return this.query('media.gallery', { slug }, (c) => bySlug(c.galleries, slug));
  }
}

@Injectable()
export class JsonTravelRepository extends CatalogQueries implements TravelRepository {
  trips(): Promise<Trip[]> {
    return this.query('travel.trips', undefined, (c) => c.trips);
  }

  trip(slug: string): Promise<Trip | null> {
    return this.query('travel.trip', { slug }, (c) => bySlug(c.trips, slug));
  }

  destinations(query: DestinationQuery = {}): Promise<Destination[]> {
    return this.query('travel.destinations', query, (c) =>
      c.destinations
        .filter((d) => !query.country || d.country === query.country.toUpperCase())
        .sort((a, b) => a.name.localeCompare(b.name, 'es')),
    );
  }

  destination(slug: string): Promise<Destination | null> {
    return this.query('travel.destination', { slug }, (c) => bySlug(c.destinations, slug));
  }

  tripsByDestination(slug: string): Promise<Trip[]> {
    return this.query('travel.tripsByDestination', { slug }, (c) =>
      c.trips.filter((trip) => trip.stops.some((stop) => stop.destination.slug === slug)),
    );
  }
}

@Injectable()
export class JsonProjectRepository extends CatalogQueries implements ProjectRepository {
  list(): Promise<Project[]> {
    return this.query('projects.list', undefined, (c) =>
      [...c.projects].sort((a, b) => b.year - a.year || a.name.localeCompare(b.name, 'es')),
    );
  }

  bySlug(slug: string): Promise<Project | null> {
    return this.query('projects.bySlug', { slug }, (c) => bySlug(c.projects, slug));
  }
}

@Injectable()
export class JsonAuthorRepository extends CatalogQueries implements AuthorRepository {
  list(): Promise<Author[]> {
    return this.query('authors.list', undefined, (c) => c.authors);
  }

  byId(id: string): Promise<Author | null> {
    return this.query('authors.byId', { id }, (c) => c.authorById.get(id) ?? null);
  }
}
