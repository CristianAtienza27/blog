package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Caducidad por inactividad. En producción son 30 min (spring.session.timeout); aquí 2 s para no
 * esperar: lo que se comprueba es que Spring Session la aplica y la sesión deja de valer.
 */
@TestPropertySource(properties = "spring.session.timeout=2s")
class SessionIdleExpiryTests extends SecurityTestSupport {

  @Test
  void caducaTrasElTiempoSinUso() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    assertThat(client.get("/api/auth/me").status()).isEqualTo(200);
    Thread.sleep(3_000);
    assertThat(client.get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void laConfiguracionDeProduccionEsDeTreintaMinutos() throws Exception {
    var yaml = new String(getClass().getResourceAsStream("/application.yaml").readAllBytes());
    assertThat(yaml).contains("timeout: 30m").contains("absolute-timeout: 12h");
  }
}
