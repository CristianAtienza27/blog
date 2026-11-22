package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * import_published_article y la separación de privilegios, con conexiones de usuarios reales
 * (sin superusuario): uno miembro solo de cms_importer, otro solo de cms_app y otro sin roles.
 */
class ImportOperationTests extends PostgresSchemaTest {

  private static final String BLOCKS = "[{\"type\": \"text\", \"html\": \"<p>Hola</p>\"}]";

  /** Un ArticleRecord completo, como en articles.json. */
  private static String record(String id, int order) {
    return "{\"id\": \"" + id + "\", \"slug\": \"" + id + "\", \"title\": \"Título\", \"dek\": \"Entradilla\","
        + " \"excerpt\": \"Extracto\", \"category\": \"viajes\", \"tags\": [\"costa\", \"mar\"],"
        + " \"format\": \"guia\", \"cover\": \"m-uno\", \"author\": \"cristian-atienza\", \"order\": " + order + ","
        + " \"publishedAt\": \"2026-09-18\", \"readingMinutes\": 1, \"featured\": true, \"demo\": true,"
        + " \"trip\": \"t-uno\", \"destinations\": [\"d-tres\"]}";
  }

  private static String importSql(String record, String blocks, int position) {
    return "SELECT * FROM import_published_article(" + q(record) + "::jsonb, " + q(blocks) + "::jsonb, " + position + ")";
  }

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
  }

  // --- La operación ---------------------------------------------------------------------------

  @Test
  void elImportadorCreaArticuloRevisionYPunterosEnUnaOperacion() throws Exception {
    txAs(IMPORTER_LOGIN, importSql(record("a-uno", 1), BLOCKS, 1));
    assertThat(one(String.class, "SELECT a.slug || '|' || a.category_id || '|' || a.editorial_order || '|' || a.featured"
            + " || '|' || a.demo || '|' || a.published_at || '|' || (a.head_revision_id = r.id)"
            + " || '|' || (a.published_revision_id = r.id) || '|' || r.number || '|' || r.origin"
            + " || '|' || COALESCE(r.parent_revision_id::text, '-')"
            + " FROM article a JOIN article_revision r ON r.article_id = a.id WHERE a.id = 'a-uno'"))
        .isEqualTo("a-uno|viajes|1|true|true|2026-09-18|true|true|1|import|-");
    // El contenido de la revisión es el del registro (sin la identidad) más los bloques.
    String expected = "{\"title\": \"Título\", \"dek\": \"Entradilla\", \"excerpt\": \"Extracto\","
        + " \"tags\": [\"costa\", \"mar\"], \"format\": \"guia\", \"cover\": \"m-uno\","
        + " \"author\": \"cristian-atienza\", \"readingMinutes\": 1, \"trip\": \"t-uno\","
        + " \"destinations\": [\"d-tres\"], \"blocks\": " + BLOCKS + "}";
    assertThat(one(Boolean.class, "SELECT article_revision_content(published_revision_id) = ?::jsonb FROM article"
        + " WHERE id = 'a-uno'", expected)).isTrue();
  }

  @Test
  void siAlgoFallaNoQuedaNada() throws Exception {
    tx("UPDATE media SET retired = true WHERE id = 'm-libre'");
    // La revisión fallaría (imagen retirada en un bloque): ni artículo ni revisión.
    txAsFails(IMPORTER_LOGIN, CHECK, importSql(record("a-uno", 1),
        "[{\"type\": \"image\", \"media\": \"m-libre\", \"layout\": \"wide\"}]", 1));
    assertThat(one(Long.class, "SELECT count(*) FROM article")).isZero();
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision")).isZero();
  }

  @Test
  void rechazaRegistrosInvalidosYArticulosExistentes() throws Exception {
    String ok = record("a-uno", 1);
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"dek\"", "\"subtitulo\""), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"order\": 1,", ""), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"cover\": \"m-uno\"", "\"cover\": null"), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"demo\": true", "\"demo\": null"), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("2026-09-18", "18/09/2026"), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"order\": 1", "\"order\": 1.5"), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, INVALID_PARAMETER, importSql(ok.replace("\"featured\": true", "\"featured\": \"sí\""), BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, CHECK, importSql(ok, "[]", 1)); // cuerpo vacío: lo rechaza la revisión
    assertThat(one(Long.class, "SELECT count(*) FROM article")).isZero();
    txAs(IMPORTER_LOGIN, importSql(ok, BLOCKS, 1));
    txAsFails(IMPORTER_LOGIN, UNIQUE, importSql(ok, BLOCKS, 2)); // ya existe: no se actualiza
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision")).isEqualTo(1L);
  }

  // --- Usuarios reales y privilegios -----------------------------------------------------------

  @Test
  void losUsuariosDePruebaSonRealesYLimitados() throws Exception {
    assertThat(column("SELECT r.rolname || ':' || r.rolsuper || ':' || r.rolcanlogin || ':' || r.rolcreaterole"
            + " || ':' || COALESCE((SELECT string_agg(g.rolname, ',' ORDER BY g.rolname) FROM pg_auth_members m"
            + " JOIN pg_roles g ON g.oid = m.roleid WHERE m.member = r.oid), '-')"
            + " FROM pg_roles r WHERE r.rolname IN (?, ?, ?) ORDER BY r.rolname", APP_LOGIN, IMPORTER_LOGIN, NOBODY_LOGIN))
        .containsExactly(
            "cms_api:false:true:false:cms_app",
            "cms_nobody_login:false:true:false:-",
            "cms_tool:false:true:false:cms_importer");
    // Cada uno inicia sesión con su propia conexión, sin SET ROLE.
    try (var c = connectAs(IMPORTER_LOGIN); var s = c.createStatement(); var rs = s.executeQuery(
        "SELECT session_user || '|' || current_user || '|' || pg_has_role('cms_importer', 'USAGE')"
            + " || '|' || pg_has_role('cms_import_writer', 'MEMBER') || '|' || pg_has_role('cms_app', 'MEMBER')")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("cms_tool|cms_tool|true|false|false");
    }
  }

  @Test
  void elImportadorNoPuedeSaltarseLaOperacion() throws Exception {
    txAs(IMPORTER_LOGIN, importSql(record("a-uno", 1), BLOCKS, 1));
    long revision = one(Long.class, "SELECT published_revision_id FROM article WHERE id = 'a-uno'");
    // Ni el puntero publicado ni el de trabajo, ni el artículo, ni las revisiones directamente.
    deniedAs(IMPORTER_LOGIN, "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
    deniedAs(IMPORTER_LOGIN, "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'");
    deniedAs(IMPORTER_LOGIN, "INSERT INTO article (id, slug, category_id, featured, demo, catalog_position)"
        + " VALUES ('a-x', 'a-x', 'motor', false, true, 9)");
    deniedAs(IMPORTER_LOGIN, "INSERT INTO article_revision_tag (revision_id, position, tag_id) VALUES ("
        + revision + ", 9, 'mar')");
    deniedAs(IMPORTER_LOGIN, "SET LOCAL cms.revision_writer = 'on'",
        "INSERT INTO article_revision_tag (revision_id, position, tag_id) VALUES (" + revision + ", 9, 'mar')");
    // Ni el núcleo interno ni las operaciones de edición de la aplicación.
    deniedAs(IMPORTER_LOGIN, "SELECT * FROM insert_article_revision('a-uno', " + revision + ", 'import', "
        + q(content("X")) + "::jsonb, NULL)");
    deniedAs(IMPORTER_LOGIN, createRevisionSql("a-uno", revision, content("X")));
    // Ni asumir el rol dueño de la operación.
    txAsFails(IMPORTER_LOGIN, NO_PRIVILEGE, "SET LOCAL ROLE cms_import_writer");
    // Inserta el catálogo, pero no lo modifica ni lo borra.
    txAs(IMPORTER_LOGIN, "INSERT INTO tag (id, slug, label, catalog_position) VALUES ('nueva', 'nueva', 'Nueva', 9)");
    deniedAs(IMPORTER_LOGIN, "UPDATE tag SET label = 'Cambio' WHERE id = 'nueva'");
    deniedAs(IMPORTER_LOGIN, "DELETE FROM tag WHERE id = 'nueva'");
    deniedAs(IMPORTER_LOGIN, "UPDATE media SET retired = true WHERE id = 'm-uno'");
    deniedAs(IMPORTER_LOGIN, "TRUNCATE media_reserved_use");
  }

  @Test
  void laAplicacionYPublicNoPuedenImportarNiMoverElPunteroPublicado() throws Exception {
    txAs(IMPORTER_LOGIN, importSql(record("a-uno", 1), BLOCKS, 1));
    deniedAs(APP_LOGIN, importSql(record("a-dos", 2), BLOCKS, 2));
    deniedAs(APP_LOGIN, "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
    deniedAs(APP_LOGIN, "SET LOCAL cms.revision_writer = 'on'",
        "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
    txAsFails(APP_LOGIN, NO_PRIVILEGE, "SET LOCAL ROLE cms_import_writer");
    // Sin pertenencia a ningún rol (PUBLIC): sin USAGE en el esquema desde la 6.2.1, ni siquiera ve
    // los objetos; con el esquema explícito, el rechazo es de permisos.
    rejectedAs(NOBODY_LOGIN, importSql(record("a-dos", 2), BLOCKS, 2));
    rejectedAs(NOBODY_LOGIN, "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
    deniedAs(NOBODY_LOGIN, "UPDATE public.article SET published_revision_id = NULL WHERE id = 'a-uno'");
    txAsFails(NOBODY_LOGIN, NO_PRIVILEGE, "SET LOCAL ROLE cms_import_writer");
    deniedAs(NOBODY_LOGIN, "SELECT count(*) FROM public.article");
  }

  @Test
  void elPunteroPublicadoNoDependeDeUnaVariableDeSesion() throws Exception {
    txAs(IMPORTER_LOGIN, importSql(record("a-uno", 1), BLOCKS, 1));
    // Ni el propietario de las tablas (cms_owner) ni el superusuario con un UPDATE directo, active
    // la variable que active: la regla comprueba la identidad del rol (current_user), no un indicador.
    for (String setting : List.of("cms.revision_writer", "cms.publication_writer")) {
      asOwnerFails(NO_PRIVILEGE, "SET LOCAL " + setting + " = 'on'",
          "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
      txFails(NO_PRIVILEGE, "SET LOCAL " + setting + " = 'on'",
          "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'");
    }
    asOwnerFails(NO_PRIVILEGE, "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'");
    txFails(NO_PRIVILEGE, "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'");
    // La regla (V6) exige el rol definidor (current_user) Y una sesión real autorizada
    // (session_user); ninguna variable de sesión.
    assertThat(one(String.class, "SELECT prosrc FROM pg_proc WHERE proname = 'guard_article_pointers'"))
        .contains("current_user = 'cms_import_writer'")
        .contains("current_user = 'cms_revision_writer'")
        .contains("pg_has_role(session_user, 'cms_importer', 'USAGE')")
        .doesNotContain("current_setting");
  }

  @ParameterizedTest
  @ValueSource(strings = {APP_LOGIN, IMPORTER_LOGIN, NOBODY_LOGIN})
  void ningunUsuarioDePruebaPuedeEjecutarElNucleoInterno(String user) {
    rejectedAs(user, "SELECT * FROM insert_article_revision('a-uno', NULL, 'import', " + q(content("X")) + "::jsonb, NULL)");
  }

  @Test
  void laOperacionEstaEndurecida() throws Exception {
    assertThat(one(String.class, "SELECT p.prosecdef || '|' || array_to_string(p.proconfig, ',') || '|'"
            + " || r.rolname || '|' || r.rolsuper || '|' || r.rolcanlogin"
            + " FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner WHERE p.proname = 'import_published_article'"))
        .isEqualTo("true|search_path=pg_catalog, public, pg_temp|cms_import_writer|false|false");
    assertThat(one(Boolean.class, "SELECT EXISTS (SELECT 1 FROM pg_proc p, aclexplode(p.proacl) a"
        + " WHERE p.proname = 'import_published_article' AND a.grantee = 0)")).as("PUBLIC sin EXECUTE").isFalse();
    assertThat(column("SELECT r.rolname FROM pg_roles r WHERE has_function_privilege(r.oid,"
            + " 'import_published_article(jsonb,jsonb,integer)', 'EXECUTE') AND NOT r.rolsuper"
            + " AND r.rolname NOT LIKE 'pg\\_%' ORDER BY 1"))
        // Su dueño, el rol importador y su login, y cms_owner, que la hereda del rol dueño: INHERIT
        // es necesario para que V4 pueda retirar el EXECUTE de PUBLIC tras transferirla (límite
        // documentado). cms_migrator es NOINHERIT: no la hereda sin SET ROLE.
        .containsExactly("cms_import_writer", "cms_importer", "cms_owner", "cms_tool");
  }
}
