import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, ResolveFn } from '@angular/router';
import type { ArticleSummary, Category, SectionId } from '../../content/models';
import { ArticleRepository } from '../../content/repositories/article-repository';
import type { SeoData } from '../../core/seo/seo';

/** Lo que muestra la página provisional de un artículo. */
export interface ArticlePreviewData {
  title: string;
  dek: string;
  category: Category;
  demo: boolean;
}

export const NOT_FOUND_TITLE = 'Página no encontrada';

/**
 * El artículo de la URL, solo si pertenece a la sección de la ruta: /motor/<slug de viajes>
 * no existe. Llamar al principio del resolver (usa inject).
 */
function findArticle(route: ActivatedRouteSnapshot): Promise<ArticleSummary | null> {
  const repository = inject(ArticleRepository);
  const slug = route.paramMap.get('slug') ?? '';
  const section = route.data['section'] as SectionId;
  return repository
    .bySlug(slug)
    .then((article) => (article?.category.id === section ? article : null));
}

/** null si no existe o es de otra sección: la página responde 404. */
export const articleResolver: ResolveFn<ArticlePreviewData | null> = async (route) => {
  const article = await findArticle(route);
  return article
    ? { title: article.title, dek: article.dek, category: article.category, demo: article.demo }
    : null;
};

export const articleTitleResolver: ResolveFn<string> = async (route) =>
  (await findArticle(route))?.title ?? NOT_FOUND_TITLE;

/** Siempre noindex mientras el contenido sea provisional (y en el 404). */
export const articleSeoResolver: ResolveFn<SeoData> = async (route) => {
  const article = await findArticle(route);
  return article ? { description: article.dek, type: 'article', noindex: true } : { noindex: true };
};
