package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL deja de responder (docker pause: procesos congelados, conexiones TCP abiertas, como en
 * la prueba de la 6.1.4) con la API en marcha, sobre un PostgreSQL PROPIO de esta clase (6.2.7.2):
 *
 * <ul>
 *   <li>liveness sigue en UP y responde al momento: la caída de la base de datos no hace que Docker o
 *       Kubernetes den la JVM por muerta;
 *   <li>readiness pasa a DOWN, y las peticiones que necesitan la base de datos fallan con 503
 *       «unavailable» y Retry-After en unos 5 s (connection-timeout), nunca tras 30–60 s;
 *   <li>lo que no necesita la base de datos (el token CSRF) sigue funcionando;
 *   <li>al volver PostgreSQL, la API se recupera sola, sin reiniciarse, y la misma sesión sigue valiendo.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseOutageTests {

  static final PostgreSQLContainer PG = TestDatabase.isolated();
  static final String PASSWORD = "una-contraseña-de-prueba-larga";
  /** connection-timeout (5 s) más margen para la validación y el propio equipo. */
  static final Duration FAST = Duration.ofSeconds(8);

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("CMS_DB_URL", PG::getJdbcUrl);
    registry.add("CMS_DB_USER", () -> "cms_api");
    registry.add("CMS_DB_PASSWORD", () -> TestDatabase.password("cms_api"));
  }

  @AfterAll
  static void stop() {
    resume();
    PG.stop();
  }

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHashing hashing;

  static void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(PG.getContainerId()).exec();
  }

  static void resume() {
    var state = DockerClientFactory.instance().client().inspectContainerCmd(PG.getContainerId()).exec().getState();
    if (Boolean.TRUE.equals(state.getPaused())) {
      DockerClientFactory.instance().client().unpauseContainerCmd(PG.getContainerId()).exec();
    }
  }

  record Timed(ApiClient.Response response, Duration took) {}

  static Timed timed(Callable<ApiClient.Response> call) throws Exception {
    Instant start = Instant.now();
    ApiClient.Response response = call.call();
    return new Timed(response, Duration.between(start, Instant.now()));
  }

  String createUser() throws Exception {
    String email = "caida-" + UUID.randomUUID() + "@example.test";
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "cms_security_tool",
        TestDatabase.password("cms_security_tool"));
        PreparedStatement ps = c.prepareStatement("INSERT INTO app_user (id, email, password_hash, role, enabled,"
            + " enroll_required) VALUES (?, ?, ?, 'ADMIN', true, true)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, email);
      ps.setString(3, hashing.encode(PASSWORD));
      ps.executeUpdate();
    }
    return email;
  }

  static void assertUnavailable(Timed t, String what) {
    assertThat(t.response().status()).as(what + ": " + t.response().body()).isEqualTo(503);
    assertThat(t.took()).as(what + ": respuesta rápida").isLessThan(FAST);
    assertThat(t.response().body()).contains("\"code\":\"unavailable\"").contains("\"correlationId\"")
        .doesNotContain("Exception").doesNotContain("PSQL").doesNotContain("Hikari");
    var headers = t.response().raw().headers();
    assertThat(headers.firstValue("Retry-After")).as(what).contains("5");
    assertThat(headers.firstValue("Content-Type")).as(what).hasValueSatisfying(v -> assertThat(v)
        .startsWith("application/problem+json"));
    assertThat(headers.firstValue("X-Content-Type-Options")).as(what + ": cabeceras de seguridad").contains("nosniff");
    assertThat(headers.firstValue("Cache-Control")).as(what).contains("no-store");
  }

  @Test
  void caidaDePostgresqlYRecuperacionAutomatica() throws Exception {
    String email = createUser();
    ApiClient session = new ApiClient(port);
    assertThat(session.login(email, PASSWORD).status()).isEqualTo(200);
    assertThat(session.get("/api/auth/me").status()).isEqualTo(200);
    ApiClient probes = new ApiClient(port);
    assertThat(probes.get("/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
    assertThat(probes.get("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");

    pause();
    try {
      Timed liveness = timed(() -> probes.get("/actuator/health/liveness"));
      assertThat(liveness.response().status()).isEqualTo(200);
      assertThat(liveness.took()).as("liveness no consulta la base de datos").isLessThan(Duration.ofSeconds(1));

      Timed readiness = timed(() -> probes.get("/actuator/health/readiness"));
      assertThat(readiness.response().status()).isEqualTo(503);
      assertThat(readiness.response().body()).contains("\"status\":\"DOWN\"");
      assertThat(readiness.took()).isLessThan(FAST);

      // Con sesión: Spring Session no puede cargarla (fuera de los controladores, despacho de errores).
      assertUnavailable(timed(() -> session.get("/api/auth/me")), "/me con sesión");
      // Inicio de sesión: el usuario no se puede leer (dentro de un controlador).
      ApiClient other = new ApiClient(port);
      assertUnavailable(timed(() -> other.login(email, PASSWORD)), "inicio de sesión");
      // Sin base de datos: el token CSRF.
      Timed csrf = timed(() -> new ApiClient(port).get("/api/auth/csrf"));
      assertThat(csrf.response().status()).isEqualTo(200);
      assertThat(csrf.took()).isLessThan(Duration.ofSeconds(1));
      // Tras las peticiones que esperaron al pool, liveness sigue respondiendo al momento.
      Timed again = timed(() -> probes.get("/actuator/health/liveness"));
      assertThat(again.response().status()).isEqualTo(200);
      assertThat(again.took()).isLessThan(Duration.ofSeconds(1));
    } finally {
      resume();
    }

    // Recuperación automática, sin reiniciar la API.
    Instant resumed = Instant.now();
    while (probes.get("/actuator/health/readiness").status() != 200) {
      assertThat(Duration.between(resumed, Instant.now())).as("readiness vuelve a UP").isLessThan(Duration.ofSeconds(20));
      Thread.sleep(200);
    }
    assertThat(session.get("/api/auth/me").status()).as("la misma sesión sigue valiendo").isEqualTo(200);
    assertThat(new ApiClient(port).login(email, PASSWORD).status()).isEqualTo(200);
  }
}
