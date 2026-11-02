import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { PUBLIC_SITE_URL, SITE_LOCALE, SITE_NAME } from '../config/site-config';

export interface SeoData {
  /** Título de la página sin el nombre del sitio. Vacío = solo el nombre del sitio. */
  title?: string;
  description?: string;
  /** Imagen para redes sociales (absoluta o relativa al dominio público). */
  image?: string;
  type?: 'website' | 'article';
  /** Excluye la página de los buscadores (páginas provisionales, 404, desarrollo). */
  noindex?: boolean;
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

    this.setName('twitter:card', image ? 'summary_large_image' : 'summary');
    this.setName('twitter:title', title);
    this.setName('twitter:description', description);
    this.setName('twitter:image', image);

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
