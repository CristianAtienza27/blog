package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V11 (fase 6.4.5): publish_article y unpublish_article, su dueño cms_article_publisher con
 * privilegios mínimos, la guarda de los punteros con su única vía nueva y las reglas de la operación
 * (versión, pertenencia, revisión de trabajo, fecha de Europe/Madrid, orden), con logins reales.
 */
class ArticlePublishingSchemaTests extends PostgresSchemaTest {

  private static final String TODAY_MADRID = "SELECT (statement_timestamp() AT TIME ZONE 'Europe/Madrid')::date::text";

  @BeforeEach
  void catalogAndArticles() throws Exception {
    seedCatalog();
    // Un publicado de la sección (orden 1) y dos borradores sin fecha ni orden.
    createArticle("publicado-motor", "motor", 1, 1);
    long head = createRevision("publicado-motor", null, content("Publicado"));
    txAs("cms_api", "SELECT * FROM publish_article('publicado-motor', " + head + ", 0)");
    for (String id : List.of("borrador-uno", "borrador-dos")) {
      tx("INSERT INTO article (id, slug, category_id, featured, demo, catalog_position) VALUES (" + q(id) + ", "
          + q(id) + ", 'motor', false, true, " + (id.equals("borrador-uno") ? 2 : 3) + ")");
      createRevision(id, null, content("Borrador " + id));
    }
  }

