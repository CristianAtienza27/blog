import { inject } from '@angular/core';
import type { ResolveFn } from '@angular/router';
import type { ArticleSummary, SectionId } from '../../../content/models';
import { ArticleRepository, MAX_PAGE_SIZE } from '../../../content/repositories/article-repository';

/** Lo que sigue al artículo: otros que leer y los vecinos de su sección. */
export interface ArticleClosing {
  related: ArticleSummary[];
  /** Publicado antes en la misma sección (null si es el primero). */
  previous: ArticleSummary | null;
  /** Publicado después en la misma sección (null si es el último). */
  next: ArticleSummary | null;
}

/** Cuántos relacionados se muestran como mucho. */
export const RELATED_LIMIT = 3;

/**
 * Vecinos en el orden editorial de la sección (`order`, nunca fechas) y relacionados sin
 * repetir: nunca el propio artículo ni el anterior o el siguiente (ya están en la navegación).
 * La navegación no sale de la sección del artículo, aunque la lista traiga otros.
 */
export function articleClosing(
  slug: string,
  section: readonly ArticleSummary[],
  related: readonly ArticleSummary[],
): ArticleClosing {
  const current = section.find((a) => a.slug === slug);
  const ordered = section
    .filter((a) => a.category.id === current?.category.id)
    .sort((a, b) => a.order - b.order || a.slug.localeCompare(b.slug));
  const at = ordered.findIndex((a) => a.slug === slug);
  const previous = at > 0 ? ordered[at - 1] : null;
  const next = at >= 0 && at < ordered.length - 1 ? ordered[at + 1] : null;
  const shown = new Set([slug, previous?.slug, next?.slug]);
  const picked: ArticleSummary[] = [];
  for (const article of related) {
    if (shown.has(article.slug)) continue;
    shown.add(article.slug);
    picked.push(article);
    if (picked.length === RELATED_LIMIT) break;
  }
  return { related: picked, previous, next };
}

/** Cierre del artículo de la URL (null si no existe o es de otra sección: lo resuelve el 404). */
export const articleClosingResolver: ResolveFn<ArticleClosing | null> = async (route) => {
  const repository = inject(ArticleRepository);
  const slug = route.paramMap.get('slug') ?? '';
  const section = route.data['section'] as SectionId;
  const article = await repository.bySlug(slug);
  if (article?.category.id !== section) return null;
  const [page, related] = await Promise.all([
    repository.list({ category: section, limit: MAX_PAGE_SIZE }),
    repository.related(slug, RELATED_LIMIT + 2),
  ]);
  return articleClosing(slug, page.items, related);
};
