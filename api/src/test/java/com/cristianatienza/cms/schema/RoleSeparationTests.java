package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.testsupport.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Separación de roles de la fase 6.2.1, con los logins reales que crea el script de inicio
 * (infra/local/postgres-init) y conexiones propias, sin SET ROLE desde un superusuario.
 */
class RoleSeparationTests extends PostgresSchemaTest {

  private static final List<String> LOGINS = List.of("cms_api", "cms_maintenance", "cms_migrator", "cms_security_tool", "cms_tool");
  private static final List<String> GROUPS = List.of("cms_app", "cms_article_publisher", "cms_audit_maintainer", "cms_audit_retention",
      "cms_import_writer", "cms_importer", "cms_owner", "cms_revision_writer", "cms_security_admin");

  @Test
  void cadaLoginPerteneceSoloASuRolYSinAtributosPeligrosos() throws Exception {
    assertThat(column("SELECT r.rolname || ':' || r.rolinherit || ':' || COALESCE((SELECT string_agg(g.rolname || '/inherit='"
            + " || m.inherit_option || '/set=' || m.set_option, ',' ORDER BY g.rolname) FROM pg_auth_members m"
            + " JOIN pg_roles g ON g.oid = m.roleid WHERE m.member = r.oid), '-')"
            + " FROM pg_roles r WHERE r.rolname = ANY (?) ORDER BY r.rolname", (Object) LOGINS.toArray(String[]::new)))
        .containsExactly(
            "cms_api:true:cms_app/inherit=true/set=true",
            "cms_maintenance:true:cms_audit_maintainer/inherit=true/set=true",
            "cms_migrator:false:cms_owner/inherit=false/set=true",
            "cms_security_tool:true:cms_security_admin/inherit=true/set=true",
            "cms_tool:true:cms_importer/inherit=true/set=true");
    assertThat(column("SELECT rolname FROM pg_roles WHERE rolname = ANY (?) AND rolcanlogin AND NOT rolsuper"
        + " AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls ORDER BY 1",
        (Object) LOGINS.toArray(String[]::new))).containsExactlyElementsOf(LOGINS);
  }

  @Test
  void losRolesDeGrupoNoTienenLogin() throws Exception {
    assertThat(column("SELECT rolname FROM pg_roles WHERE rolname = ANY (?) AND NOT rolcanlogin AND NOT rolsuper"
        + " AND NOT rolcreaterole AND NOT rolcreatedb ORDER BY 1", (Object) GROUPS.toArray(String[]::new)))
        .containsExactlyElementsOf(GROUPS);
    // cms_owner no puede abrir conexiones, ni con la contraseña de otro login.
    assertThatThrownBy(() -> DriverManager.getConnection(TestDatabase.url(), "cms_owner", TestDatabase.password("cms_migrator")).close())
        .isInstanceOf(SQLException.class);
    // El propietario solo pertenece a los dueños de las funciones transferidas por V3 y V4, con
    // SET (para transferirlas) e INHERIT (para gestionar sus permisos después), sin ADMIN.
    assertThat(column("SELECT g.rolname || '/inherit=' || m.inherit_option || '/set=' || m.set_option"
        + " || '/admin=' || m.admin_option FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid"
        + " WHERE m.member = 'cms_owner'::regrole ORDER BY 1"))
        .containsExactly(
            "cms_article_publisher/inherit=true/set=true/admin=false",
            "cms_audit_retention/inherit=true/set=true/admin=false",
            "cms_enrollment_writer/inherit=true/set=true/admin=false",
            "cms_import_writer/inherit=true/set=true/admin=false",
            "cms_revision_writer/inherit=true/set=true/admin=false");
  }

  @ParameterizedTest
  @ValueSource(strings = {"cms_api", "cms_tool", "cms_security_tool", "cms_maintenance"})
  void soloCmsMigratorPuedeAsumirElPropietario(String login) {
    assertThat(txAsFails(login, NO_PRIVILEGE, "SET LOCAL ROLE cms_owner").getMessage()).contains("permission denied");
  }

  @Test
  void cmsMigratorSinSetRoleNoTieneNingunPrivilegio() {
    // Con el esquema explícito (sin USAGE en public, sin él ni siquiera resuelve los nombres).
    deniedAs("cms_migrator", "CREATE TABLE public.intruso (id int)");
    deniedAs("cms_migrator", "SELECT count(*) FROM public.article");
    deniedAs("cms_migrator", "SELECT count(*) FROM public.flyway_schema_history");
    deniedAs("cms_migrator", "CREATE TEMP TABLE intrusa (id int)");
  }

