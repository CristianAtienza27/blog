package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.security.tool.ToolTestAccess;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lo que tiene que sobrevivir a un reinicio (6.2.7.4) vive en PostgreSQL, no en la memoria de una
 * instancia de la API. Se demuestra de forma determinista con una SEGUNDA instancia de la API sobre
 * la misma base de datos: lo hecho en una vale en la otra, y sigue valiendo después de cerrar la
 * primera (el «reinicio»). Las muertes bruscas (SIGKILL) y el reinicio real de PostgreSQL se prueban
 * en el entorno de medición (infra/bench, R1–R9).
 *
 * <ul>
 *   <li>la sesión sigue valiendo en la otra instancia y tras cerrar la primera;
 *   <li>los contadores de intentos conservan el bloqueo (429 con Retry-After);
 *   <li>un challenge consumido no vuelve a estar disponible;
 *   <li>ningún evento de auditoría se duplica ni queda huérfano;
 *   <li>el bloqueo por usuario de una conexión que muere se libera (la recuperación espera y después
 *       termina).
 * </ul>
 */
class RestartPersistenceTests extends SecurityTestSupport {

  @TempDir Path dir;
  final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
  ConfigurableApplicationContext other;

  @AfterEach
  void close() {
    if (other != null) {
      other.close();
    }
    pool.shutdownNow();
  }

  /** Otra instancia de la API, como cms_api, sobre la misma base de datos. */
  int startOther() {
    other = new SpringApplicationBuilder(CmsApplication.class)
        .properties("CMS_DB_URL=" + TestDatabase.url(), "CMS_DB_USER=cms_api",
            "CMS_DB_PASSWORD=" + TestDatabase.password("cms_api"), "server.port=0")
        .run();
    return ((WebServerApplicationContext) other).getWebServer().getPort();
  }

  static int count(String sql, Object... params) throws Exception {
    return Integer.parseInt(query("cms_security_tool", sql, params).getFirst());
  }

  @Test
  void sesionContadoresYChallengesSobrevivenAlReinicio() throws Exception {
    Account account = fullAccount();
    UUID userId = userIdOf(account.email());
    // Un challenge ya consumido con éxito (step-up) en esta instancia.
    var options = account.client().post("/api/auth/step-up/options", null);
    String assertion = account.authenticator().assertion(options.body());
    assertThat(account.client().post("/api/auth/step-up", assertion).status()).isEqualTo(204);
    // Una pareja cuenta + IP bloqueada por fallos (5, valor real).
    String blocked = createUser(true, true);
    ApiClient attacker = client();
    for (int i = 0; i < 5; i++) {
      assertThat(attacker.login(blocked, "no-es-la-contraseña-correcta").status()).isEqualTo(401);
    }
    var limited = attacker.login(blocked, PASSWORD);
    assertThat(limited.status()).isEqualTo(429);
    int loginsBefore = count("SELECT count(*) FROM audit_event WHERE user_id = ? AND event = 'login.password_succeeded'",
        userId);

    // Otra instancia: lo anterior vale allí (está en PostgreSQL)...
    ApiClient second = account.client().on(startOther());
    assertThat(second.get("/api/auth/me").status()).as("la misma sesión en otra instancia").isEqualTo(200);
    assertThat(second.post("/api/auth/step-up", assertion).status()).as("el challenge consumido no vuelve")
        .isEqualTo(401);
    var stillLimited = attacker.on(second.port()).login(blocked, PASSWORD);
    assertThat(stillLimited.status()).as("la pareja sigue bloqueada").isEqualTo(429);
    assertThat(stillLimited.raw().headers().firstValue("Retry-After")).isPresent();

    // ...y sigue valiendo tras cerrar la primera instancia y arrancar otra («reinicio»).
    other.close();
    ApiClient third = account.client().on(startOther());
    assertThat(third.get("/api/auth/me").status()).isEqualTo(200);
    assertThat(attacker.on(third.port()).login(blocked, PASSWORD).status()).isEqualTo(429);

    // Auditoría: nada duplicado (cada evento de esta prueba, una vez por correlación) ni huérfano.
    assertThat(count("SELECT count(*) FROM audit_event WHERE user_id = ? AND event = 'login.password_succeeded'",
        userId)).as("los reinicios no repiten eventos").isEqualTo(loginsBefore);
    // Solo los usuarios de esta prueba: la base de datos de los tests es compartida.
    UUID blockedId = userIdOf(blocked);
    assertThat(count("SELECT count(*) FROM (SELECT details->>'correlationId', event FROM audit_event"
        + " WHERE user_id IN (?, ?) AND details->>'correlationId' IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1) d",
        userId, blockedId)).as("duplicados").isZero();
    assertThat(count("SELECT count(*) FROM audit_event e WHERE e.user_id IN (?, ?)"
        + " AND e.details->>'correlationId' IS NOT NULL"
        + " AND NOT EXISTS (SELECT 1 FROM audit_event_ip i WHERE i.event_id = e.id)", userId, blockedId))
        .as("eventos de la API sin su IP (escritos en la misma transacción)").isZero();
    assertThat(count("SELECT count(*) FROM audit_event e WHERE e.user_id IS NOT NULL"
        + " AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = e.user_id)")).as("usuarios inexistentes").isZero();
  }

  @Test
  void elBloqueoDeUnaConexionQueMuereSeLibera() throws Exception {
    Account account = fullAccount();
    String email = account.email();
    // Una conexión de la API (cms_api) con el bloqueo por usuario tomado, como en una operación en curso.
    Connection holder = connectAs("cms_api");
    holder.setAutoCommit(false);
    int holderPid;
    try (PreparedStatement ps = holder.prepareStatement(
        "SELECT pg_backend_pid() FROM app_user WHERE email = ? FOR KEY SHARE")) {
      ps.setString(1, email);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        holderPid = rs.getInt(1);
      }
    }
    Path file = Files.writeString(dir.resolve("nueva"), "frase-nueva-tras-morir-la-conexion\n");
    Future<ToolTestAccess.Recovered> recovery = pool.submit(() -> ToolTestAccess.recover(email, file));
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (count("SELECT count(*) FROM pg_locks WHERE NOT granted") == 0) {
      assertThat(Instant.now()).as("la recuperación espera el bloqueo").isBefore(deadline);
      assertThat(recovery.isDone()).isFalse();
      Thread.onSpinWait();
    }
    // La conexión muere (como cuando la API muere): PostgreSQL termina su transacción y suelta el bloqueo.
    try (Connection killer = connectAs("cms_api");
        PreparedStatement ps = killer.prepareStatement("SELECT pg_terminate_backend(?)")) {
      ps.setInt(1, holderPid);
      ps.executeQuery();
    }
    ToolTestAccess.Recovered r = recovery.get(20, TimeUnit.SECONDS);
    assertThat(r.passkeys()).isOne();
    assertThat(count("SELECT count(*) FROM pg_locks WHERE NOT granted")).isZero();
    try {
      holder.close();
    } catch (Exception ignored) {
      // ya terminada por PostgreSQL
    }
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
  }
}
