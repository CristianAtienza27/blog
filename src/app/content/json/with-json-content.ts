import { AbstractType, Injectable, Injector, Provider, inject } from '@angular/core';
import { ContentFeature } from '../provide-content';
import { ArticleRepository } from '../repositories/article-repository';
import { AuthorRepository } from '../repositories/author-repository';
import { MediaRepository } from '../repositories/media-repository';
import { ProjectRepository } from '../repositories/project-repository';
import { TaxonomyRepository } from '../repositories/taxonomy-repository';
import { TravelRepository } from '../repositories/travel-repository';
import type { JsonAdapter } from './json-adapter';

/** Carga el adaptador una sola vez, la primera vez que se consulta un repositorio. */
@Injectable()
class JsonAdapterLoader {
  private readonly injector = inject(Injector);
  private adapter?: Promise<JsonAdapter>;

  load(): Promise<JsonAdapter> {
    this.adapter ??= import('./json-adapter')
      .then((module) => module.createJsonAdapter(this.injector))
      .catch((error: unknown) => {
        this.adapter = undefined;
        throw error;
      });
    return this.adapter;
  }
}

/** Lista de métodos de un repositorio: el compilador exige que estén todos. */
type Methods<T> = Record<keyof T, true>;

/**
 * Repositorio diferido: todos los métodos de los repositorios devuelven promesas,
 * así que cada llamada espera a que el adaptador esté cargado y la reenvía.
 * El bundle inicial solo incluye estos reenvíos y los contratos abstractos.
 */
function lazyRepository<T extends object>(
  token: AbstractType<T>,
  pick: (adapter: JsonAdapter) => T,
  methods: Methods<T>,
): Provider {
  return {
    provide: token,
    useFactory: () => {
      const loader = inject(JsonAdapterLoader);
      const forward =
        (method: string) =>
        async (...args: unknown[]) => {
          const repository = pick(await loader.load()) as Record<
            string,
            (...params: unknown[]) => unknown
          >;
          return repository[method](...args);
        };
      return Object.fromEntries(
        Object.keys(methods).map((method) => [method, forward(method)]),
      ) as T;
    },
  };
}

/** Adaptador de archivos JSON locales (src/content). */
export function withJsonContent(): ContentFeature {
  return {
    kind: 'json',
    providers: [
      JsonAdapterLoader,
      lazyRepository(ArticleRepository, (adapter) => adapter.articles, {
        list: true,
        bySlug: true,
        search: true,
        related: true,
        slugs: true,
      }),
      lazyRepository(TaxonomyRepository, (adapter) => adapter.taxonomy, {
        categories: true,
        category: true,
        tags: true,
        tag: true,
      }),
      lazyRepository(MediaRepository, (adapter) => adapter.media, {
        media: true,
        mediaList: true,
        galleries: true,
        gallery: true,
      }),
      lazyRepository(TravelRepository, (adapter) => adapter.travel, {
        trips: true,
        trip: true,
        destinations: true,
        destination: true,
        tripsByDestination: true,
      }),
      lazyRepository(ProjectRepository, (adapter) => adapter.projects, {
        list: true,
        bySlug: true,
      }),
      lazyRepository(AuthorRepository, (adapter) => adapter.authors, { list: true, byId: true }),
    ],
  };
}
