package com.cristianatienza.cms.db;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * La API y las herramientas no migran (fase 6.2.1): al arrancar comprueban que el esquema está
 * exactamente en la última versión que incluye su código. Si no, se niegan a arrancar y piden
 * ejecutar el contenedor de migraciones. Solo lee {@code flyway_schema_history} (SELECT
 * concedido en V5); el contenedor de migraciones no hace esta comprobación.
 */
@Component
@ConditionalOnExpression("'${cms.tool:}' != 'migrate'")
public class SchemaVersionCheck implements InitializingBean {

  private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.+\\.sql");

  private final JdbcTemplate jdbc;

  public SchemaVersionCheck(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public void afterPropertiesSet() {
    MigrationVersion expected = expectedVersion();
    MigrationVersion actual;
    try {
      Integer failed = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
      if (failed != null && failed > 0) {
        throw new SchemaVersionException("hay migraciones fallidas en flyway_schema_history");
      }
      String version = jdbc.query(
          "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL"
              + " ORDER BY installed_rank DESC LIMIT 1",
          rs -> rs.next() ? rs.getString(1) : null);
      actual = version == null ? MigrationVersion.EMPTY : MigrationVersion.fromVersion(version);
    } catch (DataAccessException e) {
      String state = sqlState(e);
      String hint = state.endsWith("42501")
          ? ": sin permiso de lectura, el esquema es anterior a V5 o faltan sus permisos"
          : state.endsWith("42P01") ? ": la base de datos no tiene migraciones" : "";
      throw new SchemaVersionException("no se puede leer el historial de migraciones (" + state + ")" + hint
          + " (ejecuta el contenedor de migraciones)", e);
    }
    if (actual.compareTo(expected) != 0) {
      throw new SchemaVersionException("el esquema está en la versión " + actual + " y este código espera la "
          + expected + " (ejecuta el contenedor de migraciones)");
    }
  }

  /** La última versión de las migraciones incluidas en el código (classpath:db/migration). */
  static MigrationVersion expectedVersion() {
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
      return Arrays.stream(resources)
          .map(Resource::getFilename)
          .filter(Objects::nonNull)
          .map(VERSIONED::matcher)
          .filter(Matcher::matches)
          .map(m -> MigrationVersion.fromVersion(m.group(1).replace('_', '.')))
          .max(Comparator.naturalOrder())
          .orElseThrow(() -> new SchemaVersionException("no hay migraciones en el classpath"));
    } catch (IOException e) {
      throw new SchemaVersionException("no se pueden leer las migraciones del classpath", e);
    }
  }

  private static String sqlState(DataAccessException e) {
    return e.getMostSpecificCause() instanceof SQLException sql ? "SQLSTATE " + sql.getSQLState() : e.getMessage();
  }

  /** El esquema no está en la versión esperada: la aplicación no debe arrancar. */
  public static class SchemaVersionException extends IllegalStateException {
    SchemaVersionException(String message) {
      super("Esquema no válido: " + message);
    }

    SchemaVersionException(String message, Throwable cause) {
      super("Esquema no válido: " + message, cause);
    }
  }
}
