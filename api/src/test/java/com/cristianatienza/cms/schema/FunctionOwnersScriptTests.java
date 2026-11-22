package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.testsupport.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * D5: cms_enrollment_writer en volúmenes nuevos y ya inicializados. El script
 * cms-function-owners.psql es idempotente; sin el rol, V8 se detiene sin dejar nada a medias; tras
 * el procedimiento documentado, V8 migra. Las operaciones con superusuario son las del propio
 * procedimiento administrativo (excepcional), no las de la aplicación.
 */
class FunctionOwnersScriptTests extends PostgresSchemaTest {

  private static final String SCRIPT = "/docker-entrypoint-initdb.d/sql/cms-function-owners.psql";
  private static final String SNAPSHOT = "SELECT string_agg(r.rolname || ':' || r.rolcanlogin || r.rolsuper"
      + " || r.rolcreaterole || r.rolcreatedb || r.rolreplication || r.rolbypassrls || r.rolinherit"
      + " || ':' || coalesce((SELECT string_agg(u.rolname || '/' || m.inherit_option || m.set_option || m.admin_option, ',')"
      + " FROM pg_auth_members m JOIN pg_roles u ON u.oid = m.member WHERE m.roleid = r.oid), '-'), ' ' ORDER BY r.rolname)"
      + " FROM pg_roles r WHERE r.rolname IN ('cms_revision_writer', 'cms_import_writer', 'cms_audit_retention',"
      + " 'cms_enrollment_writer', 'cms_article_publisher')";

  @Test
  void elScriptEsIdempotente() throws Exception {
    String before = TestDatabase.superuserPsql("-At", "-c", SNAPSHOT);
    TestDatabase.superuserPsql("-f", SCRIPT);
    TestDatabase.superuserPsql("-f", SCRIPT);
    assertThat(TestDatabase.superuserPsql("-At", "-c", SNAPSHOT)).isEqualTo(before);
    assertThat(before).contains("cms_enrollment_writer:falsefalsefalsefalsefalsefalsetrue:cms_owner/truetruefalse");
    assertThat(before).contains("cms_article_publisher:falsefalsefalsefalsefalsefalsetrue:cms_owner/truetruefalse");
  }

  @Test
  void sinElRolV8SeDetieneSinCambiosYTrasElProcedimientoMigra() throws Exception {
    // Volumen «anterior a la 6.2.3»: hasta V7, y sin el rol (como si el script de inicio fuera el de antes).
    Flyway flyway = flyway();
    flyway.clean();
    TestDatabase.superuserPsql("-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql", "-v",
        "DBNAME=" + TestDatabase.POSTGRES.getDatabaseName());
    Flyway.configure().configuration(flyway.getConfiguration()).target("7").load().migrate();
    // Los roles son de todo el clúster: otros tests crean bases de datos con objetos del rol.
    for (String database : TestDatabase.superuserPsql("-At", "-c",
        "SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn").strip().split("\\R")) {
      TestDatabase.superuserPsql("-d" + database, "-c", "DROP OWNED BY cms_enrollment_writer");
    }
    TestDatabase.superuserPsql("-c", "DROP ROLE cms_enrollment_writer");

    assertThatThrownBy(flyway::migrate).hasMessageContaining("falta el rol cms_enrollment_writer")
        .hasMessageContaining("cms-function-owners.psql");
    // Revertida entera: sigue en la 7 y no existe nada de V8.
    assertThat(one(String.class, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("7");
    assertThat(one(Boolean.class, "SELECT to_regclass('public.passkey_credential') IS NULL")).isTrue();

    // Procedimiento documentado (infra/local/README.md) y migración.
    TestDatabase.superuserPsql("-f", SCRIPT);
    flyway.migrate();
    // Migra hasta la última (V11, fase 6.4.5).
    assertThat(one(String.class, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("11");
    assertThat(one(String.class, "SELECT proowner::regrole::text FROM pg_proc WHERE proname = 'complete_passkey_enrollment'"))
        .isEqualTo("cms_enrollment_writer");
  }

  @Test
  void sinElRolV11SeDetieneSinCambiosYTrasElProcedimientoMigra() throws Exception {
    // Volumen «anterior a la 6.4.5»: hasta V10, y sin cms_article_publisher.
    Flyway flyway = flyway();
    flyway.clean();
    TestDatabase.superuserPsql("-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql", "-v",
        "DBNAME=" + TestDatabase.POSTGRES.getDatabaseName());
    Flyway.configure().configuration(flyway.getConfiguration()).target("10").load().migrate();
    for (String database : TestDatabase.superuserPsql("-At", "-c",
        "SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn").strip().split("\\R")) {
      TestDatabase.superuserPsql("-d" + database, "-c", "DROP OWNED BY cms_article_publisher");
    }
    TestDatabase.superuserPsql("-c", "DROP ROLE cms_article_publisher");

    assertThatThrownBy(flyway::migrate).hasMessageContaining("falta el rol cms_article_publisher")
        .hasMessageContaining("cms-function-owners.psql");
    // Revertida entera: sigue en la 10 y no existe nada de V11.
    assertThat(one(String.class, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("10");
    assertThat(one(Boolean.class, "SELECT to_regprocedure('publish_article(text,bigint,integer)') IS NULL")).isTrue();

    TestDatabase.superuserPsql("-f", SCRIPT);
    flyway.migrate();
    assertThat(one(String.class, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success")).isEqualTo("11");
    assertThat(one(String.class, "SELECT proowner::regrole::text FROM pg_proc WHERE proname = 'publish_article'"))
        .isEqualTo("cms_article_publisher");
  }

  @Test
  void conUnMiembroDeMasV11SeDetiene() throws Exception {
    Flyway flyway = flyway();
    flyway.clean();
    TestDatabase.superuserPsql("-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql", "-v",
        "DBNAME=" + TestDatabase.POSTGRES.getDatabaseName());
    Flyway.configure().configuration(flyway.getConfiguration()).target("10").load().migrate();
    TestDatabase.superuserPsql("-c", "GRANT cms_article_publisher TO cms_api");
    try {
      assertThatThrownBy(flyway::migrate)
          .hasMessageContaining("cms_article_publisher solo puede tener a cms_owner como miembro");
    } finally {
      TestDatabase.superuserPsql("-c", "REVOKE cms_article_publisher FROM cms_api");
    }
  }

  @Test
  void conUnMiembroDeMasV8SeDetiene() throws Exception {
    Flyway flyway = flyway();
    flyway.clean();
    TestDatabase.superuserPsql("-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql", "-v",
        "DBNAME=" + TestDatabase.POSTGRES.getDatabaseName());
    Flyway.configure().configuration(flyway.getConfiguration()).target("7").load().migrate();
    TestDatabase.superuserPsql("-c", "GRANT cms_enrollment_writer TO cms_api");
    try {
      assertThatThrownBy(flyway::migrate).hasMessageContaining("solo puede tener a cms_owner como miembro");
    } finally {
      TestDatabase.superuserPsql("-c", "REVOKE cms_enrollment_writer FROM cms_api");
    }
  }
}
