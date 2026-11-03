import { EnvironmentProviders, Provider, makeEnvironmentProviders } from '@angular/core';

/** Adaptador de contenido: implementa todos los repositorios para un origen de datos. */
export interface ContentFeature {
  kind: 'json' | 'http';
  providers: Provider[];
}

/**
 * Registra la capa de contenido con el adaptador elegido.
 *
 *   provideContent(withJsonContent())          // ahora: JSON locales
 *   provideContent(withHttpContent({ … }))     // futuro: API REST de Spring Boot
 *
 * Los componentes solo inyectan los repositorios abstractos, así que cambiar de
 * adaptador no les afecta.
 */
export function provideContent(feature: ContentFeature): EnvironmentProviders {
  return makeEnvironmentProviders(feature.providers);
}
