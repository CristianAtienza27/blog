import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { providePublicSiteUrl } from './core/config/site-config';
import { provideMediaBaseUrl } from './core/media/media-delivery';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    // Dominio público para canónicas y Open Graph (vacío = no se generan).
    providePublicSiteUrl(process.env['PUBLIC_SITE_URL']),
    // Origen de las imágenes: /media por defecto; en producción, Azure Blob Storage o su CDN.
    provideMediaBaseUrl(process.env['MEDIA_BASE_URL']),
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
