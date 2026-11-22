package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import com.cristianatienza.cms.testsupport.TestDatabase;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de los tests de esquema: un PostgreSQL 18.6 real (la misma imagen que infra/local),
 * inicializado con el script de roles del repositorio y compartido por todas las clases. Antes
 * de cada test, el esquema se borra y se migra como en producción: cms_migrator con SET ROLE
 * cms_owner (TestDatabase). Las revisiones no se pueden borrar ni truncar, así que cada test
 * parte de cero. Las conexiones sin usuario explícito son del superusuario del contenedor y solo
 * sirven para preparar e inspeccionar datos.
 */
public abstract class PostgresSchemaTest {

  protected static final PostgreSQLContainer POSTGRES = TestDatabase.POSTGRES;

  /** SQLSTATE de los errores que se comprueban. */
  protected static final String UNIQUE = "23505";
  protected static final String FOREIGN_KEY = "23503";
  /** Borrar algo referenciado con ON DELETE RESTRICT. */
  protected static final String RESTRICT = "23001";
  protected static final String CHECK = "23514";
  protected static final String NOT_NULL = "23502";
  protected static final String INTEGRITY = "23000";
  protected static final String NO_PRIVILEGE = "42501";
  protected static final String INVALID_PARAMETER = "22023";
  protected static final String REVISION_CONFLICT = "CM409";

  /** Flyway como el contenedor de migraciones (cms_migrator + SET ROLE cms_owner). */
  protected static Flyway flyway() {
    return TestDatabase.migrator();
  }

  @BeforeEach
  protected void migrateFromScratch() throws SQLException {
    TestDatabase.migrateFromScratch();
    createNobodyLogin();
  }

  // --- Usuarios reales (sin superusuario) ------------------------------------------------------
  //
  // Para demostrar la separación de privilegios con conexiones propias, no con SET ROLE desde un
  // superusuario: cada login inicia sesión con su contraseña y hereda solo los privilegios del
  // rol del que es miembro. cms_api y cms_tool son los logins reales que crea el script de
  // inicio; cms_nobody_login existe solo en los tests.

  /** Login real de la herramienta de contenido: miembro únicamente de cms_importer. */
  protected static final String IMPORTER_LOGIN = "cms_tool";
  /** Login real de la API: miembro únicamente de cms_app. */
  protected static final String APP_LOGIN = "cms_api";
  /** Sin pertenencia a ningún rol: solo lo que tiene PUBLIC (solo en los tests). */
  protected static final String NOBODY_LOGIN = "cms_nobody_login";
  private static final String NOBODY_PASSWORD = "test-cms-nobody-password-000";

  private static void createNobodyLogin() throws SQLException {
    tx("""
        DO $$
        BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cms_nobody_login') THEN
            CREATE ROLE cms_nobody_login LOGIN PASSWORD 'test-cms-nobody-password-000' NOSUPERUSER NOCREATEDB NOCREATEROLE;
          END IF;
        END
        $$""",
        // Sin CONNECT para PUBLIC (script de inicio): el login de prueba lo recibe explícitamente.
        "GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO cms_nobody_login");
  }

  private static String passwordOf(String user) {
    return NOBODY_LOGIN.equals(user) ? NOBODY_PASSWORD : TestDatabase.password(user);
  }

