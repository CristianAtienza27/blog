import { Injectable, inject } from '@angular/core';
import { ActivatedRouteSnapshot, RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { SeoData, SeoService } from './seo';

/**
 * Aplica el SEO de cada ruta en cada navegación (también en SSR).
 * Título: propiedad `title` de la ruta. Resto: `data.seo` de la ruta más profunda.
 * Las páginas dinámicas (artículos) llamarán a SeoService.update() con sus datos.
 */
@Injectable()
export class SeoTitleStrategy extends TitleStrategy {
  private readonly seo = inject(SeoService);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    let route: ActivatedRouteSnapshot = snapshot.root;
    while (route.firstChild) route = route.firstChild;

    const data = (route.data['seo'] ?? {}) as SeoData;
    this.seo.update({ ...data, title: this.buildTitle(snapshot) ?? data.title }, snapshot.url);
  }
}
