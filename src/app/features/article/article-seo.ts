import type { Article } from '../../content/models';
import { mediaUrl, pickWidth } from '../../core/media/media-delivery';
import type { SeoData } from '../../core/seo/seo';
import { articleJsonLd } from './article-jsonld';
import type { ArticleHeaderData } from './article-presentation';

/**
 * La página de artículo está completa (fases 5.1 a 5.9: cabecera, relato, fotografías,
 * galerías, mapas, índice y cierre). Un artículo real se puede indexar; el contenido de
 * demostración no se indexa nunca, con o sin este indicador.
 */
export const ARTICLE_PAGE_COMPLETE = true;

/** Ancho de la imagen para redes sociales: la versión más pequeña que cubre 1200 px. */
const SOCIAL_IMAGE_WIDTH = 1200;

/**
 * SEO de un artículo: descripción, imagen para Open Graph (con medidas y texto alternativo),
 * metadatos article:* y, si es indexable, datos estructurados (JSON-LD). Las fechas solo se
 * publican en contenido definitivo, y el de demostración nunca se indexa.
 */
export function articleSeo(
  article: Article,
  header: ArticleHeaderData,
  mediaBase: string,
  siteUrl: string | null = null,
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
  if (!seo.noindex) {
    const jsonLd = articleJsonLd(article, { siteUrl, image: seo.image });
    if (jsonLd) seo.jsonLd = jsonLd;
  }
  return seo;
}
