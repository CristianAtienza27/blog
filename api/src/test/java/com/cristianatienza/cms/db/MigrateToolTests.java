package com.cristianatienza.cms.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.content.io.ContentTool;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * El contenedor de migraciones: perfil «migrate», conexión con cms_migrator (sin privilegios
 * propios) y SET ROLE cms_owner en las conexiones de Flyway. Se ejecuta en el mismo proceso con
 * cms.tool.system-exit=false para leer su código de salida sin terminar la JVM de los tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class MigrateToolTests {

  private static int runMigrate(String url) {
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CmsApplication.class)
        .profiles("migrate")
        .properties(
            "CMS_DB_URL=" + url,
            "CMS_DB_USER=cms_migrator",
            "CMS_DB_PASSWORD=" + TestDatabase.password("cms_migrator"),
            "cms.tool.system-exit=false")
        .run()) {
      return context.getBean(ContentTool.class).lastExitCode();
    }
  }

  private static List<String> column(String url, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (var c = DriverManager.getConnection(url, TestDatabase.POSTGRES.getUsername(), TestDatabase.POSTGRES.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  @Test
  void migraComoPropietarioSinAvisosYEsIdempotente(CapturedOutput output) throws Exception {
    String url = TestDatabase.prepareDatabase("migrate_tool");

    assertThat(runMigrate(url)).isZero();
    assertThat(output.getOut()).contains("Migraciones aplicadas. Versión del esquema: " + SchemaVersionCheck.expectedVersion());
    // Flyway reconoce PostgreSQL 18 sin avisos de versión no soportada.
    assertThat(output.getAll())
        .doesNotContainIgnoringCase("newer than this version of Flyway")
        .doesNotContainIgnoringCase("not been tested")
        .doesNotContainIgnoringCase("Unsupported Database");
    // Todo lo creado pertenece a cms_owner, también el historial; nada a cms_migrator.
    assertThat(column(url, "SELECT DISTINCT pg_get_userbyid(relowner) FROM pg_class"
        + " WHERE relnamespace = 'public'::regnamespace")).containsExactly("cms_owner");
    assertThat(column(url, "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'flyway_schema_history'"))
        .containsExactly("cms_owner");

    // Una segunda ejecución no tiene nada pendiente ni añade filas al historial.
    List<String> before = column(url, "SELECT count(*) FROM flyway_schema_history");
    assertThat(before).containsExactly(SchemaVersionCheck.expectedVersion().getVersion());
    assertThat(runMigrate(url)).isZero();
    assertThat(column(url, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(before);
  }
}
