import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, ResolveFn } from '@angular/router';
import type { Article, SectionId } from '../../content/models';
import { ArticleRepository } from '../../content/repositories/article-repository';
import { PUBLIC_SITE_URL } from '../../core/config/site-config';
import { MEDIA_BASE_URL } from '../../core/media/media-delivery';
import type { SeoData } from '../../core/seo/seo';
import { ArticlePageData, articlePresentation } from './article-presentation';
import { articleSeo } from './article-seo';

export const NOT_FOUND_TITLE = 'Página no encontrada';

/**
 * El artículo de la URL, solo si pertenece a la sección de la ruta: /motor/<slug de viajes>
 * no existe. Llamar al principio del resolver (usa inject). El repositorio guarda el
 * resultado: los tres resolvers comparten una sola carga, también al hidratar.
 */
function findArticle(route: ActivatedRouteSnapshot): Promise<Article | null> {
  const repository = inject(ArticleRepository);
  const slug = route.paramMap.get('slug') ?? '';
  const section = route.data['section'] as SectionId;
  return repository
    .bySlug(slug)
    .then((article) => (article?.category.id === section ? article : null));
}

/** Artículo completo y su cabecera; null si no existe o es de otra sección (404). */
export const articleResolver: ResolveFn<ArticlePageData | null> = async (route) => {
  const article = await findArticle(route);
  return article ? articlePresentation(article) : null;
};

export const articleTitleResolver: ResolveFn<string> = async (route) =>
  (await findArticle(route))?.title ?? NOT_FOUND_TITLE;

/** Descripción, Open Graph, metadatos y datos estructurados del artículo; el 404 no se indexa. */
export const articleSeoResolver: ResolveFn<SeoData> = async (route) => {
  const base = inject(MEDIA_BASE_URL);
  const siteUrl = inject(PUBLIC_SITE_URL);
  const article = await findArticle(route);
  if (!article) return { noindex: true };
  return articleSeo(article, articlePresentation(article).header, base, siteUrl);
};
