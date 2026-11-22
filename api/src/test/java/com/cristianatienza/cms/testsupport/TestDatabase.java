package com.cristianatienza.cms.testsupport;

import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 18.6 real para los tests, inicializado igual que en infra/local: la imagen oficial
 * ejecuta el script de roles del repositorio (infra/local/postgres-init) con secretos de prueba.
 * Las migraciones se aplican como en el contenedor de migraciones: conexión con cms_migrator y
 * SET ROLE cms_owner. El superusuario del contenedor solo se usa en los tests para preparar e
 * inspeccionar datos, nunca para migrar ni para la aplicación.
 */
public final class TestDatabase {

  private static final Path INIT = Path.of("../infra/local/postgres-init");

  /** Contraseñas de prueba de los logins reales (distintas y de 24+ caracteres, como exige el script). */
  public static final Map<String, String> PASSWORDS = Map.of(
      "cms_migrator", "test-cms-migrator-password-000",
      "cms_api", "test-cms-api-password-0000000",
      "cms_tool", "test-cms-tool-password-000000",
      "cms_security_tool", "test-cms-security-tool-password",
      "cms_maintenance", "test-cms-maintenance-password-0");

  public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-trixie");

  static {
    configure(POSTGRES).start();
  }

  /** Mismo script de roles del repositorio y mismos secretos de prueba que el contenedor compartido. */
  private static PostgreSQLContainer configure(PostgreSQLContainer container) {
    container
        .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve("10-cms-roles.sh"), 0755),
            "/docker-entrypoint-initdb.d/10-cms-roles.sh")
        .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve("sql/cms-roles.psql"), 0644),
            "/docker-entrypoint-initdb.d/sql/cms-roles.psql")
        .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve("sql/cms-function-owners.psql"), 0644),
            "/docker-entrypoint-initdb.d/sql/cms-function-owners.psql");
    PASSWORDS.forEach((user, password) ->
        container.withCopyToContainer(Transferable.of(password, 0444), "/run/secrets/" + user + "_password"));
    return container;
  }

  /**
   * Un PostgreSQL PROPIO, inicializado y migrado igual que el compartido, para las pruebas que lo
   * detienen o lo pausan (6.2.7.2): así no afectan a los demás tests. Quien lo crea lo para.
   */
  public static PostgreSQLContainer isolated() {
    PostgreSQLContainer container = configure(new PostgreSQLContainer("postgres:18.6-trixie"));
    container.start();
    migrator(container.getJdbcUrl()).migrate();
    return container;
  }

  private TestDatabase() {}

  public static String url() {
    return POSTGRES.getJdbcUrl();
  }

  public static String password(String user) {
    String password = PASSWORDS.get(user);
    if (password == null) {
      throw new IllegalArgumentException("login sin contraseña de prueba: " + user);
    }
    return password;
  }

  /**
   * Crea otra base de datos vacía y le aplica el mismo script de inicio (idempotente: los roles ya
   * existen; repite la propiedad y los permisos de la base de datos y del esquema).
   */
  public static String prepareDatabase(String name) throws Exception {
    var drop = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
        "-v", "ON_ERROR_STOP=1", "-c", "DROP DATABASE IF EXISTS " + name, "-c", "CREATE DATABASE " + name);
    if (drop.getExitCode() != 0) {
      throw new IllegalStateException(drop.getStderr());
    }
    applyInitScript(name);
    return url().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
  }

  /** El script de inicio real (psql) sobre una base de datos del contenedor. */
  private static void applyInitScript(String database) throws Exception {
    var init = POSTGRES.execInContainer("psql", "-X", "-U", POSTGRES.getUsername(), "-d", database,
        "-v", "ON_ERROR_STOP=1", "-v", "DBNAME=" + database, "-f", "/docker-entrypoint-initdb.d/sql/cms-roles.psql");
    if (init.getExitCode() != 0) {
      throw new IllegalStateException(init.getStderr());
    }
  }

  /**
   * Operación administrativa EXCEPCIONAL con el superusuario del contenedor, como el procedimiento
   * documentado para volúmenes ya inicializados (infra/local/README.md): un script de psql por el
   * socket local del contenedor. Solo la usan los tests de ese procedimiento.
   */
  public static String superuserPsql(String... args) throws Exception {
    String[] command = new String[args.length + 7];
    command[0] = "psql";
    command[1] = "-X";
    command[2] = "-v";
    command[3] = "ON_ERROR_STOP=1";
    command[4] = "-U";
    command[5] = POSTGRES.getUsername();
    command[6] = "-d" + POSTGRES.getDatabaseName();
    System.arraycopy(args, 0, command, 7, args.length);
    var result = POSTGRES.execInContainer(command);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(result.getStderr());
    }
    return result.getStdout();
  }

  /** Flyway como el contenedor de migraciones: cms_migrator + SET ROLE cms_owner. */
  public static Flyway migrator() {
    return migrator(url());
  }

  public static Flyway migrator(String url) {
    return Flyway.configure()
        .dataSource(url, "cms_migrator", password("cms_migrator"))
        .initSql("SET ROLE cms_owner")
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load();
  }

  /**
   * Esquema desde cero: limpieza como propietario y, como en un volumen nuevo, el script de
   * inicio antes de migrar (la limpieza no restaura los permisos del esquema que retira V5).
   */
  public static void migrateFromScratch() {
    Flyway flyway = migrator();
    flyway.clean();
    try {
      applyInitScript(POSTGRES.getDatabaseName());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    flyway.migrate();
  }
}
