import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { PUBLIC_SITE_URL, SITE_LOCALE, SITE_NAME } from '../config/site-config';

export interface SeoData {
  /** Título de la página sin el nombre del sitio. Vacío = solo el nombre del sitio. */
  title?: string;
  description?: string;
  /** Imagen para redes sociales (absoluta o relativa al dominio público). */
  image?: string;
  /** Texto alternativo y medidas en píxeles de la imagen. */
  imageAlt?: string;
  imageWidth?: number;
  imageHeight?: number;
  type?: 'website' | 'article';
  /** Metadatos Open Graph de artículo (article:*). */
  article?: ArticleMeta;
  /** Excluye la página de los buscadores (páginas provisionales, 404, desarrollo). */
  noindex?: boolean;
}

export interface ArticleMeta {
  /** Fechas ISO: solo en contenido definitivo, nunca fechas de demostración. */
  publishedTime?: string;
  modifiedTime?: string;
  section?: string;
  tags?: string[];
}

export const DEFAULT_DESCRIPTION =
  'Revista personal de Cristian Atienza: viajes, fotografía, motor, experiencias y tecnología.';

/** Gestión centralizada de título, descripción, canónica, Open Graph y Twitter. */
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly document = inject(DOCUMENT);
  private readonly siteUrl = inject(PUBLIC_SITE_URL);

  update(data: SeoData, path: string): void {
    const title = data.title ? `${data.title} · ${SITE_NAME}` : SITE_NAME;
    const description = data.description ?? DEFAULT_DESCRIPTION;
    const url = this.absoluteUrl(path);
    const image = data.image ? this.absoluteUrl(data.image) : null;

    this.title.setTitle(title);
    this.setName('description', description);
    this.setName('robots', data.noindex ? 'noindex, follow' : null);

    this.setProperty('og:site_name', SITE_NAME);
    this.setProperty('og:locale', SITE_LOCALE);
    this.setProperty('og:type', data.type ?? 'website');
    this.setProperty('og:title', title);
    this.setProperty('og:description', description);
    this.setProperty('og:url', url);
    this.setProperty('og:image', image);
    this.setProperty('og:image:alt', image ? (data.imageAlt ?? null) : null);
    this.setProperty('og:image:width', image ? dimension(data.imageWidth) : null);
    this.setProperty('og:image:height', image ? dimension(data.imageHeight) : null);
    this.setArticle(data.type === 'article' ? data.article : undefined);

    this.setName('twitter:card', image ? 'summary_large_image' : 'summary');
    this.setName('twitter:title', title);
    this.setName('twitter:description', description);
    this.setName('twitter:image', image);
    this.setName('twitter:image:alt', image ? (data.imageAlt ?? null) : null);

    this.setCanonical(data.noindex ? null : url);
  }

  /** URL absoluta solo si hay dominio público configurado (o si ya es absoluta). */
  private absoluteUrl(path: string): string | null {
    if (/^https?:\/\//.test(path)) return path;
    if (!this.siteUrl) return null;
    const clean = path.split(/[?#]/)[0];
    return this.siteUrl + (clean === '/' ? '/' : clean.replace(/\/+$/, ''));
  }

  private setName(name: string, content: string | null): void {
    if (content) this.meta.updateTag({ name, content });
    else this.meta.removeTag(`name="${name}"`);
  }

  private setProperty(property: string, content: string | null): void {
    if (content) this.meta.updateTag({ property, content });
    else this.meta.removeTag(`property="${property}"`);
  }

  /** article:*. Las etiquetas pueden ser varias: se sustituyen todas en cada página. */
  private setArticle(article: ArticleMeta | undefined): void {
    this.setProperty('article:published_time', article?.publishedTime ?? null);
    this.setProperty('article:modified_time', article?.modifiedTime ?? null);
    this.setProperty('article:section', article?.section ?? null);
    for (const tag of this.meta.getTags('property="article:tag"')) this.meta.removeTagElement(tag);
    this.meta.addTags(
      (article?.tags ?? []).map((content) => ({ property: 'article:tag', content })),
    );
  }

  private setCanonical(url: string | null): void {
    let link = this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!url) {
      link?.remove();
      return;
    }
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.document.head.appendChild(link);
    }
    link.setAttribute('href', url);
  }
}

/** Medida en píxeles como texto; nada si no es válida. */
function dimension(value: number | undefined): string | null {
  return value && value > 0 ? String(Math.round(value)) : null;
}
