import type { Article } from '../../content/models';
import { articleUrl } from '../../editorial/article-meta';
import { SECTIONS } from '../../layout/navigation';

/** Datos estructurados (Schema.org) listos para serializar en un script JSON-LD. */
export type JsonLd = Record<string, unknown>;

/** Fecha real AAAA-MM-DD (existe en el calendario). */
export function isRealDate(value: string | undefined): value is string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
}

/** URL absoluta http(s) válida, o null. */
function absolute(url: string): string | null {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null;
  } catch {
    return null;
  }
}

export interface ArticleJsonLdOptions {
  /** Dominio público (PUBLIC_SITE_URL ya normalizado), sin barra final. */
  siteUrl: string | null;
  /** Imagen para redes de la portada (absoluta o relativa al dominio), si la hay. */
  image?: string;
}

/**
 * BlogPosting y BreadcrumbList de un artículo, solo si es publicable de verdad: no es de
 * demostración, tiene fecha real de publicación, hay dominio público y URL canónica válida, y
 * el autor viene del catálogo. Si falta cualquiera de esos datos, null (no se inventa nada:
 * ni fechas, ni autores, ni editorial, ni imágenes, ni dominios).
 */
export function articleJsonLd(article: Article, options: ArticleJsonLdOptions): JsonLd | null {
  const { siteUrl } = options;
  if (article.demo || !siteUrl || !isRealDate(article.publishedAt)) return null;
  const author = article.author?.name?.trim();
  if (!article.author?.id || !author) return null;
  const canonical = absolute(siteUrl + articleUrl(article));
  const home = absolute(`${siteUrl}/`);
  const section = SECTIONS.find((s) => s.id === article.category.id);
  const sectionUrl = section && absolute(siteUrl + section.path);
  if (!canonical || !home || !sectionUrl) return null;

  const image = options.image
    ? absolute(/^https?:\/\//.test(options.image) ? options.image : siteUrl + options.image)
    : null;
  const modified =
    article.updatedAt && isRealDate(article.updatedAt) && article.updatedAt !== article.publishedAt
      ? article.updatedAt
      : null;
  const keywords = article.tags.map((tag) => tag.label);

  return {
    '@context': 'https://schema.org',
    '@graph': [
      {
        '@type': 'BlogPosting',
        '@id': `${canonical}#articulo`,
        headline: article.title,
        description: article.seo?.description ?? article.dek,
        author: { '@type': 'Person', name: author },
        datePublished: article.publishedAt,
        ...(modified ? { dateModified: modified } : {}),
        ...(image ? { image } : {}),
        mainEntityOfPage: { '@type': 'WebPage', '@id': canonical },
        url: canonical,
        articleSection: article.category.name,
        ...(keywords.length ? { keywords } : {}),
      },
      {
        '@type': 'BreadcrumbList',
        itemListElement: [
          { '@type': 'ListItem', position: 1, name: 'Inicio', item: home },
          { '@type': 'ListItem', position: 2, name: section.label, item: sectionUrl },
          { '@type': 'ListItem', position: 3, name: article.title, item: canonical },
        ],
      },
    ],
  };
}
