import { mergeApplicationConfig, ApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { providePublicSiteUrl } from './core/config/site-config';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    // Dominio público para canónicas y Open Graph (vacío = no se generan).
    providePublicSiteUrl(process.env['PUBLIC_SITE_URL']),
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
