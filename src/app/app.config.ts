import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
} from '@angular/router';
import { routes } from './app.routes';
import { withJsonContent } from './content/json/with-json-content';
import { provideContent } from './content/provide-content';
import { provideMediaDelivery } from './core/media/media-delivery';
import { SeoTitleStrategy } from './core/seo/seo-title-strategy';
import { withPageTransitions } from './motion/view-transitions';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
      withPageTransitions(),
    ),
    { provide: TitleStrategy, useClass: SeoTitleStrategy },
    provideClientHydration(withEventReplay()),
    // Contenido: JSON locales. Con la API de Spring Boot bastará con cambiar el adaptador.
    provideContent(withJsonContent()),
    // Imágenes: loader de NgOptimizedImage y origen de las versiones (/media o Azure).
    provideMediaDelivery(),
  ],
};
