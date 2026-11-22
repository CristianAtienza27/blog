package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentImporter;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.schema.PostgresSchemaTest;
import com.cristianatienza.cms.security.audit.AuditLog;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Base de los tests de publicación (fase 6.3.4): PostgreSQL 18.6 real con el contenido real
 * importado, la API con su login real (cms_api, rol cms_app), carpetas temporales de instantáneas y
 * de estado y una web simulada con las reglas de la real.
 */
abstract class PublicationTestSupport extends PostgresSchemaTest {

  @TempDir Path temp;
  Path snapshots;
  Path state;
  FakeWeb web;

  @BeforeEach
  void prepareContentAndWeb() throws Exception {
    snapshots = Files.createDirectories(temp.resolve("instantaneas"));
    state = Files.createDirectories(temp.resolve("estado"));
    new ContentImporter(dataSourceAs(IMPORTER_LOGIN)).importContent(ContentSource.read(Path.of("../src/content")),
        CodeUsages.read(Path.of("../src/app/editorial/retired-media.ts"), Path.of("../src/app/layout/navigation.ts")));
    web = new FakeWeb(snapshots, state);
  }

  @AfterEach
  void stopWeb() {
    web.close();
  }

  PublicationProperties properties(URI webUri) {
    return properties(webUri, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(60));
  }

  PublicationProperties properties(URI webUri, Duration wait, Duration heartbeat, Duration idle) {
    return new PublicationProperties(snapshots, state, webUri, wait, Duration.ofMillis(200), heartbeat, idle,
        Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 20, 50);
  }

  PublicationService service() {
    return service(properties(web.uri()), PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
  }

  PublicationService service(PublicationService.Hooks hooks) {
    return service(properties(web.uri()), hooks, PublicationLock.Hooks.NONE);
  }

  PublicationService service(PublicationProperties properties, PublicationService.Hooks hooks,
      PublicationLock.Hooks lockHooks) {
    return new PublicationService(appDataSource(), properties,
        new WebActivationClient(properties.webInternalUrl(), Duration.ofSeconds(2), Duration.ofSeconds(30)),
        new PublicationAudit(new StaticListableBeanFactory().getBeanProvider(AuditLog.class)),
        lockConnections(), System::nanoTime, hooks, lockHooks);
  }

  static DataSource appDataSource() {
    return dataSourceAs(APP_LOGIN);
  }

  /** Conexiones del bloqueo como en producción: login de la API, fuera de cualquier pool. */
  static PublicationLock.ConnectionFactory lockConnections() {
    return timeout -> {
      Properties props = new Properties();
      props.setProperty("user", APP_LOGIN);
      props.setProperty("password", TestDatabase.password(APP_LOGIN));
      props.setProperty("connectTimeout", Integer.toString(timeout));
      props.setProperty("loginTimeout", Integer.toString(timeout));
      props.setProperty("socketTimeout", "30");
      return DriverManager.getConnection(POSTGRES.getJdbcUrl(), props);
    };
  }

  static String states() throws Exception {
    return String.join(" ", column("SELECT 'v' || version || '=' || state FROM content_publication ORDER BY version"));
  }

  static long audits(String event) throws Exception {
    return one(Long.class, "SELECT count(*) FROM audit_event WHERE event = ?", event);
  }

  static long allAudits() throws Exception {
    return one(Long.class, "SELECT count(*) FROM audit_event WHERE event LIKE 'content.%'");
  }

  String pointerVersion() throws Exception {
    Path file = state.resolve("activa.json");
    if (!Files.exists(file)) {
      return null;
    }
    return PublicationRepository.JSON.readTree(Files.readString(file)).path("version").asString(null);
  }

  /** Un cambio real en lo publicado (una etiqueta), como haría el editor: otra huella. */
  static void changeContent(String label) throws Exception {
    asApp("UPDATE tag SET label = '" + label + "' WHERE id = 'costa'");
  }

  static String check() throws Exception {
    return one(String.class, "SELECT status || coalesce(' ' || (detail ->> 'motivo'), '') FROM content_publication_check");
  }

  /** Termina una sesión de PostgreSQL (corte real), como superusuario de pruebas. */
  static void terminate(int pid) throws Exception {
    tx("SELECT pg_terminate_backend(" + pid + ")");
  }

  static boolean holdsLock(int pid) throws Exception {
    return one(Long.class, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = ? AND classid = "
        + PublicationLock.KEY_CLASS + " AND objid = " + PublicationLock.KEY_OBJECT + " AND granted", pid) == 1;
  }

  static Integer lockHolderPid() throws Exception {
    return one(Integer.class, "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid = "
        + PublicationLock.KEY_CLASS + " AND objid = " + PublicationLock.KEY_OBJECT + " AND granted");
  }
}
