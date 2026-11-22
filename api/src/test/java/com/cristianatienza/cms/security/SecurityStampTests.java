package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.springframework.session.Session;

/**
 * Sello de seguridad en cada petición (H7): una sesión sin sello (anterior a la 6.2.6) o con un
 * sello que ya no es el de la cuenta se cierra en su primera petición, con su fila, y queda
 * auditado; las sesiones de otros usuarios no se ven afectadas.
 */
class SecurityStampTests extends SecurityTestSupport {

  @Test
  @SuppressWarnings("unchecked")
  void sesionSinSelloSeCierra() throws Exception {
    Account account = fullAccount();
    String id = sessionId(account.client().cookie(SecurityConfig.SESSION_COOKIE));
    Session session = (Session) sessions.findById(id);
    assertThat((Object) session.getAttribute(AuthStates.SECURITY_STAMP)).as("la sesión guarda el sello").isNotNull();
    session.removeAttribute(AuthStates.SECURITY_STAMP);
    sessions.save(session);

    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
    assertThat(sessionRows(id)).isZero();
    assertThat(query("cms_security_tool", "SELECT count(*) FROM audit_event WHERE event = 'session.stale_stamp'"
        + " AND user_id = ?", userIdOf(account.email()))).containsExactly("1");
  }

  @Test
  void cambioDelSelloCierraTodasLasSesionesDelUsuarioYSoloLasSuyas() throws Exception {
    Account target = fullAccount();
    ApiClient p2 = passwordSession(target.email());
    Account bystander = fullAccount();
    // Lo que hace la recuperación con el sello (como cms_security_tool, sin tocar las sesiones).
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("UPDATE app_user SET password_changed_at = now() WHERE email = ?")) {
      ps.setString(1, target.email());
      assertThat(ps.executeUpdate()).isOne();
    }
    assertThat(target.client().get("/api/auth/me").status()).isEqualTo(401);
    assertThat(p2.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(query("cms_security_tool", "SELECT count(*) FROM spring_session WHERE principal_name = ?",
        userIdOf(target.email()).toString())).containsExactly("0");
    assertThat(bystander.client().get("/api/auth/me").status()).isEqualTo(200);
  }
}
