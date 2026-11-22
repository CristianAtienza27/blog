package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Cerrado por defecto (decisión G3), con los controladores de PRODUCCIÓN: ningún endpoint sin
 * política. Los de autenticación están todos en la matriz de EndpointProtectionTests; cualquier
 * otro debe estar bajo /api/cms y declarar @CmsEditor o @CmsAdmin. Que el comprobador detecta un
 * olvido se demuestra en AuthorizationPolicyTests, con un controlador de pruebas sin anotación.
 */
class EndpointPolicyArchitectureTests extends SecurityTestSupport {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  void ningunEndpointDeProduccionSinPolitica() {
    assertThat(EndpointPolicy.violations(mappings.getHandlerMethods())).isEmpty();
  }
}
