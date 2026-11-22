package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import com.cristianatienza.cms.testsupport.TestDatabase;
import org.testcontainers.containers.Container.ExecResult;

/** Migraciones Flyway reproducibles desde una base de datos vacía, en PostgreSQL 18.6. */
class MigrationTests extends PostgresSchemaTest {

  @Test
  void lasMigracionesSeAplicanDesdeCeroYValidan() throws Exception {
    Flyway flyway = flyway();
    List<MigrationInfo> applied = List.of(flyway.info().applied());
    assertThat(applied).extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    assertThat(applied).allMatch(m -> m.getState() == MigrationState.SUCCESS);
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(one(String.class, "SHOW server_version")).startsWith("18.6");
  }

  @Test
  void elModeloTieneSusDieciochoTablasYLasDeSeguridadYPublicacion() throws Exception {
    // Publicación (V10, fase 6.3.4): historial, época y resultado de la reconciliación.
    assertThat(column("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename LIKE 'content_publication%'"
        + " ORDER BY tablename")).containsExactly("content_publication", "content_publication_check", "content_publication_lease");
    List<String> tables = column(
        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"
            + " AND tablename NOT LIKE 'content_publication%'"
            + " AND tablename NOT IN ('app_user', 'spring_session', 'spring_session_attributes', 'webauthn_user_handle', 'passkey_credential',"
            + " 'webauthn_challenge', 'webauthn_assertion', 'recovery_code', 'auth_session_failure',"
            + " 'audit_event', 'audit_event_ip', 'login_attempt', 'trusted_device', 'trusted_device_presentation') ORDER BY tablename");
    // Seguridad: usuarios y sesiones (V7), passkeys y recuperación (V8), auditoría, intentos y dispositivos (V9).
    assertThat(column("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
        + " AND tablename IN ('app_user', 'spring_session', 'spring_session_attributes', 'webauthn_user_handle', 'passkey_credential',"
            + " 'webauthn_challenge', 'webauthn_assertion', 'recovery_code', 'auth_session_failure',"
            + " 'audit_event', 'audit_event_ip', 'login_attempt', 'trusted_device', 'trusted_device_presentation') ORDER BY tablename"))
        .containsExactly("app_user", "audit_event", "audit_event_ip", "auth_session_failure", "login_attempt",
            "passkey_credential", "recovery_code", "spring_session", "spring_session_attributes",
            "trusted_device", "trusted_device_presentation", "webauthn_assertion", "webauthn_challenge",
            "webauthn_user_handle");
    assertThat(tables).containsExactly(
        "article", "article_revision", "article_revision_block_ref", "article_revision_destination",
        "article_revision_tag", "author", "category", "destination", "gallery", "gallery_item", "media",
        "media_original", "media_reserved_use", "project", "project_stack_item", "tag", "trip", "trip_stop");
  }

  @Test
  void ningunaExtensionSalvoPlpgsql() throws Exception {
    assertThat(column("SELECT extname FROM pg_extension ORDER BY extname")).containsExactly("plpgsql");
  }

  @Test
  void dosMigracionesDesdeCeroProducenElMismoEsquema() throws Exception {
    // Otra base de datos preparada con el mismo script de inicio y migrada como propietario.
    String urlB = TestDatabase.prepareDatabase("esquema_b");
    TestDatabase.migrator(urlB).migrate();

    String a = schemaDump(POSTGRES.getDatabaseName());
    String b = schemaDump("esquema_b");
    assertThat(a).contains("CREATE TABLE public.article_revision");
    assertThat(b).isEqualTo(a);
  }

  /** pg_dump --schema-only sin las líneas que cambian en cada volcado. */
  private static String schemaDump(String database) throws Exception {
    ExecResult result = POSTGRES.execInContainer(
        "pg_dump", "--schema-only", "-U", POSTGRES.getUsername(), "-d", database);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    List<String> lines = result.getStdout().lines()
        // PostgreSQL 18 añade una clave aleatoria de protección (\restrict) en cada volcado.
        .filter(line -> !line.startsWith("\\restrict") && !line.startsWith("\\unrestrict"))
        .filter(line -> !line.startsWith("-- Dumped"))
        .collect(Collectors.toCollection(java.util.ArrayList::new));
    // El orden de las entradas de un ACL no tiene significado (6.2.4): depende de la historia del
    // objeto (p. ej. el esquema public, que la limpieza de Flyway no recrea, tras borrar y volver a
    // crear un rol en FunctionOwnersScriptTests). Se ordenan los GRANT/REVOKE consecutivos.
    int i = 0;
    while (i < lines.size()) {
      int j = i;
      while (j < lines.size() && (lines.get(j).startsWith("GRANT ") || lines.get(j).startsWith("REVOKE "))) {
        j++;
      }
      if (j > i + 1) {
        java.util.Collections.sort(lines.subList(i, j));
      }
      i = Math.max(j, i + 1);
    }
    return String.join("\n", lines);
  }
}
