package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * IP del cliente con la configuración por defecto (sin proxy de confianza): X-Forwarded-For se
 * ignora y cuenta la IP del socket. Un atacante no puede cambiar de «IP» con una cabecera.
 */
class ClientIpTests extends SecurityTestSupport {

  @Test
  void sinProxyDeConfianzaLaCabeceraSeIgnora() throws Exception {
    String email = createUser(true, true);
    for (int i = 0; i < 5; i++) {
      // Cada intento dice venir de otra IP, pero es el mismo socket: la misma pareja.
      assertThat(client().from("198.18.0." + (i + 1)).login(email, "mal").status()).isEqualTo(401);
    }
    assertThat(client().from("198.18.0.99").login(email, PASSWORD).status()).isEqualTo(429);
    assertThat(query("cms_security_tool", "SELECT DISTINCT host(i.ip) FROM audit_event e JOIN audit_event_ip i"
        + " ON i.event_id = e.id WHERE e.event = 'login.password_failed' AND e.user_id = ?", userIdOf(email)))
        .containsExactly("127.0.0.1");
  }
}