  private static String row(String login, String sql) throws Exception {
    try (Connection c = connectAs(login); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      List<String> values = new ArrayList<>();
      for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
        values.add(rs.getString(i));
      }
      return String.join("|", values);
    }
  }

  private static String state(String id) throws Exception {
    return one(String.class, "SELECT concat_ws('|', coalesce(published_revision_id = head_revision_id, false),"
        + " coalesce(published_at::text, '-'), coalesce(editorial_order::text, '-'), lock_version)"
        + " FROM article WHERE id = ?", id);
  }

  private static long head(String id) throws Exception {
    return one(Long.class, "SELECT head_revision_id FROM article WHERE id = ?", id);
  }

  // --- Dueño, propiedades y permisos ------------------------------------------------------------------

  @Test
  void lasFuncionesSonDeCmsArticlePublisherSecurityDefinerYSoloLasEjecutaLaApi() throws Exception {
    for (String function : List.of("publish_article(text,bigint,integer)", "unpublish_article(text,integer)")) {
      assertThat(one(String.class, "SELECT proowner::regrole || ':' || prosecdef || ':' || array_to_string(proconfig, ',')"
          + " FROM pg_proc WHERE oid = ?::regprocedure", function))
          .as(function).isEqualTo("cms_article_publisher:true:search_path=pg_catalog, public, pg_temp");
      assertThat(column("SELECT r FROM unnest(ARRAY['cms_app', 'cms_importer', 'cms_security_admin',"
          + " 'cms_audit_maintainer', 'cms_revision_writer', 'cms_import_writer', 'cms_enrollment_writer'])"
          + " AS r WHERE has_function_privilege(r, ?::regprocedure, 'EXECUTE') ORDER BY 1", function))
          .as(function).containsExactly("cms_app");
      assertThat(one(Boolean.class, "SELECT has_function_privilege('public', ?::regprocedure, 'EXECUTE')", function))
          .as(function + " para PUBLIC").isFalse();
    }
  }

  @Test
  void cmsArticlePublisherTieneExactamenteLoMinimo() throws Exception {
    // Por columnas: leer lo que comprueba y escribir solo los cuatro campos de la publicación.
    assertThat(column("SELECT c.relname || '.' || a.attname || ':' || x.privilege_type FROM pg_attribute a"
        + " JOIN pg_class c ON c.oid = a.attrelid, aclexplode(a.attacl) x"
        + " WHERE c.relnamespace = 'public'::regnamespace AND x.grantee = 'cms_article_publisher'::regrole ORDER BY 1"))
        .containsExactly(
            "article.category_id:SELECT", "article.editorial_order:SELECT", "article.editorial_order:UPDATE",
            "article.head_revision_id:SELECT", "article.id:SELECT", "article.lock_version:SELECT",
            "article.lock_version:UPDATE", "article.published_at:SELECT", "article.published_at:UPDATE",
            "article.published_revision_id:SELECT", "article.published_revision_id:UPDATE",
            "article_revision.article_id:SELECT", "article_revision.id:SELECT");
    // Ningún privilegio de tabla, ni CREATE en el esquema (solo USAGE).
    assertThat(column("SELECT c.relname || ':' || x.privilege_type FROM pg_class c, aclexplode(c.relacl) x"
        + " WHERE c.relnamespace = 'public'::regnamespace AND x.grantee = 'cms_article_publisher'::regrole")).isEmpty();
    assertThat(one(String.class, "SELECT has_schema_privilege('cms_article_publisher', 'public', 'USAGE') || ':'"
        + " || has_schema_privilege('cms_article_publisher', 'public', 'CREATE')")).isEqualTo("true:false");
    assertThat(one(Boolean.class, "SELECT has_table_privilege('cms_article_publisher', 'article_revision', 'UPDATE')"
        + " OR has_table_privilege('cms_article_publisher', 'article', 'DELETE')")).isFalse();
  }

  // --- publish_article ----------------------------------------------------------------------------------

  @Test
  void publicarUnBorradorFijaLaFechaDeHoyYElSiguienteOrdenDeSuSeccion() throws Exception {
    String today = one(String.class, TODAY_MADRID);
    String result = row("cms_api", "SELECT * FROM publish_article('borrador-uno', " + head("borrador-uno") + ", 0)");
    assertThat(result).isEqualTo("t|" + today + "|2|1");
    assertThat(state("borrador-uno")).isEqualTo("t|" + today + "|2|1"); // concat_ws: booleanos como t/f
  }

  @Test
  void versionObsoletaEsCm412YNoCambiaNada() throws Exception {
    String before = state("borrador-uno");
    assertThat(txAsFails("cms_api", "CM412",
        "SELECT * FROM publish_article('borrador-uno', " + head("borrador-uno") + ", 7)").getMessage())
        .contains("versión obsoleta");
    assertThat(txAsFails("cms_api", "CM412", "SELECT * FROM unpublish_article('publicado-motor', 7)").getMessage())
        .contains("versión obsoleta");
    assertThat(state("borrador-uno")).isEqualTo(before);
  }

  @Test
  void laRevisionDebeSerDelArticuloYSuRevisionDeTrabajo() throws Exception {
    String before = state("borrador-uno");
    assertThat(txAsFails("cms_api", INVALID_PARAMETER,
        "SELECT * FROM publish_article('borrador-uno', " + head("borrador-dos") + ", 0)").getMessage())
        .contains("no es del artículo");
    long first = head("borrador-uno");
    createRevision("borrador-uno", first, content("Segunda"));
    assertThat(txAsFails("cms_api", REVISION_CONFLICT,
        "SELECT * FROM publish_article('borrador-uno', " + first + ", 0)").getMessage())
        .contains("la revisión de trabajo es");
    assertThat(state("borrador-uno")).isEqualTo(before);
    assertThat(txAsFails("cms_api", FOREIGN_KEY, "SELECT * FROM publish_article('no-existe', 1, 0)").getMessage())
        .contains("artículo inexistente");
  }

  @Test
  void republicarConservaFechaYOrdenYPublicarLoPublicadoNoCambiaNada() throws Exception {
    String today = one(String.class, TODAY_MADRID);
    txAs("cms_api", "SELECT * FROM publish_article('borrador-uno', " + head("borrador-uno") + ", 0)");
    // Lo mismo otra vez: sin cambios ni versión nueva.
    assertThat(row("cms_api", "SELECT * FROM publish_article('borrador-uno', " + head("borrador-uno") + ", 1)"))
        .isEqualTo("f|" + today + "|2|1");
    // Una fecha anterior (como un artículo importado) se conserva al republicar una revisión nueva.
    asApp("UPDATE article SET published_at = '2026-01-15' WHERE id = 'borrador-uno'");
    long second = createRevision("borrador-uno", head("borrador-uno"), content("Segunda"));
    assertThat(row("cms_api", "SELECT * FROM publish_article('borrador-uno', " + second + ", 1)"))
        .isEqualTo("t|2026-01-15|2|2");
  }

  @Test
  void despublicarQuitaElPunteroYConservaFechaYOrden() throws Exception {
    String before = state("publicado-motor");
    assertThat(row("cms_api", "SELECT * FROM unpublish_article('publicado-motor', 1)")).isEqualTo("t|2");
    assertThat(one(String.class, "SELECT coalesce(published_revision_id::text, 'null') || '|' || published_at || '|'"
        + " || editorial_order FROM article WHERE id = 'publicado-motor'"))
        .isEqualTo("null|" + before.split("\\|")[1] + "|1");
    assertThat(row("cms_api", "SELECT * FROM unpublish_article('publicado-motor', 2)")).isEqualTo("f|2");
  }

  @Test
  void dosPublicacionesSimultaneasEnLaMismaSeccionRecibenOrdenesDistintos() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (String id : List.of("borrador-uno", "borrador-dos")) {
        long head = head(id);
        futures.add(pool.submit(() -> {
          start.await();
          return row("cms_api", "SELECT new_order FROM publish_article(" + q(id) + ", " + head + ", 0)");
        }));
      }
      start.countDown();
      List<String> orders = new ArrayList<>();
      for (Future<String> f : futures) {
        orders.add(f.get());
      }
      assertThat(orders).containsExactlyInAnyOrder("2", "3");
    } finally {
      pool.shutdownNow();
    }
  }

  // --- Ninguna otra vía hacia la revisión publicada ------------------------------------------------------

  @Test
  void elPunteroPublicadoSoloLoMuevenLasOperaciones() throws Exception {
    long draft = head("borrador-uno");
    String before = state("borrador-uno");
    // La API, por DML directo: sin privilegio de columna.
    deniedAs("cms_api", "UPDATE article SET published_revision_id = " + draft + " WHERE id = 'borrador-uno'");
    // El propietario de la tabla: lo para la guarda (mira current_user y session_user).
    assertThat(asOwnerFails(NO_PRIVILEGE,
        "UPDATE article SET published_revision_id = " + draft + ", published_at = '2026-01-01', editorial_order = 9"
            + " WHERE id = 'borrador-uno'").getMessage()).contains("la revisión publicada solo la cambia");
    // El propietario asumiendo el rol dueño: current_user es el correcto, pero la sesión no es de la API.
    assertThat(asOwnerFails(NO_PRIVILEGE, "SET LOCAL ROLE cms_article_publisher",
        "UPDATE article SET published_revision_id = " + draft + ", published_at = '2026-01-01', editorial_order = 9"
            + " WHERE id = 'borrador-uno'").getMessage()).contains("la revisión publicada solo la cambia");
    // El propietario llamando a la operación: hereda EXECUTE, pero la sesión no es de la API.
    assertThat(asOwnerFails(NO_PRIVILEGE, "SELECT * FROM publish_article('borrador-uno', " + draft + ", 0)")
        .getMessage()).contains("solo puede usarla una conexión de la API");
    // La API no puede asumir el rol dueño; la herramienta y la de seguridad no pueden ejecutarla.
    deniedAs("cms_api", "SET LOCAL ROLE cms_article_publisher");
    deniedAs("cms_tool", "SELECT * FROM publish_article('borrador-uno', " + draft + ", 0)");
    deniedAs("cms_security_tool", "SELECT * FROM unpublish_article('publicado-motor', 1)");
    // Las operaciones de revisión tampoco tocan la publicada.
    createRevision("borrador-uno", draft, content("Otra"));
    assertThat(one(Boolean.class, "SELECT published_revision_id IS NULL FROM article WHERE id = 'borrador-uno'"))
        .isTrue();
    assertThat(state("borrador-uno").split("\\|")[1]).isEqualTo(before.split("\\|")[1]);
  }
}
