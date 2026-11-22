package com.cristianatienza.cms.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** La API no migra: arranca solo si el esquema está exactamente en la versión de su código. */
class SchemaVersionCheckTests {

  private static ConfigurableApplicationContext startApi(String url) {
    return new SpringApplicationBuilder(CmsApplication.class)
        .web(WebApplicationType.NONE)
        .properties("CMS_DB_URL=" + url, "CMS_DB_USER=cms_api", "CMS_DB_PASSWORD=" + TestDatabase.password("cms_api"))
        .run();
  }

  @Test
  void laVersionEsperadaEsLaUltimaMigracionDelCodigo() {
    assertThat(SchemaVersionCheck.expectedVersion().getVersion()).isEqualTo("11");
  }

  @Test
  void conElEsquemaAlDiaArranca() throws Exception {
    String url = TestDatabase.prepareDatabase("version_ok");
    TestDatabase.migrator(url).migrate();
    try (var context = startApi(url)) {
      assertThat(context.isRunning()).isTrue();
    }
  }

  @Test
  void conUnaMigracionPendienteNoArrancaNiMigra() throws Exception {
    String url = TestDatabase.prepareDatabase("version_antigua");
    Flyway.configure().configuration(TestDatabase.migrator(url).getConfiguration()).target("4").load().migrate();

    // Antes de V5, cms_api ni siquiera puede leer el historial: tampoco arranca.
    assertThatThrownBy(() -> startApi(url))
        .hasStackTraceContaining("el esquema es anterior a V5 o faltan sus permisos");
    // La API no ha aplicado la migración pendiente.
    try (var c = DriverManager.getConnection(url, TestDatabase.POSTGRES.getUsername(), TestDatabase.POSTGRES.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery("SELECT max(version::int) FROM flyway_schema_history")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(4);
    }
  }

  @Test
  void sinHistorialDeMigracionesNoArranca() throws Exception {
    String url = TestDatabase.prepareDatabase("version_vacia");
    assertThatThrownBy(() -> startApi(url)).hasStackTraceContaining("la base de datos no tiene migraciones");
  }

  @Test
  void conUnEsquemaMasNuevoQueElCodigoNoArranca() throws Exception {
    String url = TestDatabase.prepareDatabase("version_nueva");
    TestDatabase.migrator(url).migrate();
    try (var c = DriverManager.getConnection(url, "cms_migrator", TestDatabase.password("cms_migrator"));
        var s = c.createStatement()) {
      s.execute("SET ROLE cms_owner");
      s.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum,"
          + " installed_by, execution_time, success) VALUES (99, '99', 'futura', 'SQL', 'V99__futura.sql', 0,"
          + " 'cms_owner', 0, true)");
    }
    assertThatThrownBy(() -> startApi(url)).hasStackTraceContaining("el esquema está en la versión 99 y este código espera la 11");
  }
}
