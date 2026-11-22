package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Grafo de roles y privilegios EFECTIVOS (lo que evalúa PostgreSQL con has_*_privilege), no solo
 * las concesiones escritas en las migraciones. Demuestra que ninguna pertenencia ni propiedad
 * genera permisos indirectos y que la importación solo está al alcance de cms_tool.
 */
class EffectivePrivilegeTests extends PostgresSchemaTest {

  private static final String TABLE_PRIVILEGES = "ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']";
  private static final String COLUMN_PRIVILEGES = "ARRAY['SELECT','INSERT','UPDATE','REFERENCES']";

  // --- Privilegios efectivos frente a concesiones directas ----------------------------------------

  /** Igualdad de conjuntos con un mensaje que muestra solo lo que sobra y lo que falta. */
  private static void assertSameSets(TreeSet<String> actual, TreeSet<String> expected) {
    TreeSet<String> extra = new TreeSet<>(actual);
    extra.removeAll(expected);
    TreeSet<String> missing = new TreeSet<>(expected);
    missing.removeAll(actual);
    assertThat(extra).as("privilegios efectivos de más").isEmpty();
    assertThat(missing).as("privilegios esperados que faltan").isEmpty();
  }

  /** Todo lo que el rol puede hacer en el esquema según PostgreSQL. */
  private static TreeSet<String> effective(String role) throws SQLException {
    TreeSet<String> out = new TreeSet<>();
    out.addAll(column("SELECT 'tabla ' || c.relname || ':' || p FROM pg_class c, unnest(" + TABLE_PRIVILEGES + ") p"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v')"
        + " AND has_table_privilege(?::regrole, c.oid, p)", role));
    out.addAll(column("SELECT 'columna ' || c.relname || '.' || a.attname || ':' || p"
        + " FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped,"
        + " unnest(" + COLUMN_PRIVILEGES + ") p"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')"
        + " AND has_column_privilege(?::regrole, c.oid, a.attnum, p) AND NOT has_table_privilege(?::regrole, c.oid, p)",
        role, role));
    out.addAll(column("SELECT 'secuencia ' || c.relname || ':' || p FROM pg_class c, unnest(ARRAY['USAGE','SELECT','UPDATE']) p"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S' AND has_sequence_privilege(?::regrole, c.oid, p)",
        role));
    out.addAll(column("SELECT 'función ' || p.oid::regprocedure FROM pg_proc p"
        + " WHERE p.pronamespace = 'public'::regnamespace AND has_function_privilege(?::regrole, p.oid, 'EXECUTE')", role));
    out.addAll(column("SELECT 'esquema public:' || p FROM unnest(ARRAY['USAGE','CREATE']) p"
        + " WHERE has_schema_privilege(?::regrole, 'public', p)", role));
    return out;
  }

