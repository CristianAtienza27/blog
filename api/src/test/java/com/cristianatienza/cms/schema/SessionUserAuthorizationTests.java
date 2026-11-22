package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Autorización por session_user (V6). Todas las comprobaciones usan conexiones reales con los
 * logins de cada proceso (cms_tool, cms_api, cms_migrator), nunca el superusuario; el
 * superusuario solo prepara el catálogo de prueba.
 */
class SessionUserAuthorizationTests extends PostgresSchemaTest {

  private static final String RECORD = "{\"id\": \"%s\", \"slug\": \"%s\", \"category\": \"motor\", \"order\": %d,"
      + " \"publishedAt\": \"2026-09-18\", \"featured\": false, \"demo\": true, \"title\": \"Título\","
      + " \"dek\": \"Entradilla\", \"excerpt\": \"Extracto\", \"format\": \"guia\", \"author\": \"cristian-atienza\","
      + " \"tags\": [\"costa\"], \"readingMinutes\": 1}";
  private static final String BLOCKS = "[{\"type\": \"text\", \"html\": \"<p>Hola</p>\"}]";

  private static String importSql(String id, int position) {
    return "SELECT * FROM import_published_article(" + q(RECORD.formatted(id, id, position)) + "::jsonb, "
        + q(BLOCKS) + "::jsonb, " + position + ")";
  }

  /** Sesión de cms_migrator con la cadena de SET ROLE indicada y las sentencias, en una transacción. */
  private static String[] migratorChain(String chain, String... statements) {
    List<String> all = new ArrayList<>();
    for (String role : chain.split(">")) {
      all.add("SET LOCAL ROLE " + role);
    }
    all.addAll(List.of(statements));
    return all.toArray(String[]::new);
  }

  private long revision;

  @BeforeEach
  void contenidoImportadoPorCmsTool() throws Exception {
    seedCatalog();
    txAs(IMPORTER_LOGIN, importSql("a-uno", 1));
    revision = one(Long.class, "SELECT published_revision_id FROM article WHERE id = 'a-uno'");
  }

  private static String snapshot() throws SQLException {
    return one(String.class, "SELECT (SELECT count(*) FROM article) || '/' || (SELECT count(*) FROM article_revision)"
        + " || '/' || (SELECT count(*) FROM article_revision_tag) || '/' || (SELECT string_agg(id || ':' ||"
        + " COALESCE(head_revision_id::text, '-') || ':' || COALESCE(published_revision_id::text, '-'), ',' ORDER BY id)"
        + " FROM article)");
  }

  // --- Identidad ---------------------------------------------------------------------------------

