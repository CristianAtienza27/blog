package com.cristianatienza.cms.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Qué cuenta como «base de datos no disponible» (503, 6.2.7.2) y qué no (500). */
class ApiProblemsTests {

  @Test
  void baseDeDatosNoDisponible() {
    // Pool agotado (Hikari) tal como lo envuelve Spring, y envuelto otra vez (auditoría, sesión).
    var pool = new CannotGetJdbcConnectionException("x", new SQLTransientConnectionException("timeout"));
    assertThat(ApiProblems.databaseUnavailable(pool)).isTrue();
    assertThat(ApiProblems.databaseUnavailable(new RuntimeException("auditoría", pool))).isTrue();
    assertThat(ApiProblems.databaseUnavailable(new CannotCreateTransactionException("x"))).isTrue();
    assertThat(ApiProblems.databaseUnavailable(new DataAccessResourceFailureException("x"))).isTrue();
    // Spring Session al guardar la sesión mientras PostgreSQL se reinicia (medido en la 6.2.7.4): el
    // rollback falla porque Hikari ya cerró la conexión, sin SQLSTATE.
    var rollback = new org.springframework.transaction.TransactionSystemException("JDBC rollback failed",
        new SQLException("Connection is closed"));
    assertThat(ApiProblems.databaseUnavailable(rollback)).isTrue();
    // socketTimeout o conexión rota (08006), servidor deteniéndose (57P01), no admite conexiones (57P03).
    for (String state : new String[] {"08006", "08001", "57P01", "57P03"}) {
      assertThat(ApiProblems.databaseUnavailable(new IllegalStateException(new SQLException("x", state)))).as(state)
          .isTrue();
    }
  }

  /**
   * La respuesta directa de 503 se escribe aunque otra respuesta ya hubiera abierto el flujo binario
   * (petición en curso cuando PostgreSQL deja de responder: Spring MVC empieza a escribir y falla al
   * guardar la sesión). Antes acababa en IllegalStateException y en la página 503 de Tomcat.
   */
  @Test
  void respuestaDirectaConElFlujoBinarioYaAbierto() throws Exception {
    var mock = new org.springframework.mock.web.MockHttpServletResponse();
    var response = new jakarta.servlet.http.HttpServletResponseWrapper(mock) {
      boolean binary;

      @Override
      public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
        binary = true;
        return super.getOutputStream();
      }

      @Override
      public java.io.PrintWriter getWriter() throws java.io.IOException {
        if (binary) {
          throw new IllegalStateException("getOutputStream() has already been called for this response");
        }
        return super.getWriter();
      }
    };
    response.getOutputStream().write("{\"parcial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    response.resetBuffer();
    ApiProblems.writeUnavailable(new org.springframework.mock.web.MockHttpServletRequest(), response);
    assertThat(mock.getStatus()).isEqualTo(503);
    assertThat(mock.getHeader("Retry-After")).isEqualTo("5");
    assertThat(mock.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).startsWith("{\"title\":")
        .contains("\"code\":\"unavailable\"").doesNotContain("parcial");
  }

  @Test
  void loDemasSigueSiendo500() {
    assertThat(ApiProblems.databaseUnavailable(new IllegalStateException("x"))).isFalse();
    assertThat(ApiProblems.databaseUnavailable(new DataIntegrityViolationException("x"))).isFalse();
    assertThat(ApiProblems.databaseUnavailable(new SQLException("x", "23505"))).isFalse();
    assertThat(ApiProblems.databaseUnavailable(new SQLException("x", "42501"))).isFalse();
    assertThat(ApiProblems.databaseUnavailable(new SQLException("sin estado"))).isFalse();
    // Un fallo de transacción por otra causa (violación de unicidad al confirmar) sigue siendo 500.
    assertThat(ApiProblems.databaseUnavailable(new org.springframework.transaction.TransactionSystemException("x",
        new SQLException("duplicado", "23505")))).isFalse();
  }
}
