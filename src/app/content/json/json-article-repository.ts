import { Injectable, inject } from '@angular/core';
import { ContentCache, cacheKey } from '../content-cache';
import type { Article, ArticleSummary, Page } from '../models';
import {
  ArticleQuery,
  ArticleRepository,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
} from '../repositories/article-repository';
import { SearchDocument, buildIndex, search } from '../search/search';
import { Catalog, newestFirst } from './catalog';
import { JsonContentStore } from './json-content-store';

/** Recorta una lista a la página pedida, con límites seguros. */
export function paginate<T>(items: readonly T[], query: ArticleQuery = {}): Page<T> {
  const offset = Math.max(0, Math.trunc(query.offset ?? 0));
  const limit = Math.min(MAX_PAGE_SIZE, Math.max(1, Math.trunc(query.limit ?? DEFAULT_PAGE_SIZE)));
  return { items: items.slice(offset, offset + limit), total: items.length, offset, limit };
}

/** Aplica los filtros de la consulta (sin ordenar ni paginar). */
export function filterArticles(
  catalog: Catalog,
  articles: readonly ArticleSummary[],
  query: ArticleQuery,
): ArticleSummary[] {
  return articles.filter((article) => {
    const record = catalog.articleRecords.get(article.slug);
    const trip = record?.trip ? catalog.tripById.get(record.trip) : undefined;
    const project = record?.project ? catalog.projectById.get(record.project) : undefined;
    return (
      (!query.category || article.category.id === query.category) &&
      (!query.tag || article.tags.some((tag) => tag.slug === query.tag)) &&
      (!query.format || article.format === query.format) &&
      (query.featured === undefined || article.featured === query.featured) &&
      (!query.trip || trip?.slug === query.trip) &&
      (!query.destination || article.destinations.some((d) => d.slug === query.destination)) &&
      (!query.project || project?.slug === query.project)
    );
  });
}

function searchDocuments(catalog: Catalog): SearchDocument[] {
  return catalog.articles.map((article) => {
    const record = catalog.articleRecords.get(article.slug);
    const trip = record?.trip ? catalog.tripById.get(record.trip) : undefined;
    const project = record?.project ? catalog.projectById.get(record.project) : undefined;
    const related = [
      trip?.title,
      ...article.destinations.map((d) => d.name),
      project?.name,
      ...(project?.stack.map((tag) => tag.label) ?? []),
    ];
    return {
      id: article.slug,
      fields: [
        { text: article.title, weight: 5 },
        { text: [article.category.name, ...article.tags.map((t) => t.label)].join(' '), weight: 3 },
        { text: related.filter(Boolean).join(' '), weight: 3 },
        { text: `${article.dek} ${article.excerpt}`, weight: 2 },
      ],
    };
  });
}

@Injectable()
export class JsonArticleRepository extends ArticleRepository {
  private readonly store = inject(JsonContentStore);
  private readonly cache = inject(ContentCache);
  private index?: { catalog: Catalog; documents: ReturnType<typeof buildIndex> };

  list(query: ArticleQuery = {}): Promise<Page<ArticleSummary>> {
    return this.cache.get(cacheKey('articles.list', query), async () => {
      const catalog = await this.store.catalog();
      const items = filterArticles(catalog, catalog.articles, query);
      if (query.sort === 'oldest') items.reverse();
      return paginate(items, query);
    });
  }

  bySlug(slug: string): Promise<Article | null> {
    return this.cache.get(cacheKey('articles.bySlug', { slug }), () => this.store.article(slug));
  }

  search(term: string, query: ArticleQuery = {}): Promise<Page<ArticleSummary>> {
    return this.cache.get(cacheKey('articles.search', { ...query, term }), async () => {
      const catalog = await this.store.catalog();
      if (this.index?.catalog !== catalog) {
        this.index = { catalog, documents: buildIndex(searchDocuments(catalog)) };
      }
      const found = search(this.index.documents, term).flatMap((hit) => {
        const article = catalog.articleBySlug.get(hit.id);
        return article ? [article] : [];
      });
      const items = filterArticles(catalog, found, query);
      if (query.sort) {
        items.sort(newestFirst);
        if (query.sort === 'oldest') items.reverse();
      }
      return paginate(items, query);
    });
  }

  related(slug: string, limit = 3): Promise<ArticleSummary[]> {
    return this.cache.get(cacheKey('articles.related', { slug, limit }), async () => {
      const catalog = await this.store.catalog();
      const base = catalog.articleBySlug.get(slug);
      if (!base) return [];
      const baseTags = new Set(base.tags.map((tag) => tag.id));
      return catalog.articles
        .filter((article) => article.slug !== slug)
        .map((article) => ({
          article,
          score:
            (article.category.id === base.category.id ? 3 : 0) +
            article.tags.filter((tag) => baseTags.has(tag.id)).length * 2,
        }))
        .filter(({ score }) => score > 0)
        .sort((a, b) => b.score - a.score || newestFirst(a.article, b.article))
        .slice(0, Math.max(0, limit))
        .map(({ article }) => article);
    });
  }

  async slugs(): Promise<string[]> {
    const catalog = await this.store.catalog();
    return catalog.articles.map((article) => article.slug);
  }
}
