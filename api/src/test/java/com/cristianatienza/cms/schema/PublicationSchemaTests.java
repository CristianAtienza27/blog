package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * V10 (fase 6.3.4) con PostgreSQL 18.6 real y logins reales: la matriz de transiciones, las
 * restricciones de huella y activación, la época que solo crece, una sola versión activa y los
 * permisos mínimos de cms_app (todo el flujo P1–P4 sin privilegios adicionales).
 */
class PublicationSchemaTests extends PostgresSchemaTest {

  private static final String SHA = "a".repeat(64);
  private static final String SHA2 = "b".repeat(64);

  private static String insert(int version) {
    return "INSERT INTO content_publication (version, state, requested_via) VALUES (" + version
        + ", 'generando', 'api')";
  }

  private static String candidate(int version, String sha) {
    return "UPDATE content_publication SET state = 'candidata', content_sha256 = '" + sha
        + "', files = 21, bytes = 88008, articles = 12, generated_at = clock_timestamp() WHERE version = " + version;
  }

  private static String activate(int version, Integer previous) {
    return "UPDATE content_publication SET state = 'activa', activated_at = clock_timestamp(), previous_version = "
        + previous + " WHERE version = " + version;
  }

  private static String state(int version) throws SQLException {
    return one(String.class, "SELECT state FROM content_publication WHERE version = ?", version);
  }

  /** Lleva vN al estado pedido por un camino válido (como cms_api). */
  private static void reach(int version, String target) throws SQLException {
    txAs(APP_LOGIN, insert(version));
    switch (target) {
      case "generando" -> {}
      case "fallida_sin_huella" -> txAs(APP_LOGIN, "UPDATE content_publication SET state = 'fallida',"
          + " issues = '[\"x\"]' WHERE version = " + version);
      default -> {
        txAs(APP_LOGIN, candidate(version, version % 2 == 0 ? SHA : SHA2));
        switch (target) {
          case "candidata" -> {}
          case "rechazada" -> txAs(APP_LOGIN, "UPDATE content_publication SET state = 'rechazada',"
              + " issues = '[\"x\"]' WHERE version = " + version);
          case "fallida" -> txAs(APP_LOGIN, "UPDATE content_publication SET state = 'fallida',"
              + " issues = '[\"x\"]' WHERE version = " + version);
          case "activa", "retirada" -> {
            txAs(APP_LOGIN, activate(version, null));
            if (target.equals("retirada")) {
              txAs(APP_LOGIN, "UPDATE content_publication SET state = 'retirada' WHERE version = " + version);
            }
          }
          default -> throw new IllegalArgumentException(target);
        }
      }
    }
  }

  /** La sentencia mínima que intenta la transición (con los datos que exige el destino). */
  private static String transition(int version, String to) {
    return switch (to) {
      case "candidata" -> candidate(version, SHA);
      case "activa" -> activate(version, null);
      case "rechazada", "fallida" -> "UPDATE content_publication SET state = '" + to + "', issues = '[\"y\"]'"
          + " WHERE version = " + version;
      default -> "UPDATE content_publication SET state = '" + to + "' WHERE version = " + version;
    };
  }

  @ParameterizedTest(name = "{0} → {1}: {2}")
  @CsvSource({
      "generando, candidata, true", "generando, fallida, true", "generando, activa, false",
      "generando, retirada, false", "generando, rechazada, false",
      "candidata, activa, true", "candidata, rechazada, true", "candidata, fallida, true",
      "candidata, generando, false", "candidata, retirada, false",
      "activa, retirada, true", "activa, candidata, false", "activa, fallida, false", "activa, rechazada, false",
      "activa, generando, false",
      "retirada, activa, true", "retirada, candidata, false", "retirada, fallida, false",
      "retirada, rechazada, false",
      "rechazada, activa, false", "rechazada, candidata, false", "rechazada, fallida, false",
      "rechazada, retirada, false",
      "fallida, activa, true", "fallida, candidata, false", "fallida, retirada, false", "fallida, rechazada, false",
      "fallida_sin_huella, activa, false", "fallida_sin_huella, candidata, false"})
  void matrizDeTransiciones(String from, String to, boolean allowed) throws Exception {
    reach(1, from);
    if (allowed) {
      txAs(APP_LOGIN, transition(1, to));
      assertThat(state(1)).isEqualTo(to);
    } else {
      txAsFails(APP_LOGIN, CHECK, transition(1, to));
      assertThat(state(1)).isEqualTo(from.replace("_sin_huella", ""));
    }
  }

