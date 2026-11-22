package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * La herramienta de seguridad (conexión real como cms_security_tool) cierra las sesiones vivas de
 * un usuario creadas por la API, sin poder leer sus identificadores ni sus atributos.
 */
class SessionRevocationByToolTests extends SecurityTestSupport {

  private static String userId(String email) throws Exception {
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT id FROM app_user WHERE email = ?")) {
      ps.setString(1, email);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static int attributeRows(String sessionId) throws Exception {
    try (Connection c = connectAs("cms_api");
        PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM spring_session_attributes a"
            + " JOIN spring_session s ON s.primary_id = a.session_primary_id WHERE s.session_id = ?")) {
      ps.setString(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  @Test
  void cierraLasSesionesDeUnUsuarioSinTocarLasDeOtro() throws Exception {
    String target = createUser(true, true);
    String other = createUser(true, true);
    ApiClient first = client();
    ApiClient second = client();
    ApiClient bystander = client();
    assertThat(first.login(target, PASSWORD).status()).isEqualTo(200);
    assertThat(second.login(target, PASSWORD).status()).isEqualTo(200);
    assertThat(bystander.login(other, PASSWORD).status()).isEqualTo(200);
    String firstId = sessionId(first.cookie(SecurityConfig.SESSION_COOKIE));
    String otherId = sessionId(bystander.cookie(SecurityConfig.SESSION_COOKIE));
    assertThat(attributeRows(firstId)).as("la sesión tiene atributos serializados").isPositive();

    String principal = userId(target);
    try (Connection c = connectAs("cms_security_tool")) {
      // Lo que no puede hacer, con sesiones reales: leer identificadores o atributos.
      for (String sql : new String[] {"SELECT session_id FROM spring_session WHERE principal_name = ?",
          "SELECT attribute_bytes FROM spring_session_attributes a JOIN spring_session s"
              + " ON s.primary_id = a.session_primary_id WHERE s.principal_name = ?",
          "DELETE FROM spring_session WHERE principal_name = ? RETURNING session_id"}) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
          ps.setString(1, principal);
          assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
              .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
      }
      // Lo que sí: cerrar todas las sesiones de ese usuario.
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM spring_session WHERE principal_name = ?")) {
        ps.setString(1, principal);
        assertThat(ps.executeUpdate()).isEqualTo(2);
      }
    }

    // Las cookies del usuario ya no valen; la del otro usuario, sí.
    assertThat(first.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(second.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(bystander.get("/api/auth/me").status()).isEqualTo(200);
    assertThat(sessionRows(firstId)).isZero();
    assertThat(attributeRows(firstId)).as("atributos borrados en cascada").isZero();
    assertThat(sessionRows(otherId)).isEqualTo(1);
  }
}
