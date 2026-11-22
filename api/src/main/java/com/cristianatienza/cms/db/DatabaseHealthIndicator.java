package com.cristianatienza.cms.db;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Estado de PostgreSQL para /actuator/health y readiness (6.2.7.2), con un tiempo máximo. Sustituye
 * al de Spring Boot (mismo nombre de bean, «db»), que valida con {@code Connection.isValid(0)}: sin
 * límite, con PostgreSQL detenido esperaba hasta el socketTimeout del driver (30 s, medido).
 *
 * <p>Como mucho connection-timeout (5 s) para obtener la conexión más {@value #VALIDATION_SECONDS} s
 * para validarla. Una conexión que no responde se expulsa del pool: Hikari entrega sin validar las
 * que se usaron hace menos de 500 ms, y la siguiente petición no debe recibirla.
 */
@Component("dbHealthIndicator")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DatabaseHealthIndicator implements HealthIndicator {

  /** Igual que spring.datasource.hikari.validation-timeout (2 s). */
  static final int VALIDATION_SECONDS = 2;

  private final DataSource dataSource;

  public DatabaseHealthIndicator(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Health health() {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.isValid(VALIDATION_SECONDS)) {
        return Health.up().build();
      }
      evict(connection);
      return Health.down().build();
    } catch (SQLException | RuntimeException e) {
      // Sin detalles: ni la excepción ni su mensaje (show-details: never).
      return Health.down().build();
    }
  }

  private void evict(Connection connection) {
    try {
      if (dataSource.isWrapperFor(HikariDataSource.class)) {
        dataSource.unwrap(HikariDataSource.class).evictConnection(connection);
      }
    } catch (SQLException ignored) {
      // Si no se puede expulsar, Hikari la retirará al fallar su siguiente uso.
    }
  }
}
