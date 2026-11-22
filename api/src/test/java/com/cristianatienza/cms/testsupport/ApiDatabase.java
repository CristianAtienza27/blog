package com.cristianatienza.cms.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Conexión de los tests de contexto de Spring: el esquema se migra antes, como el contenedor de
 * migraciones (cms_migrator), y la aplicación se conecta con el login real cms_api, sin Flyway.
 */
public final class ApiDatabase {

  private ApiDatabase() {}

  public static void register(DynamicPropertyRegistry registry) {
    TestDatabase.migrateFromScratch();
    registry.add("CMS_DB_URL", TestDatabase::url);
    registry.add("CMS_DB_USER", () -> "cms_api");
    registry.add("CMS_DB_PASSWORD", () -> TestDatabase.password("cms_api"));
  }
}