  @Test
  void sessionUserSigueSiendoCmsMigratorAunqueCambieCurrentUser() throws Exception {
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      s.execute("SET LOCAL ROLE cms_import_writer");
      try (ResultSet rs = s.executeQuery("SELECT session_user || '|' || current_user")) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("cms_migrator|cms_import_writer");
      }
      c.rollback();
    }
  }

  // --- Importación ---------------------------------------------------------------------------------

  @Test
  void cmsToolImportaYConservaSusOperaciones() throws Exception {
    txAs(IMPORTER_LOGIN, importSql("a-dos", 2));
    assertThat(one(Long.class, "SELECT count(*) FROM article WHERE published_revision_id IS NOT NULL")).isEqualTo(2L);
    // Lectura del contenido publicado (exportación).
    try (Connection c = connectAs(IMPORTER_LOGIN); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT article_revision_content(" + revision + ") ->> 'title'")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("Título");
    }
  }

  @Test
  void cmsApiNoPuedeImportar() {
    deniedAs(APP_LOGIN, importSql("a-dos", 2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "cms_owner", "cms_owner>cms_import_writer", "cms_owner>cms_revision_writer"})
  void cmsMigratorNoPuedeImportarConNingunSetRole(String chain) throws Exception {
    String before = snapshot();
    if (chain.isEmpty()) {
      rejectedAs("cms_migrator", importSql("a-dos", 2));
    } else {
      SQLException e = txAsFails("cms_migrator", NO_PRIVILEGE, migratorChain(chain, importSql("a-dos", 2)));
      if (!chain.endsWith("cms_revision_writer")) {
        // El privilegio heredado le deja ejecutarla; la rechaza la comprobación de session_user.
        assertThat(e.getMessage()).contains("la importación solo puede ejecutarla una conexión del importador")
            .contains("sesión: cms_migrator");
      }
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  // --- Rutas alternativas para modificar contenido --------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"cms_owner", "cms_owner>cms_revision_writer", "cms_owner>cms_import_writer"})
  void cmsMigratorNoModificaContenidoPorNingunaRuta(String chain) throws Exception {
    String before = snapshot();
    String[] routes = {
      // El núcleo interno, directamente.
      "SELECT * FROM insert_article_revision('a-uno', " + revision + ", 'edit', " + q(content("X")) + "::jsonb, NULL)",
      // Las operaciones públicas de revisión.
      createRevisionSql("a-uno", revision, content("X")),
      "SELECT * FROM restore_article_revision(" + revision + ", " + revision + ")",
      // Los punteros, con UPDATE directo.
      "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'",
      "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'",
      // Las tablas de revisión, con el indicador del núcleo activado a mano.
      "SET LOCAL cms.revision_writer = 'on'",
    };
    for (String route : routes) {
      if (route.startsWith("SET LOCAL cms")) {
        continue;
      }
      SQLException e = txAsFails("cms_migrator", NO_PRIVILEGE, migratorChain(chain, route));
      assertThat(e.getMessage()).as(chain + " · " + route).containsAnyOf("sesión: cms_migrator", "permission denied");
    }
    for (String insert : List.of(
        "INSERT INTO article_revision (article_id, number, origin, title, dek, excerpt, format, author_id,"
            + " destinations_present, blocks, reading_minutes) VALUES ('a-uno', 9, 'edit', 'T', 'D', 'E', 'guia',"
            + " 'cristian-atienza', false, '[{\"type\": \"divider\", \"variant\": \"line\"}]', 1)",
        "INSERT INTO article_revision_tag (revision_id, position, tag_id) VALUES (" + revision + ", 9, 'mar')")) {
      txAsFails("cms_migrator", NO_PRIVILEGE, migratorChain(chain, "SET LOCAL cms.revision_writer = 'on'", insert));
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void dondeLosPermisosLoPermitiriaLoRechazaV6() {
    // Casos en los que las ACL sí lo permiten (el rol es dueño, tiene el privilegio por columna o
    // hereda el EXECUTE): solo la comprobación de session_user de V6 lo impide.
    String[][] cases = {
      {"cms_owner", "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'", "la revisión publicada"},
      {"cms_owner", "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'", "la revisión de trabajo"},
      {"cms_owner>cms_import_writer", "UPDATE article SET published_revision_id = NULL WHERE id = 'a-uno'", "la revisión publicada"},
      {"cms_owner>cms_revision_writer", "UPDATE article SET head_revision_id = NULL WHERE id = 'a-uno'", "la revisión de trabajo"},
      {"cms_owner", createRevisionSql("a-uno", revision, content("X")), "create_article_revision solo puede usarla"},
      {"cms_owner", "SELECT * FROM insert_article_revision('a-uno', " + revision + ", 'edit', " + q(content("X"))
          + "::jsonb, NULL)", "el núcleo de revisiones solo se usa"},
    };
    for (String[] c : cases) {
      assertThat(txAsFails("cms_migrator", NO_PRIVILEGE, migratorChain(c[0], c[1])).getMessage())
          .as(c[0] + " · " + c[1]).contains(c[2]).contains("sesión: cms_migrator");
    }
    // Inserción directa en la tabla de revisiones como su rol definidor, con el indicador.
    assertThat(txAsFails("cms_migrator", NO_PRIVILEGE, migratorChain("cms_owner>cms_revision_writer",
        "SET LOCAL cms.revision_writer = 'on'",
        "INSERT INTO article_revision (article_id, number, origin, title, dek, excerpt, format, author_id,"
            + " destinations_present, blocks, reading_minutes) VALUES ('a-uno', 9, 'edit', 'T', 'D', 'E', 'guia',"
            + " 'cristian-atienza', false, '[{\"type\": \"divider\", \"variant\": \"line\"}]', 1)")).getMessage())
        .contains("las revisiones solo se crean con").contains("sesión: cms_migrator");
  }

  // --- Operaciones legítimas de la API --------------------------------------------------------------

  @Test
  void cmsApiConservaSusOperacionesDeRevision() throws Exception {
    txAs(APP_LOGIN, "INSERT INTO article (id, slug, category_id, editorial_order, featured, demo, published_at,"
        + " catalog_position) VALUES ('a-api', 'a-api', 'motor', 7, false, true, '2026-09-18', 7)");
    long first;
    try (Connection c = connectAs(APP_LOGIN); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(createRevisionSql("a-api", null, content("Primera")))) {
      rs.next();
      first = rs.getLong("new_revision_id");
    }
    long second;
    try (Connection c = connectAs(APP_LOGIN); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(createRevisionSql("a-api", first, content("Segunda")))) {
      rs.next();
      second = rs.getLong("new_revision_id");
    }
    txAs(APP_LOGIN, "SELECT * FROM restore_article_revision(" + first + ", " + second + ")");
    assertThat(one(String.class, "SELECT string_agg(number || ':' || origin, ',' ORDER BY number) FROM article_revision"
        + " WHERE article_id = 'a-api'")).isEqualTo("1:edit,2:edit,3:restore");
    assertThat(one(Long.class, "SELECT head_revision_id FROM article WHERE id = 'a-api'"))
        .isEqualTo(one(Long.class, "SELECT id FROM article_revision WHERE article_id = 'a-api' AND number = 3"));
    // La publicación desde la API llega en la fase 6.3: hoy no puede mover el puntero publicado.
    deniedAs(APP_LOGIN, "UPDATE article SET published_revision_id = " + second + " WHERE id = 'a-api'");
  }

  // --- EXECUTE y propietarios después de V6 ----------------------------------------------------------

  @Test
  void publicNoTieneExecuteEnNingunaFuncion() throws Exception {
    assertThat(column("SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace"
        + " AND (p.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee = 0)) ORDER BY 1"))
        .isEmpty();
  }

  @Test
  void lasFuncionesNuevasTampocoTendranExecuteParaPublic() throws Exception {
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      s.execute("CREATE FUNCTION public.funcion_futura() RETURNS int LANGUAGE sql AS 'SELECT 1'");
      // Un ACL nulo es el de por defecto, que SÍ incluye EXECUTE para PUBLIC (corregido en la 6.2.3:
      // antes solo se miraba aclexplode, que no devuelve filas para un ACL nulo).
      try (ResultSet rs = s.executeQuery("SELECT p.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a"
          + " WHERE a.grantee = 0) FROM pg_proc p WHERE p.proname = 'funcion_futura'")) {
        rs.next();
        assertThat(rs.getBoolean(1)).as("privilegios por defecto sin PUBLIC").isFalse();
      }
      c.rollback();
    }
  }

  @Test
  void propietariosYAtributosDeLasFuncionesTrasV6() throws Exception {
    assertThat(column("SELECT p.proname || ':' || pg_get_userbyid(p.proowner) || ':' || p.prosecdef || ':'"
        + " || array_to_string(p.proconfig, ',') FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace"
        + " AND p.proname IN ('import_published_article', 'insert_article_revision', 'create_article_revision',"
        + " 'restore_article_revision', 'guard_article_pointers', 'guard_article_revision_insert',"
        + " 'guard_revision_child_insert') ORDER BY 1"))
        .containsExactly(
            "create_article_revision:cms_revision_writer:true:search_path=pg_catalog, public, pg_temp",
            "guard_article_pointers:cms_owner:false:search_path=pg_catalog, public, pg_temp",
            "guard_article_revision_insert:cms_owner:false:search_path=pg_catalog, public, pg_temp",
            "guard_revision_child_insert:cms_owner:false:search_path=pg_catalog, public, pg_temp",
            "import_published_article:cms_import_writer:true:search_path=pg_catalog, public, pg_temp",
            "insert_article_revision:cms_revision_writer:true:search_path=pg_catalog, public, pg_temp",
            "restore_article_revision:cms_revision_writer:true:search_path=pg_catalog, public, pg_temp");
    // Cada operación comprueba session_user; ninguna depende de una variable de sesión para ello.
    for (String fn : List.of("import_published_article", "insert_article_revision", "create_article_revision",
        "restore_article_revision", "guard_article_pointers", "guard_article_revision_insert", "guard_revision_child_insert")) {
      assertThat(one(String.class, "SELECT prosrc FROM pg_proc WHERE proname = ?", fn)).as(fn)
          .contains("pg_catalog.pg_has_role(session_user,");
    }
  }
}