  @Test
  void unaFilaNuevaEmpiezaEnGenerandoYSinHuella() {
    txAsFails(APP_LOGIN, CHECK, "INSERT INTO content_publication (version, state, requested_via) VALUES (1, 'activa', 'api')");
    // Columnas que cms_app no puede ni siquiera indicar al insertar.
    deniedAs(APP_LOGIN, "INSERT INTO content_publication (version, state, requested_via, content_sha256)"
        + " VALUES (1, 'generando', 'api', '" + SHA + "')");
    deniedAs(APP_LOGIN, "INSERT INTO content_publication (version, state, requested_via, created_at)"
        + " VALUES (1, 'generando', 'api', now())");
  }

  @Test
  void huellaFallidaYRechazada() throws Exception {
    // Sin huella solo en generando o fallida: una fallida sin huella existe y no se puede activar.
    reach(1, "fallida_sin_huella");
    assertThat(one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = 1")).isNull();
    txAsFails(APP_LOGIN, CHECK, activate(1, null));
    // Una candidata no puede perder su huella; una rechazada siempre la tiene.
    reach(2, "candidata");
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET content_sha256 = NULL WHERE version = 2");
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET state = 'candidata', content_sha256 = '" + SHA2
        + "' WHERE version = 2");
  }

  @Test
  void quePuedeCambiarCadaTransicion() throws Exception {
    reach(1, "candidata");
    // La huella solo al completar la generación.
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET state = 'fallida', issues = '[\"x\"]',"
        + " content_sha256 = '" + SHA + "' WHERE version = 1");
    // activated_at y previous_version solo al pasar a activa; issues solo al rechazar o fallar.
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET state = 'rechazada', issues = '[\"x\"]',"
        + " activated_at = now() WHERE version = 1");
    txAsFails(APP_LOGIN, CHECK, activate(1, null).replace("SET state", "SET issues = '[\"z\"]', state"));
    // Sin transición, nada salvo la purga.
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET issues = '[\"x\"]' WHERE version = 1");
    txAs(APP_LOGIN, activate(1, null));
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET files_purged_at = now() WHERE version = 1");
    txAs(APP_LOGIN, "UPDATE content_publication SET state = 'retirada' WHERE version = 1");
    txAs(APP_LOGIN, "UPDATE content_publication SET files_purged_at = now() WHERE version = 1");
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication SET files_purged_at = now() WHERE version = 1");
    // Una versión con los archivos purgados no se puede volver a activar.
    txAsFails(APP_LOGIN, CHECK, activate(1, null));
    assertThat(one(String.class, "SELECT state FROM content_publication WHERE version = 1")).isEqualTo("retirada");
  }

  @Test
  void unaSolaVersionActiva() throws Exception {
    reach(1, "activa");
    reach(2, "candidata");
    txAsFails(APP_LOGIN, UNIQUE, activate(2, 1));
    txAs(APP_LOGIN, "UPDATE content_publication SET state = 'retirada' WHERE version = 1", activate(2, 1));
    assertThat(column("SELECT version || '=' || state FROM content_publication ORDER BY version"))
        .containsExactly("1=retirada", "2=activa");
    assertThat(one(Integer.class, "SELECT previous_version FROM content_publication WHERE version = 2")).isEqualTo(1);
  }

  @Test
  void columnasProtegidasYSinBorrar() throws Exception {
    reach(1, "candidata");
    for (String column : List.of("version = 9", "requested_by = NULL", "requested_via = 'herramienta'",
        "created_at = now()", "updated_at = now()")) {
      deniedAs(APP_LOGIN, "UPDATE content_publication SET " + column + " WHERE version = 1");
    }
    deniedAs(APP_LOGIN, "DELETE FROM content_publication WHERE version = 1");
    deniedAs(APP_LOGIN, "TRUNCATE content_publication");
    // Ni el propietario cambia la identidad de una fila (el trigger).
    asOwnerFails(CHECK, "UPDATE content_publication SET requested_via = 'herramienta' WHERE version = 1");
  }

  @Test
  void laEpocaSoloCreceYCmsAppPuedeBloquearSuFila() throws Exception {
    txAs(APP_LOGIN, "UPDATE content_publication_lease SET epoch = epoch + 1, holder = 'api' WHERE id = 1");
    txAs(APP_LOGIN, "SELECT epoch FROM content_publication_lease WHERE id = 1 FOR UPDATE");
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication_lease SET epoch = 0 WHERE id = 1");
    txAsFails(APP_LOGIN, CHECK, "UPDATE content_publication_lease SET holder = 'x' WHERE id = 1");
    deniedAs(APP_LOGIN, "DELETE FROM content_publication_lease");
    deniedAs(APP_LOGIN, "INSERT INTO content_publication_lease (id, epoch) VALUES (1, 5)");
    assertThat(one(Long.class, "SELECT epoch FROM content_publication_lease")).isEqualTo(1L);
    // La comprobación: una fila sembrada que cms_app actualiza, sin insertar ni borrar.
    txAs(APP_LOGIN, "UPDATE content_publication_check SET status = 'VACIO', detail = '{}',"
        + " checked_at = now() WHERE id = 1");
    deniedAs(APP_LOGIN, "INSERT INTO content_publication_check (id, status) VALUES (2, 'VACIO')");
    deniedAs(APP_LOGIN, "DELETE FROM content_publication_check");
  }

  @Test
  void elFlujoCompletoConElLoginRealDeLaApi() throws Exception {
    // Lectura del estado publicado (EXECUTE nuevo) y cada sentencia de P1–P4, la purga y la auditoría.
    txAs(APP_LOGIN, "SELECT article_revision_content(1)");
    txAs(APP_LOGIN,
        "SELECT epoch FROM content_publication_lease WHERE id = 1 FOR UPDATE",
        insert(1));
    txAs(APP_LOGIN, candidate(1, SHA));
    txAs(APP_LOGIN, activate(1, null),
        "INSERT INTO audit_event (id, event, outcome, details) VALUES (gen_random_uuid(), 'content.published',"
            + " 'success', '{}')");
    txAs(APP_LOGIN, insert(2), candidate(2, SHA2));
    txAs(APP_LOGIN, "UPDATE content_publication SET state = 'retirada' WHERE version = 1", activate(2, 1));
    txAs(APP_LOGIN, insert(3), candidate(3, "c".repeat(64)),
        "UPDATE content_publication SET state = 'rechazada', issues = '[\"x\"]' WHERE version = 3");
    txAs(APP_LOGIN, "UPDATE content_publication SET files_purged_at = now() WHERE version = 1");
    txAs(APP_LOGIN, "UPDATE content_publication_check SET status = 'CONCILIADO', detail = '{}', pointer_version = 2,"
        + " checked_at = now() WHERE id = 1");
    assertThat(column("SELECT version || '=' || state FROM content_publication ORDER BY version"))
        .containsExactly("1=retirada", "2=activa", "3=rechazada");
  }

  @Test
  void otrosLoginsSinAcceso() {
    for (String sql : List.of("SELECT * FROM content_publication", insert(1),
        "UPDATE content_publication_lease SET epoch = epoch + 1", "SELECT * FROM content_publication_check")) {
      deniedAs(IMPORTER_LOGIN, sql);
      rejectedAs(NOBODY_LOGIN, sql);
    }
  }

  @Test
  void permisosEfectivosDeCmsAppExactamenteLosAprobados() throws Exception {
    // Permisos de tabla (aclexplode de relacl) y de columna (de attacl) concedidos a cms_app en V10.
    assertThat(column("SELECT c.relname || ' ' || x.privilege_type FROM pg_class c CROSS JOIN aclexplode(c.relacl) x"
        + " WHERE c.relname LIKE 'content_publication%' AND c.relkind = 'r' AND x.grantee = 'cms_app'::regrole ORDER BY 1"))
        .containsExactly("content_publication SELECT", "content_publication_check SELECT",
            "content_publication_lease SELECT");
    assertThat(column("SELECT c.relname || '.' || a.attname || ' ' || x.privilege_type FROM pg_attribute a"
        + " JOIN pg_class c ON c.oid = a.attrelid CROSS JOIN aclexplode(a.attacl) x"
        + " WHERE c.relname LIKE 'content_publication%' AND x.grantee = 'cms_app'::regrole ORDER BY 1"))
        .containsExactly(
            "content_publication.activated_at UPDATE", "content_publication.articles UPDATE",
            "content_publication.bytes UPDATE", "content_publication.content_sha256 UPDATE",
            "content_publication.files UPDATE", "content_publication.files_purged_at UPDATE",
            "content_publication.generated_at UPDATE", "content_publication.issues UPDATE",
            "content_publication.previous_version UPDATE", "content_publication.requested_by INSERT",
            "content_publication.requested_via INSERT", "content_publication.state INSERT",
            "content_publication.state UPDATE", "content_publication.version INSERT",
            "content_publication_check.checked_at UPDATE", "content_publication_check.detail UPDATE",
            "content_publication_check.pointer_version UPDATE", "content_publication_check.status UPDATE",
            "content_publication_lease.acquired_at UPDATE", "content_publication_lease.epoch UPDATE",
            "content_publication_lease.holder UPDATE");
    // Nadie más recibe nada en estas tablas (salvo el propietario).
    assertThat(column("SELECT DISTINCT x.grantee::regrole::text FROM pg_class c CROSS JOIN aclexplode(c.relacl) x"
        + " WHERE c.relname LIKE 'content_publication%' AND c.relkind = 'r' ORDER BY 1")).containsExactly("cms_app", "cms_owner");
  }

  @Test
  void funcionesNuevasDelPropietarioSinSecurityDefinerNiEjecucionAjena() throws Exception {
    for (String function : List.of("guard_content_publication()", "guard_content_publication_lease()")) {
      assertThat(one(String.class, "SELECT proowner::regrole::text FROM pg_proc WHERE oid = ?::regprocedure", function))
          .isEqualTo("cms_owner");
      assertThat(one(Boolean.class, "SELECT prosecdef FROM pg_proc WHERE oid = ?::regprocedure", function)).isFalse();
      for (String role : List.of("cms_app", "cms_importer", "public")) {
        assertThat(one(Boolean.class, "SELECT has_function_privilege(?, ?, 'EXECUTE')", role, function))
            .as(role + " " + function).isFalse();
      }
    }
    // article_revision_content: EXECUTE para cms_app (R6), sin SECURITY DEFINER; su dueño no cambia.
    assertThat(one(Boolean.class, "SELECT has_function_privilege('cms_app', 'article_revision_content(bigint)', 'EXECUTE')")).isTrue();
    assertThat(one(Boolean.class, "SELECT prosecdef FROM pg_proc WHERE proname = 'article_revision_content'")).isFalse();
    assertThat(one(String.class, "SELECT proowner::regrole::text FROM pg_proc WHERE proname = 'article_revision_content'"))
        .isEqualTo("cms_revision_writer");
  }
}