  /** Lo concedido directamente al rol o a PUBLIC, y lo que posee (sin pasar por pertenencias). */
  private static TreeSet<String> direct(String role) throws SQLException {
    TreeSet<String> out = new TreeSet<>();
    out.addAll(column("SELECT 'tabla ' || c.relname || ':' || a.privilege_type FROM pg_class c, aclexplode(c.relacl) a"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v')"
        + " AND a.grantee IN (?::regrole, 0)", role));
    out.addAll(column("SELECT 'tabla ' || c.relname || ':' || p FROM pg_class c, unnest(" + TABLE_PRIVILEGES + ") p"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v') AND c.relowner = ?::regrole",
        role));
    out.addAll(column("SELECT 'columna ' || c.relname || '.' || at.attname || ':' || a.privilege_type"
        + " FROM pg_class c JOIN pg_attribute at ON at.attrelid = c.oid AND at.attnum > 0, aclexplode(at.attacl) a"
        + " WHERE c.relnamespace = 'public'::regnamespace AND a.grantee IN (?::regrole, 0)", role));
    out.addAll(column("SELECT 'secuencia ' || c.relname || ':' || a.privilege_type FROM pg_class c, aclexplode(c.relacl) a"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S' AND a.grantee IN (?::regrole, 0)", role));
    out.addAll(column("SELECT 'secuencia ' || c.relname || ':' || p FROM pg_class c, unnest(ARRAY['USAGE','SELECT','UPDATE']) p"
        + " WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S' AND c.relowner = ?::regrole", role));
    // Funciones: las que posee, las concedidas a él o a PUBLIC y las que conservan el EXECUTE por
    // defecto de PUBLIC (proacl nulo).
    out.addAll(column("SELECT 'función ' || p.oid::regprocedure FROM pg_proc p"
        + " WHERE p.pronamespace = 'public'::regnamespace AND (p.proowner = ?::regrole OR p.proacl IS NULL"
        + " OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee IN (?::regrole, 0) AND a.privilege_type = 'EXECUTE'))",
        role, role));
    out.addAll(column("SELECT 'esquema public:' || a.privilege_type FROM pg_namespace n, aclexplode(n.nspacl) a"
        + " WHERE n.nspname = 'public' AND a.grantee IN (?::regrole, 0)", role));
    out.addAll(column("SELECT 'esquema public:' || p FROM pg_namespace n, unnest(ARRAY['USAGE','CREATE']) p"
        + " WHERE n.nspname = 'public' AND n.nspowner = ?::regrole", role));
    return out;
  }

  @ParameterizedTest
  @CsvSource({"cms_app", "cms_importer", "cms_security_admin", "cms_audit_maintainer", "cms_revision_writer",
      "cms_import_writer", "cms_audit_retention", "cms_article_publisher"})
  void ningunRolTienePrivilegiosIndirectos(String role) throws Exception {
    // Lo efectivo es exactamente lo concedido directamente (o a PUBLIC) y lo que posee.
    assertSameSets(effective(role), direct(role));
  }

  @Test
  void elPropietarioSoloHeredaLasFuncionesTransferidas() throws Exception {
    // cms_owner: lo suyo (todas las tablas, el esquema, sus funciones) más, por INHERIT en los
    // dueños de funciones, exactamente el EXECUTE de las funciones que V3, V4, V8 y V11 les transfirieron.
    // Nada más: ni pertenece a cms_app ni a cms_importer.
    TreeSet<String> expected = direct("cms_owner");
    expected.addAll(column("SELECT 'función ' || p.oid::regprocedure FROM pg_proc p"
        + " WHERE p.pronamespace = 'public'::regnamespace"
        + " AND p.proowner IN ('cms_revision_writer'::regrole, 'cms_import_writer'::regrole, 'cms_audit_retention'::regrole,"
        + " 'cms_enrollment_writer'::regrole, 'cms_article_publisher'::regrole)"));
    assertSameSets(effective("cms_owner"), expected);
    assertThat(one(Boolean.class, "SELECT pg_has_role('cms_owner', 'cms_importer', 'MEMBER')"
        + " OR pg_has_role('cms_owner', 'cms_app', 'MEMBER')")).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"cms_api, cms_app", "cms_tool, cms_importer", "cms_security_tool, cms_security_admin",
      "cms_maintenance, cms_audit_maintainer"})
  void cadaLoginTieneExactamenteLosPrivilegiosDeSuRol(String login, String role) throws Exception {
    assertSameSets(effective(login), effective(role));
  }

  @Test
  void cmsMigratorNoTieneMasPrivilegiosQueUnLoginSinRoles() throws Exception {
    // NOINHERIT: sin SET ROLE, nada más que lo de PUBLIC (igual que cms_nobody_login).
    assertSameSets(effective("cms_migrator"), effective(NOBODY_LOGIN));
    assertThat(effective("cms_migrator")).noneMatch(p -> p.startsWith("tabla ") || p.startsWith("esquema "));
  }

  // --- Grafo completo de pertenencias -------------------------------------------------------------

  @Test
  void elGrafoDePertenenciasEsElPrevisto() throws Exception {
    assertThat(column("SELECT u.rolname || ' -> ' || g.rolname || ' (inherit=' || m.inherit_option || ', set='"
        + " || m.set_option || ', admin=' || m.admin_option || ')' FROM pg_auth_members m"
        + " JOIN pg_roles u ON u.oid = m.member JOIN pg_roles g ON g.oid = m.roleid"
        + " WHERE u.rolname LIKE 'cms\\_%' OR g.rolname LIKE 'cms\\_%' ORDER BY 1"))
        .containsExactly(
            "cms_api -> cms_app (inherit=true, set=true, admin=false)",
            "cms_maintenance -> cms_audit_maintainer (inherit=true, set=true, admin=false)",
            "cms_migrator -> cms_owner (inherit=false, set=true, admin=false)",
            "cms_owner -> cms_article_publisher (inherit=true, set=true, admin=false)",
            "cms_owner -> cms_audit_retention (inherit=true, set=true, admin=false)",
            "cms_owner -> cms_enrollment_writer (inherit=true, set=true, admin=false)",
            "cms_owner -> cms_import_writer (inherit=true, set=true, admin=false)",
            "cms_owner -> cms_revision_writer (inherit=true, set=true, admin=false)",
            "cms_security_tool -> cms_security_admin (inherit=true, set=true, admin=false)",
            "cms_tool -> cms_importer (inherit=true, set=true, admin=false)");
  }

  // --- Importación: solo cms_tool --------------------------------------------------------------------

  private static String importCall(String schemaPrefix, String id, int position) {
    return "SELECT * FROM " + schemaPrefix + "import_published_article(" + q("{\"id\": \"" + id + "\", \"slug\": \"" + id
        + "\", \"category\": \"motor\", \"order\": " + position + ", \"publishedAt\": \"2026-09-18\","
        + " \"featured\": false, \"demo\": true, \"title\": \"Título\", \"dek\": \"Entradilla\", \"excerpt\": \"Extracto\","
        + " \"format\": \"guia\", \"author\": \"cristian-atienza\", \"tags\": [\"costa\"], \"readingMinutes\": 1}")
        + "::jsonb, " + q("[{\"type\": \"text\", \"html\": \"<p>Hola</p>\"}]") + "::jsonb, " + position + ")";
  }

  @Test
  void soloCmsToolPuedeImportar() throws Exception {
    seedCatalog();
    txAs(IMPORTER_LOGIN, importCall("", "a-uno", 1));
    assertThat(one(Long.class, "SELECT count(*) FROM article WHERE published_revision_id IS NOT NULL")).isEqualTo(1L);

    deniedAs(APP_LOGIN, importCall("", "a-dos", 2));
    // cms_migrator sin SET ROLE: ni siquiera ve el esquema; con el nombre completo, sin permiso.
    rejectedAs("cms_migrator", importCall("", "a-dos", 2));
    deniedAs("cms_migrator", importCall("public.", "a-dos", 2));
    assertThat(one(Long.class, "SELECT count(*) FROM article")).isEqualTo(1L);
  }

  @Test
  void conSetRoleCmsOwnerElPrivilegioHeredadoNoBastaParaImportar() throws Exception {
    // cms_owner hereda el EXECUTE de la importación (INHERIT, necesario para V3 y V4), pero la
    // operación comprueba session_user (V6): la sesión sigue siendo cms_migrator.
    seedCatalog();
    assertThat(one(Boolean.class, "SELECT has_function_privilege('cms_owner',"
        + " 'import_published_article(jsonb,jsonb,integer)', 'EXECUTE')")).isTrue();
    assertThat(asOwnerFails(NO_PRIVILEGE, importCall("", "a-propietario", 1)).getMessage())
        .contains("la importación solo puede ejecutarla una conexión del importador (sesión: cms_migrator, rol: cms_import_writer)");
    assertThat(one(Long.class, "SELECT count(*) FROM article")).isZero();
  }

  @Test
  void elPropietarioConservaLosPrivilegiosDeMigracion() throws Exception {
    // Como en una migración: crear, alterar y borrar objetos del esquema y dar permisos. Se deshace.
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      s.execute("CREATE TABLE public.prueba_migracion (id int PRIMARY KEY)");
      s.execute("ALTER TABLE public.tag ADD COLUMN prueba int");
      s.execute("CREATE INDEX prueba_idx ON public.tag (prueba)");
      s.execute("GRANT SELECT ON public.prueba_migracion TO cms_app");
      s.execute("DROP TABLE public.prueba_migracion");
      s.execute("ALTER TABLE public.tag DROP COLUMN prueba");
      c.rollback();
    }
  }

  @Test
  void soloElPropietarioPuedeAsumirALosDuenosDeFunciones() throws Exception {
    // Para transferir funciones en las migraciones, cms_owner necesita la opción SET sobre sus
    // dueños, así que una sesión de cms_migrator puede encadenar SET ROLE y actuar como ellos.
    // Ninguna otra cuenta puede.
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      s.execute("SET LOCAL ROLE cms_import_writer");
      c.rollback();
    }
    for (String login : List.of("cms_api", "cms_tool", "cms_security_tool", "cms_maintenance")) {
      txAsFails(login, NO_PRIVILEGE, "SET LOCAL ROLE cms_import_writer");
      txAsFails(login, NO_PRIVILEGE, "SET LOCAL ROLE cms_revision_writer");
    }
  }
}
