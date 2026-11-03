import { Injector } from '@angular/core';
import { JsonArticleRepository } from './json-article-repository';
import { JsonContentStore } from './json-content-store';
import {
  JsonAuthorRepository,
  JsonMediaRepository,
  JsonProjectRepository,
  JsonTaxonomyRepository,
  JsonTravelRepository,
} from './json-repositories';

/** Implementación completa del adaptador JSON. Se carga en un chunk diferido. */
export function createJsonAdapter(parent: Injector) {
  const injector = Injector.create({
    parent,
    providers: [
      JsonContentStore,
      JsonArticleRepository,
      JsonTaxonomyRepository,
      JsonMediaRepository,
      JsonTravelRepository,
      JsonProjectRepository,
      JsonAuthorRepository,
    ],
  });
  return {
    articles: injector.get(JsonArticleRepository),
    taxonomy: injector.get(JsonTaxonomyRepository),
    media: injector.get(JsonMediaRepository),
    travel: injector.get(JsonTravelRepository),
    projects: injector.get(JsonProjectRepository),
    authors: injector.get(JsonAuthorRepository),
  };
}

export type JsonAdapter = ReturnType<typeof createJsonAdapter>;