  @Test
  void todoPerteneceACmsOwnerSalvoLasFuncionesSecurityDefiner() throws Exception {
    assertThat(column("SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"))
        .containsExactly("cms_owner");
    assertThat(column("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public'"))
        .containsExactly("cms_owner");
    // Tablas, índices, secuencias y vistas del esquema, incluido el historial de Flyway.
    assertThat(column("SELECT DISTINCT pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace = 'public'::regnamespace"))
        .containsExactly("cms_owner");
    // Dominios y demás tipos.
    assertThat(column("SELECT DISTINCT pg_get_userbyid(typowner) FROM pg_type WHERE typnamespace = 'public'::regnamespace"))
        .containsExactly("cms_owner");
    // Funciones: de cms_owner, salvo las que V3 y V4 transfieren a sus roles dueños (las SECURITY
    // DEFINER y article_revision_content, que solo ellas usan).
    assertThat(column("SELECT proname || ':' || pg_get_userbyid(proowner) || ':' || prosecdef FROM pg_proc"
        + " WHERE pronamespace = 'public'::regnamespace AND proowner <> 'cms_owner'::regrole ORDER BY 1"))
        .containsExactly(
            "article_revision_content:cms_revision_writer:false",
            "complete_passkey_enrollment:cms_enrollment_writer:true",
            "create_article_revision:cms_revision_writer:true",
            "import_published_article:cms_import_writer:true",
            "insert_article_revision:cms_revision_writer:true",
            "publish_article:cms_article_publisher:true",
            "purge_audit:cms_audit_retention:true",
            "restore_article_revision:cms_revision_writer:true",
            "unpublish_article:cms_article_publisher:true");
    assertThat(column("SELECT proname FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND prosecdef"
        + " AND proowner = 'cms_owner'::regrole")).as("ninguna SECURITY DEFINER del propietario").isEmpty();
  }

  @Test
  void nadieSalvoElPropietarioPuedeCrearEnElEsquemaNiConectarseSinPermiso() throws Exception {
    assertThat(column("SELECT r.rolname FROM pg_roles r WHERE has_schema_privilege(r.oid, 'public', 'CREATE')"
        + " AND NOT r.rolsuper ORDER BY 1"))
        .as("tras V5, los dueños de funciones ya no tienen CREATE").containsExactly("cms_owner");
    assertThat(column("SELECT privilege_type FROM (VALUES ('CONNECT'), ('TEMPORARY'), ('CREATE')) v(privilege_type)"
        + " WHERE has_database_privilege('public', current_database(), privilege_type)")).isEmpty();
    assertThat(one(Boolean.class, "SELECT has_schema_privilege('public', 'public', 'USAGE')")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cms_api", "cms_tool", "cms_security_tool", "cms_maintenance"})
  void losLoginsDeServicioNoPuedenMigrarNiAlterarElEsquema(String login) {
    deniedAs(login, "CREATE TABLE public.intruso (id int)");
    deniedAs(login, "CREATE TEMP TABLE intrusa (id int)");
    deniedAs(login, "INSERT INTO public.flyway_schema_history (installed_rank, version, description, type, script,"
        + " installed_by, execution_time, success) VALUES (99, '99', 'x', 'SQL', 'x', 'x', 0, true)");
    txAsFails(login, NO_PRIVILEGE, "ALTER TABLE public.tag ADD COLUMN intrusa int");
    txAsFails(login, NO_PRIVILEGE, "DROP TABLE public.tag");
  }

  @Test
  void soloLaApiYLasHerramientasLeenElHistorialDeMigraciones() throws Exception {
    for (String login : List.of("cms_api", "cms_tool", "cms_security_tool")) {
      txAs(login, "SELECT count(*) FROM flyway_schema_history");
    }
    deniedAs("cms_maintenance", "SELECT count(*) FROM public.flyway_schema_history");
  }

  @Test
  void elScriptDeInicioRechazaSecretosAusentesCortosORepetidos() throws Exception {
    // Contraseñas cortas y repetidas en otra carpeta de secretos: el script se detiene antes de
    // cambiar nada (ON_ERROR_STOP) y lo dice.
    var prepare = POSTGRES.execInContainer("sh", "-c", "mkdir -p /tmp/malos && for u in migrator api tool security_tool"
        + " maintenance; do printf corta > /tmp/malos/cms_${u}_password; done");
    assertThat(prepare.getExitCode()).isZero();
    var run = POSTGRES.execInContainer("env", "CMS_SECRETS_DIR=/tmp/malos", "psql", "-X", "-U", POSTGRES.getUsername(),
        "-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-v", "DBNAME=" + POSTGRES.getDatabaseName(),
        "-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql");
    assertThat(run.getExitCode()).isNotZero();
    assertThat(run.getStderr()).contains("secretos del CMS ausentes, repetidos o de menos de 24 caracteres");
    // Sin archivo de secretos, tampoco continúa.
    var missing = POSTGRES.execInContainer("env", "CMS_SECRETS_DIR=/tmp/no-existe", "psql", "-X", "-U",
        POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-v", "ON_ERROR_STOP=1",
        "-v", "DBNAME=" + POSTGRES.getDatabaseName(), "-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql");
    assertThat(missing.getExitCode()).isNotZero();
    // Los logins siguen con sus contraseñas reales.
    txAs("cms_api", "SELECT 1");
  }
}