  /** Conexión propia de un login (no un SET ROLE). */
  protected static Connection connectAs(String user) throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, passwordOf(user));
  }

  /** DataSource con las credenciales propias de un login. */
  protected static javax.sql.DataSource dataSourceAs(String user) {
    return new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), user, passwordOf(user));
  }

  /** Sentencias como propietario del esquema: cms_migrator con SET LOCAL ROLE cms_owner. */
  protected static void asOwner(String... statements) throws SQLException {
    String[] all = new String[statements.length + 1];
    all[0] = "SET LOCAL ROLE cms_owner";
    System.arraycopy(statements, 0, all, 1, statements.length);
    txAs("cms_migrator", all);
  }

  protected static SQLException asOwnerFails(String sqlState, String... statements) {
    String[] all = new String[statements.length + 1];
    all[0] = "SET LOCAL ROLE cms_owner";
    System.arraycopy(statements, 0, all, 1, statements.length);
    return txAsFails("cms_migrator", sqlState, all);
  }

  /** Sentencias en una transacción con la conexión propia de un usuario de prueba. */
  protected static void txAs(String user, String... statements) throws SQLException {
    try (Connection c = connectAs(user)) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        for (String sql : statements) {
          s.execute(sql);
        }
      }
      c.commit();
    }
  }

  protected static SQLException txAsFails(String user, String sqlState, String... statements) {
    try {
      txAs(user, statements);
    } catch (SQLException e) {
      assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
      return e;
    }
    return fail("se esperaba un error " + sqlState + " como " + user + " y la transacción se confirmó");
  }

  /** Como ese usuario, lo rechaza el sistema de permisos de PostgreSQL («permission denied»). */
  protected static void deniedAs(String user, String... statements) {
    assertThat(txAsFails(user, NO_PRIVILEGE, statements).getMessage()).contains("permission denied");
  }

  /**
   * Para un login sin ningún rol: desde la 6.2.1 PUBLIC no tiene USAGE en el esquema, así que el
   * rechazo puede ser «permission denied» o que el objeto ni siquiera sea visible (no existe).
   */
  protected static void rejectedAs(String user, String... statements) {
    try {
      txAs(user, statements);
    } catch (SQLException e) {
      assertThat(e.getSQLState()).as(e.getMessage()).isIn(NO_PRIVILEGE, "42883", "42P01", "3F000");
      return;
    }
    fail("se esperaba un rechazo como " + user + " y la transacción se confirmó");
  }

  // --- Conexión y ejecución ------------------------------------------------------------------

  protected static Connection connect() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Ejecuta las sentencias en una sola transacción (las restricciones diferidas, al confirmar). */
  protected static void tx(String... statements) throws SQLException {
    try (Connection c = connect()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        for (String sql : statements) {
          s.execute(sql);
        }
      }
      c.commit();
    }
  }

  /** Igual que {@link #tx}, pero espera un error con ese SQLSTATE y lo devuelve. */
  protected static SQLException txFails(String sqlState, String... statements) {
    try {
      tx(statements);
    } catch (SQLException e) {
      assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState);
      return e;
    }
    return fail("se esperaba un error " + sqlState + " y la transacción se confirmó");
  }

  /** Igual que {@link #tx}, pero con el rol de la aplicación (SET ROLE cms_app). */
  protected static void asApp(String... statements) throws SQLException {
    String[] all = new String[statements.length + 1];
    all[0] = "SET LOCAL ROLE cms_app";
    System.arraycopy(statements, 0, all, 1, statements.length);
    tx(all);
  }

  /**
   * Como cms_app, la operación la rechaza el sistema de permisos de PostgreSQL («permission
   * denied»), no un trigger ni otra regla.
   */
  protected static void asAppDenied(String... statements) {
    assertThat(asAppFails(NO_PRIVILEGE, statements).getMessage()).contains("permission denied");
  }

  protected static SQLException asAppFails(String sqlState, String... statements) {
    String[] all = new String[statements.length + 1];
    all[0] = "SET LOCAL ROLE cms_app";
    System.arraycopy(statements, 0, all, 1, statements.length);
    return txFails(sqlState, all);
  }

  /** Primera columna de la primera fila. */
  protected static <T> T one(Class<T> type, String sql, Object... params) throws SQLException {
    try (Connection c = connect();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("sin filas: " + sql).isTrue();
        return rs.getObject(1, type);
      }
    }
  }

  /** Primera columna de todas las filas. */
  protected static List<String> column(String sql, Object... params) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = connect();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          values.add(rs.getString(1));
        }
      }
    }
    return values;
  }

  // --- Datos de prueba ------------------------------------------------------------------------

  protected static String q(String value) {
    return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
  }

  /** Imagen externa de Wikimedia con su original (las dos filas, en la misma transacción). */
  protected static String[] media(String id, int position) {
    return new String[] {
      "INSERT INTO media (id, path, width, height, widths, alt, caption, focal_x, focal_y, placeholder,"
          + " credit_author, credit_source, credit_source_url, credit_license, credit_license_url, demo,"
          + " catalog_position) VALUES ("
          + q(id) + ", " + q("viajes/" + id + ".0123abcd") + ", 1600, 1000, '{640,1024,1600}', 'Una foto',"
          + " 'Pie', 0.5, 0.5, 'data:image/webp;base64,UklGRg==', 'Autora de prueba', 'wikimedia', "
          + q("https://commons.wikimedia.org/wiki/File:" + id) + ", 'CC-BY-4.0',"
          + " 'https://creativecommons.org/licenses/by/4.0/', true, " + position + ")",
      "INSERT INTO media_original (media_id, file, format, width, height, bytes, sha256, url) VALUES ("
          + q(id) + ", " + q(id + ".jpg") + ", 'jpeg', 3000, 2000, 1000000, md5(" + q(id) + ") || md5("
          + q(id + "-2") + "), " + q("https://upload.wikimedia.org/" + id + ".jpg") + ")"
    };
  }

  /** Catálogo mínimo con una entidad de cada tipo, coherente y en una transacción. */
  protected static void seedCatalog() throws SQLException {
    List<String> all = new ArrayList<>();
    all.add("INSERT INTO category (id, slug, name, description, catalog_position) VALUES"
        + " ('viajes', 'viajes', 'Viajes', 'Rutas', 1), ('fotografia', 'fotografia', 'Fotografía', 'Series', 2),"
        + " ('motor', 'motor', 'Motor', 'Máquinas', 3), ('experiencias', 'experiencias', 'Experiencias', 'Vida', 4),"
        + " ('tecnologia', 'tecnologia', 'Tecnología', 'Código', 5)");
    all.add("INSERT INTO tag (id, slug, label, catalog_position) VALUES"
        + " ('costa', 'costa', 'Costa', 1), ('mar', 'mar', 'Mar', 2), ('angular', 'angular', 'Angular', 3)");
    for (String[] m :
        List.of(media("m-uno", 1), media("m-dos", 2), media("m-tres", 3), media("m-cuatro", 4), media("m-libre", 5))) {
      all.addAll(List.of(m));
    }
    all.add("INSERT INTO author (id, name, role, catalog_position) VALUES"
        + " ('cristian-atienza', 'Cristian Atienza', 'Autor y editor', 1)");
    all.add("INSERT INTO destination (id, slug, name, country, region, lat, lng, summary, cover_media_id, demo,"
        + " catalog_position) VALUES"
        + " ('d-uno', 'd-uno', 'Uno', 'ES', 'Girona', 41.7197, 2.933, 'Primero', 'm-uno', true, 1),"
        + " ('d-dos', 'd-dos', 'Dos', 'ES', NULL, 41.9, 3.1, 'Segundo', NULL, true, 2),"
        + " ('d-tres', 'd-tres', 'Tres', 'IT', NULL, 46.5, 10.4, 'Tercero', NULL, true, 3)");
    all.add("INSERT INTO trip (id, slug, title, summary, cover_media_id, demo, catalog_position) VALUES"
        + " ('t-uno', 't-uno', 'Ruta', 'Dos paradas', 'm-dos', true, 1)");
    all.add("INSERT INTO trip_stop (trip_id, stop_order, destination_id, distance_km, mode) VALUES"
        + " ('t-uno', 1, 'd-uno', NULL, 'coche'), ('t-uno', 2, 'd-dos', 42, 'coche')");
    all.add("INSERT INTO project (id, slug, name, summary, status, year, cover_media_id, demo, catalog_position)"
        + " VALUES ('p-uno', 'p-uno', 'Proyecto', 'Uno', 'activo', 2026, 'm-tres', true, 1)");
    all.add("INSERT INTO project_stack_item (project_id, position, tag_id) VALUES ('p-uno', 1, 'angular')");
    all.add("INSERT INTO gallery (id, slug, title, layout, demo, catalog_position) VALUES"
        + " ('g-uno', 'g-uno', 'Serie', 'grid', true, 1)");
    all.add("INSERT INTO gallery_item (gallery_id, position, media_id) VALUES ('g-uno', 1, 'm-uno')");
    tx(all.toArray(String[]::new));
  }

  /** Artículo sin revisiones (la identidad). */
  protected static void createArticle(String id, String category, Integer order, int position) throws SQLException {
    tx("INSERT INTO article (id, slug, category_id, editorial_order, featured, demo, published_at, catalog_position)"
        + " VALUES (" + q(id) + ", " + q(id) + ", " + q(category) + ", " + order + ", false, true, '2026-09-18', "
        + position + ")");
  }

  /** Contenido mínimo válido de una revisión, con un cuerpo dado. */
  protected static String content(String title, String blocksJson) {
    return "{\"title\": " + json(title) + ", \"dek\": \"Entradilla\", \"excerpt\": \"Extracto\","
        + " \"format\": \"guia\", \"author\": \"cristian-atienza\", \"tags\": [\"costa\"],"
        + " \"readingMinutes\": 1, \"blocks\": " + blocksJson + "}";
  }

  protected static String content(String title) {
    return content(title, "[{\"type\": \"text\", \"html\": \"<p>Hola</p>\"}]");
  }

  protected static String json(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** create_article_revision: devuelve el id de la revisión creada. */
  protected static long createRevision(String articleId, Long parentId, String contentJson) throws SQLException {
    return one(Long.class,
        "SELECT new_revision_id FROM create_article_revision(?::text, ?::bigint, 'edit', ?::jsonb)",
        articleId, parentId, contentJson);
  }

  protected static String createRevisionSql(String articleId, Long parentId, String contentJson) {
    return "SELECT * FROM create_article_revision(" + q(articleId) + ", " + parentId + ", 'edit', "
        + q(contentJson) + "::jsonb)";
  }
}
