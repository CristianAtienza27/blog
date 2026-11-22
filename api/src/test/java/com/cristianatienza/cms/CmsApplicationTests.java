package com.cristianatienza.cms;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.db.SchemaVersionCheck;
import com.cristianatienza.cms.testsupport.ApiDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arranque completo contra un PostgreSQL 18 real (la misma imagen que en infra/local), con el
 * login real de la API (cms_api) y el esquema migrado antes por cms_migrator.
 */
@SpringBootTest
class CmsApplicationTests {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    ApiDatabase.register(registry);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationContext context;

  @Test
  void conectaConPostgreSql18ComoCmsApi() {
    assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("18.");
    assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("cms_api");
    assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
  }

  @Test
  void laApiNoEjecutaFlywayYCompruebaLaVersionDelEsquema() {
    // Sin Flyway en el contexto: la API no puede migrar ni limpiar el esquema.
    assertThat(context.getBeanProvider(Flyway.class).getIfAvailable()).isNull();
    assertThat(context.getBeansOfType(SchemaVersionCheck.class)).hasSize(1);
  }
}
