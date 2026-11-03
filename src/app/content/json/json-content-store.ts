import { Injectable, inject } from '@angular/core';
import type { Article } from '../models';
import { ContentValidationError } from '../records/integrity';
import { COLLECTION_NAMES, ContentCollections } from '../records/records';
import { validateArticleBody, validateCollection } from '../records/validate';
import { Catalog, buildCatalog, resolveArticle } from './catalog';
import { CONTENT_SOURCE } from './content-source';

/** En desarrollo se valida la forma de cada archivo al cargarlo; en producción no se incluye. */
const validating = typeof ngDevMode === 'undefined' || !!ngDevMode;

/**
 * Carga perezosa del contenido JSON. El catálogo (metadatos) se carga una vez,
 * al primer uso; el cuerpo de cada artículo, solo cuando se pide ese artículo.
 */
@Injectable()
export class JsonContentStore {
  private readonly source = inject(CONTENT_SOURCE);
  private catalogRequest?: Promise<Catalog>;

  catalog(): Promise<Catalog> {
    this.catalogRequest ??= this.loadCatalog().catch((error: unknown) => {
      // Permite reintentar si falla la descarga de un chunk.
      this.catalogRequest = undefined;
      throw error;
    });
    return this.catalogRequest;
  }

  async article(slug: string): Promise<Article | null> {
    const catalog = await this.catalog();
    if (!catalog.articleRecords.has(slug)) return null;
    const body = await this.source.body(slug);
    if (!body) throw new Error(`Falta el cuerpo del artículo «${slug}»`);
    if (validating) {
      const issues: string[] = [];
      validateArticleBody(`articles/${slug}.json`, body, issues);
      if (issues.length) throw new ContentValidationError(issues);
    }
    return resolveArticle(catalog, body);
  }

  private async loadCatalog(): Promise<Catalog> {
    const entries = await Promise.all(
      COLLECTION_NAMES.map(async (name) => [name, await this.source.collection(name)] as const),
    );
    const data = Object.fromEntries(entries) as unknown as ContentCollections;
    if (validating) {
      const issues: string[] = [];
      for (const name of COLLECTION_NAMES) validateCollection(name, data[name], issues);
      if (issues.length) throw new ContentValidationError(issues);
    }
    return buildCatalog(data);
  }
}
