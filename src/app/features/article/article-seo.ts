import type { Article } from '../../content/models';
import { mediaUrl, pickWidth } from '../../core/media/media-delivery';
import type { SeoData } from '../../core/seo/seo';
import type { ArticleHeaderData } from './article-presentation';

/**
 * La página de artículo aún no está completa: faltan las fotografías del relato, galerías,
 * mapas y módulos de cierre (fases 5.3 a 5.7). Hasta entonces no se indexa, sea o no
 * contenido de demostración.
 */
export const ARTICLE_PAGE_COMPLETE = false;

/** Ancho de la imagen para redes sociales: la versión más pequeña que cubre 1200 px. */
const SOCIAL_IMAGE_WIDTH = 1200;

/**
 * SEO de un artículo: descripción, imagen para Open Graph (con medidas y texto alternativo)
 * y metadatos article:*. Las fechas solo se publican en contenido definitivo, y el de
 * demostración nunca se indexa.
 */
export function articleSeo(
  article: Article,
  header: ArticleHeaderData,
  mediaBase: string,
): SeoData {
  const cover = header.cover?.media;
  const seo: SeoData = {
    description: article.seo?.description ?? article.dek,
    type: 'article',
    noindex: article.demo || !ARTICLE_PAGE_COMPLETE,
    article: {
      section: article.category.name,
      tags: article.tags.map((tag) => tag.label),
      ...(article.demo
        ? {}
        : {
            publishedTime: article.publishedAt,
            ...(article.updatedAt ? { modifiedTime: article.updatedAt } : {}),
          }),
    },
  };
  if (cover) {
    const width = pickWidth(cover.widths, SOCIAL_IMAGE_WIDTH);
    seo.image = mediaUrl(mediaBase, cover.path, width, 'webp');
    seo.imageAlt = cover.alt;
    seo.imageWidth = width;
    seo.imageHeight = Math.round((width * cover.height) / cover.width);
  }
  return seo;
}
