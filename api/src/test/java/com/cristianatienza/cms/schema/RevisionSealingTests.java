package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Inmutabilidad y permisos: el historial no se modifica ni se amplía después de crearse, ni como
 * propietario ni como aplicación; la aplicación solo escribe revisiones con las dos funciones,
 * que son SECURITY DEFINER endurecidas.
 */
class RevisionSealingTests extends PostgresSchemaTest {

  private static final List<String> REVISION_TABLES = List.of(
      "article_revision", "article_revision_tag", "article_revision_destination", "article_revision_block_ref");

  private long revision;

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
    createArticle("a-uno", "viajes", 1, 1);
    revision = createRevision("a-uno", null, content("Primera",
        "[{\"type\": \"image\", \"media\": \"m-uno\", \"layout\": \"wide\"}]")
        .replace("\"readingMinutes\"", "\"destinations\": [\"d-uno\"], \"readingMinutes\""));
  }

  // --- Inmutabilidad (también como propietario de la base de datos) --------------------------

  @ParameterizedTest
  @ValueSource(strings = {"article_revision", "article_revision_tag", "article_revision_destination",
      "article_revision_block_ref"})
  void niModificarNiBorrarNiTruncar(String table) {
    String key = table.equals("article_revision") ? "id" : "revision_id";
    // article_revision.id es una identidad (no se puede asignar): se modifica otra columna.
    String column = table.equals("article_revision") ? "title" : "revision_id";
    txFails(INTEGRITY, "UPDATE " + table + " SET " + column + " = " + column + " WHERE " + key + " = " + revision);
    txFails(INTEGRITY, "DELETE FROM " + table + " WHERE " + key + " = " + revision);
    txFails(INTEGRITY, "TRUNCATE " + table + " CASCADE");
  }

  @Test
  void unaRevisionConfirmadaNoAdmiteElementosNuevosNiSiquieraAlPropietario() throws Exception {
    for (String insert : List.of(
        "INSERT INTO article_revision_tag (revision_id, position, tag_id) VALUES (" + revision + ", 9, 'mar')",
        "INSERT INTO article_revision_destination (revision_id, position, destination_id) VALUES (" + revision
            + ", 9, 'd-dos')",
        "INSERT INTO article_revision_block_ref (revision_id, ref_path, block_index, block_type, kind, media_id)"
            + " VALUES (" + revision + ", 'blocks[5].media', 5, 'image', 'media', 'm-dos')")) {
      // Sellado: aun con el rol definidor del núcleo y el indicador activado a mano (solo posible
      // desde una sesión con todos los privilegios), la revisión es de otra transacción.
      txFails(INTEGRITY, "SET LOCAL ROLE cms_revision_writer", "SET LOCAL cms.revision_writer = 'on'", insert);
      // El propietario (cms_migrator con SET ROLE cms_owner), con el indicador: lo rechaza antes la
      // identidad (V6: rol definidor y sesión de la API o del importador).
      asOwnerFails(NO_PRIVILEGE, "SET LOCAL cms.revision_writer = 'on'", insert);
      txFails(NO_PRIVILEGE, insert); // sin el indicador: entrada única
    }
  }

  @Test
  void sinLaOperacionControladaNoSeCreanRevisionesNiSeMueveLaRevisionDeTrabajo() {
    txFails(NO_PRIVILEGE, "INSERT INTO article_revision (article_id, number, origin, title, dek, excerpt, format,"
        + " author_id, destinations_present, blocks, reading_minutes) VALUES ('a-uno', 9, 'edit', 'T', 'D', 'E',"
        + " 'guia', 'cristian-atienza', false, '[{\"type\": \"divider\", \"variant\": \"line\"}]', 1)");
    txFails(NO_PRIVILEGE, "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'");
    txFails(CHECK, "INSERT INTO article (id, slug, category_id, featured, demo, head_revision_id, catalog_position)"
        + " VALUES ('a-x', 'a-x', 'motor', false, true, " + revision + ", 9)");
  }

  // --- Permisos del rol de la aplicación ------------------------------------------------------

  @Test
  void laAplicacionNoPuedeEscribirDirectamenteEnLasTablasProtegidas() throws Exception {
    asAppDenied("INSERT INTO article_revision (article_id, number, origin, title, dek, excerpt, format,"
        + " author_id, destinations_present, blocks, reading_minutes) VALUES ('a-uno', 9, 'edit', 'T', 'D', 'E',"
        + " 'guia', 'cristian-atienza', false, '[{\"type\": \"divider\", \"variant\": \"line\"}]', 1)");
    asAppDenied("SET LOCAL cms.revision_writer = 'on'",
        "INSERT INTO article_revision_tag (revision_id, position, tag_id) VALUES (" + revision + ", 9, 'mar')");
    asAppDenied("INSERT INTO article_revision_destination (revision_id, position, destination_id)"
        + " VALUES (" + revision + ", 9, 'd-dos')");
    asAppDenied("INSERT INTO article_revision_block_ref (revision_id, ref_path, block_index, block_type,"
        + " kind, media_id) VALUES (" + revision + ", 'blocks[5].media', 5, 'image', 'media', 'm-dos')");
    for (String table : REVISION_TABLES) {
      String column = table.equals("article_revision") ? "title" : "revision_id";
      asAppDenied("UPDATE " + table + " SET " + column + " = " + column);
      asAppDenied("DELETE FROM " + table);
      asAppDenied("TRUNCATE " + table);
    }
    asAppDenied("UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'");
    asAppDenied("UPDATE article SET published_revision_id = " + revision + " WHERE id = 'a-uno'");
    asAppDenied("DELETE FROM article WHERE id = 'a-uno'");
    // Las funciones internas no son ejecutables por la aplicación.
    asAppDenied("SELECT * FROM insert_article_revision('a-uno', " + revision + ", 'edit', "
        + q(content("X")) + "::jsonb, NULL)");
    // V10 (fase 6.3.4, R6): la API lee lo publicado con article_revision_content. Sigue sin ser
    // SECURITY DEFINER, así que solo lee lo que cms_app ya puede leer (las tablas de revisiones, V3).
    asApp("SELECT article_revision_content(" + revision + ")");
    assertThat(one(Boolean.class, "SELECT prosecdef FROM pg_proc WHERE proname = 'article_revision_content'")).isFalse();
  }

  @Test
  void laAplicacionCreaYRestauraRevisionesConLasFuncionesPublicas() throws Exception {
    asApp("UPDATE article SET slug = 'a-uno-nuevo', featured = true WHERE id = 'a-uno'", // datos del artículo: sí
        createRevisionSql("a-uno", revision, content("Segunda")));
    long second = one(Long.class, "SELECT head_revision_id FROM article WHERE id = 'a-uno'");
    asApp("SELECT * FROM restore_article_revision(" + revision + ", " + second + ")");
    assertThat(column("SELECT origin FROM article_revision WHERE article_id = 'a-uno' ORDER BY number"))
        .containsExactly("edit", "edit", "restore");
  }

  @Test
  void unaTablaTemporalConElMismoNombreNoSuplantaALasDeLaFuncion() throws Exception {
    // search_path fijado (pg_temp al final) y nombres con esquema: la sesión no puede desviar
    // la función hacia objetos propios. Desde la 6.2.1 la aplicación ni siquiera puede crear
    // tablas temporales (PUBLIC sin TEMPORARY), así que la sesión las crea antes de asumir
    // cms_app: siguen en su pg_temp cuando la aplicación llama a la función.
    txAsFails(APP_LOGIN, NO_PRIVILEGE, "CREATE TEMP TABLE article (id text)");
    tx("CREATE TEMP TABLE article (id text, head_revision_id bigint) ON COMMIT DROP",
        "CREATE TEMP TABLE article_revision_tag (revision_id bigint, position int, tag_id text) ON COMMIT DROP",
        "SET LOCAL ROLE cms_app",
        createRevisionSql("a-uno", revision, content("A pesar de las tablas temporales")));
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision WHERE article_id = 'a-uno'")).isEqualTo(2L);
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision_tag")).isEqualTo(2L);
  }

  // --- Endurecimiento de las funciones SECURITY DEFINER ----------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"create_article_revision", "restore_article_revision", "insert_article_revision"})
  void funcionesSecurityDefinerEndurecidas(String function) throws Exception {
    assertThat(one(String.class, "SELECT p.prosecdef || '|' || array_to_string(p.proconfig, ',') || '|'"
            + " || r.rolname || '|' || r.rolsuper || '|' || r.rolcanlogin"
            + " FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner WHERE p.proname = ?", function))
        .isEqualTo("true|search_path=pg_catalog, public, pg_temp|cms_revision_writer|false|false");
    assertThat(one(Boolean.class, "SELECT EXISTS (SELECT 1 FROM pg_proc p, aclexplode(p.proacl) a"
            + " WHERE p.proname = ? AND a.grantee = 0)", function))
        .as("PUBLIC no tiene EXECUTE").isFalse();
  }

  @Test
  void soloLaAplicacionEjecutaLasDosFuncionesPublicas() throws Exception {
    assertThat(one(Boolean.class, "SELECT has_function_privilege('cms_app',"
        + " 'create_article_revision(text,bigint,text,jsonb)', 'EXECUTE')")).isTrue();
    assertThat(one(Boolean.class, "SELECT has_function_privilege('cms_app',"
        + " 'restore_article_revision(bigint,bigint)', 'EXECUTE')")).isTrue();
    assertThat(one(Boolean.class, "SELECT has_function_privilege('cms_app',"
        + " 'insert_article_revision(text,bigint,text,jsonb,bigint)', 'EXECUTE')")).isFalse();
    for (String table : REVISION_TABLES) {
      assertThat(one(Boolean.class, "SELECT has_table_privilege('cms_app', ?, 'INSERT, UPDATE, DELETE, TRUNCATE')",
          table)).as(table).isFalse();
      assertThat(one(Boolean.class, "SELECT has_table_privilege('cms_app', ?, 'SELECT')", table)).isTrue();
    }
    assertThat(one(String.class, "SELECT string_agg(rolname || ':' || rolsuper || ':' || rolcanlogin, ','"
            + " ORDER BY rolname) FROM pg_roles WHERE rolname IN ('cms_app', 'cms_revision_writer')"))
        .isEqualTo("cms_app:false:false,cms_revision_writer:false:false");
  }
}
