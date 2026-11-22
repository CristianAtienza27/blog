package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.tool.ToolTestAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Recuperación de emergencia frente a la API en marcha (H7): sincronización por usuario. Las dos
 * ordenaciones se fijan con los puntos de observación del bloqueo, sin depender de tiempos:
 *
 * <ul>
 *   <li><b>Recuperación primero:</b> la petición ya pasó el filtro del sello y se detiene antes de
 *       bloquear; la recuperación termina; la petición bloquea, ve el sello nuevo y se rechaza.
 *   <li><b>API primero:</b> la petición tiene el bloqueo y ya ha hecho la operación; la recuperación
 *       se queda esperando el bloqueo (comprobado en pg_locks); al soltarlo, la recuperación revoca
 *       lo creado; la sesión nueva, que Spring Session inserta después de la recuperación, se cierra
 *       en su primera petición.
 * </ul>
 *
 * En todos los casos se comprueba el estado final: cuenta en P1 con la contraseña nueva, ninguna
 * sesión anterior (ni las creadas durante la carrera) válida, sin passkeys ni identificador
 * WebAuthn, códigos invalidados y dispositivos revocados.
 */
@Import(EmergencyConcurrencyTests.Hooks.class)
class EmergencyConcurrencyTests extends SecurityTestSupport {

  static final String NEW_PASSWORD = "frase-nueva-tras-la-carrera-con-la-api";
  static final Duration WAIT = Duration.ofSeconds(20);

  /** Observador del bloqueo: actúa solo en la operación y el usuario indicados. */
  static final class LockHooks implements AccountLockObserver {
    volatile BiConsumer<UUID, String> beforeLock = (u, o) -> {};
    volatile BiConsumer<UUID, String> beforeRelease = (u, o) -> {};
    volatile BiConsumer<UUID, String> afterRelease = (u, o) -> {};

    @Override
    public void beforeLock(UUID userId, String operation) {
      beforeLock.accept(userId, operation);
    }

    @Override
    public void beforeRelease(UUID userId, String operation) {
      beforeRelease.accept(userId, operation);
    }

    @Override
    public void afterRelease(UUID userId, String operation) {
      afterRelease.accept(userId, operation);
    }
  }

  @TestConfiguration
  static class Hooks {
    @Bean
    @Primary
    LockHooks testLockHooks() {
      return new LockHooks();
    }
  }

  @Autowired LockHooks hooks;
  @TempDir Path dir;
  final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void reset() {
    hooks.beforeLock = (u, o) -> {};
    hooks.beforeRelease = (u, o) -> {};
    hooks.afterRelease = (u, o) -> {};
    pool.shutdownNow();
  }

  // --- Auxiliares ------------------------------------------------------------------------------

  /** Detiene la operación {@code operation} del usuario hasta {@code go}, avisando con {@code arrived}. */
  static BiConsumer<UUID, String> pause(UUID user, String operation, CountDownLatch arrived, CountDownLatch go) {
    return (u, o) -> {
      if (u.equals(user) && o.equals(operation)) {
        arrived.countDown();
        await(go);
      }
    };
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
        throw new AssertionError("espera agotada");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  Future<ToolTestAccess.Recovered> recoverAsync(String email) throws Exception {
    Path file = Files.writeString(dir.resolve("nueva-" + UUID.randomUUID()), NEW_PASSWORD + "\n");
    return pool.submit(() -> ToolTestAccess.recover(email, file));
  }

  /** Espera a que haya una transacción esperando un bloqueo de fila (pg_locks, legible por todos). */
  static void awaitLockWait(Future<?> recovery) throws Exception {
    Instant deadline = Instant.now().plus(WAIT);
    while (Instant.now().isBefore(deadline)) {
      assertThat(recovery.isDone()).as("la recuperación no puede terminar con el bloqueo tomado por la API").isFalse();
      if (Integer.parseInt(query("cms_api", "SELECT count(*) FROM pg_locks WHERE NOT granted").getFirst()) > 0) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("la recuperación no llegó a esperar el bloqueo de la cuenta");
  }

  /** El estado final tras la recuperación, sondeando con todas las sesiones conocidas. */
  void assertRecovered(String email, UUID userId, List<ApiClient> sessionsToProbe) throws Exception {
    for (ApiClient client : sessionsToProbe) {
      assertThat(client.get("/api/auth/me").status()).as("ninguna sesión anterior es válida").isEqualTo(401);
    }
    EmergencyRecoveryTests.assertRevoked(userId);
    assertThat(client().login(email, PASSWORD).status()).as("contraseña anterior").isEqualTo(401);
    assertThat(client().login(email, NEW_PASSWORD).body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
  }

  /** Usuario con una passkey registrada y una sesión en P2 con su aserción preparada. */
  record P2(String email, UUID userId, ApiClient client, String assertion) {}

  P2 p2WithAssertion() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    assertThat(client.login(email, PASSWORD).status()).isEqualTo(200);
    TestAuthenticator authenticator = enroll(client);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    assertThat(options.status()).isEqualTo(200);
    return new P2(email, userIdOf(email), client, authenticator.assertion(options.body()));
  }

  /** Usuario recién creado en P1 con el registro de su passkey preparado. */
  record P1(String email, UUID userId, ApiClient client, String registration) {}

  P1 p1WithRegistration() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    assertThat(client.login(email, PASSWORD).status()).isEqualTo(200);
    var options = client.post("/api/auth/passkeys/registration/options", null);
    assertThat(options.status()).isEqualTo(200);
    return new P1(email, userIdOf(email), client, new TestAuthenticator().register(options.body(), "carrera"));
  }

  // --- Recuperación primero -------------------------------------------------------------------

  @Test
  void recuperacionPrimero_elPasoAFSeRechazaAlBloquear() throws Exception {
    P2 a = p2WithAssertion();
    CountDownLatch arrived = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    hooks.beforeLock = pause(a.userId(), "passkey.authentication", arrived, go);
    Future<ApiClient.Response> f = pool.submit(() -> a.client().post("/api/auth/passkeys/authentication", a.assertion()));
    await(arrived);

    ToolTestAccess.Recovered r = recoverAsync(a.email()).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(r.sessions()).isOne();
    go.countDown();

    var response = f.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    assertThat(count("SELECT count(*) FROM audit_event WHERE event = 'session.stale_stamp' AND user_id = ?"
        + " AND details->>'operation' = 'passkey.authentication'", a.userId())).isOne();
    assertThat(count("SELECT count(*) FROM audit_event WHERE event = 'login.passkey_succeeded' AND user_id = ?",
        a.userId())).as("nunca se concedió F").isZero();
    assertRecovered(a.email(), a.userId(), List.of(a.client()));
  }

  @Test
  void recuperacionPrimero_elRegistroNoDejaPasskey() throws Exception {
    P1 a = p1WithRegistration();
    CountDownLatch arrived = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    hooks.beforeLock = pause(a.userId(), "passkey.registration", arrived, go);
    Future<ApiClient.Response> f = pool.submit(() -> a.client().post("/api/auth/passkeys/registration", a.registration()));
    await(arrived);

    recoverAsync(a.email()).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    go.countDown();

    // Sin la comprobación del sello con el bloqueo tomado, el challenge de la sesión anterior aún
    // valdría y quedaría una passkey de esa sesión después de la recuperación.
    assertThat(f.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(401);
    assertRecovered(a.email(), a.userId(), List.of(a.client()));
  }

  @Test
  void recuperacionPrimero_elInicioDeSesionConLaContrasenaAnteriorSeRechaza() throws Exception {
    String email = createUser(true, true);
    UUID userId = userIdOf(email);
    CountDownLatch arrived = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    // La contraseña anterior ya se ha validado (Argon2id) contra el hash leído antes de la recuperación.
    hooks.beforeLock = pause(userId, "login", arrived, go);
    ApiClient client = client();
    Future<ApiClient.Response> login = pool.submit(() -> client.login(email, PASSWORD));
    await(arrived);
    recoverAsync(email).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    go.countDown();

    var response = login.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(response.status()).isEqualTo(401);
    assertThat(response.body()).contains("\"code\":\"invalid_credentials\"");
    assertThat(count("SELECT count(*) FROM audit_event WHERE event = 'login.stale_account' AND user_id = ?", userId))
        .isOne();
    assertRecovered(email, userId, List.of(client));
  }

  // --- API primero (bloqueo tomado) -----------------------------------------------------------

  @Test
  void apiPrimero_laRecuperacionEsperaYLaSesionInsertadaDespuesSeCierra() throws Exception {
    P2 a = p2WithAssertion();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    CountDownLatch recoveryDone = new CountDownLatch(1);
    hooks.beforeRelease = pause(a.userId(), "passkey.authentication", locked, release);
    hooks.afterRelease = pause(a.userId(), "passkey.authentication", released, recoveryDone);
    Future<ApiClient.Response> f = pool.submit(() -> a.client().post("/api/auth/passkeys/authentication", a.assertion()));
    await(locked);
    // Con el bloqueo tomado, la operación ya está hecha: auditada, dispositivo emitido y registro
    // completado (cada cosa confirmada en su transacción).
    assertThat(count("SELECT count(*) FROM trusted_device WHERE user_id = ? AND state = 'PENDING'", a.userId())).isOne();
    assertThat(query("cms_security_tool", "SELECT enroll_required FROM app_user WHERE id = ?", a.userId()))
        .containsExactly("f");

    Future<ToolTestAccess.Recovered> recovery = recoverAsync(a.email());
    awaitLockWait(recovery);
    release.countDown();
    await(released);
    ToolTestAccess.Recovered r = recovery.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    // La recuperación vio y revocó lo que la operación había creado con el bloqueo tomado.
    assertThat(r.devices()).isOne();
    assertThat(r.passkeys()).isOne();
    recoveryDone.countDown();

    // F se concedió antes de la recuperación (orden serializable)...
    var response = f.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(response.body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");
    // ...pero Spring Session insertó la sesión nueva después de la recuperación, con el sello anterior.
    String fresh = sessionId(a.client().cookie(SecurityConfig.SESSION_COOKIE));
    assertThat(sessionRows(fresh)).as("sesión insertada después de la recuperación").isOne();
    assertThat(a.client().get("/api/auth/me").status()).as("el filtro del sello la cierra").isEqualTo(401);
    assertThat(sessionRows(fresh)).isZero();
    assertRecovered(a.email(), a.userId(), List.of(a.client()));
  }

  @Test
  void apiPrimero_laRecuperacionBorraLaPasskeyRegistradaConElBloqueo() throws Exception {
    P1 a = p1WithRegistration();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    hooks.beforeRelease = pause(a.userId(), "passkey.registration", locked, release);
    Future<ApiClient.Response> f = pool.submit(() -> a.client().post("/api/auth/passkeys/registration", a.registration()));
    await(locked);
    assertThat(count("SELECT count(*) FROM passkey_credential WHERE user_id = ?", a.userId())).isOne();

    Future<ToolTestAccess.Recovered> recovery = recoverAsync(a.email());
    awaitLockWait(recovery);
    release.countDown();
    assertThat(recovery.get(WAIT.toSeconds(), TimeUnit.SECONDS).passkeys()).isOne();
    assertThat(f.get(WAIT.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(201);
    assertRecovered(a.email(), a.userId(), List.of(a.client()));
  }

  // --- Diez pasos a F simultáneos durante una recuperación -------------------------------------

  @RepeatedTest(3)
  void diezPasosAFSimultaneosDuranteUnaRecuperacion() throws Exception {
    String email = createUser(true, true);
    UUID userId = userIdOf(email);
    ApiClient first = client();
    assertThat(first.login(email, PASSWORD).status()).isEqualTo(200);
    TestAuthenticator authenticator = enroll(first);
    List<ApiClient> clients = new ArrayList<>(List.of(first));
    while (clients.size() < 10) {
      clients.add(passwordSession(email));
    }
    List<String> assertions = new ArrayList<>();
    for (ApiClient c : clients) {
      var options = c.post("/api/auth/passkeys/authentication/options", null);
      assertThat(options.status()).isEqualTo(200);
      assertions.add(authenticator.assertion(options.body()));
    }

    CountDownLatch start = new CountDownLatch(1);
    List<Future<ApiClient.Response>> requests = new ArrayList<>();
    for (int i = 0; i < clients.size(); i++) {
      ApiClient c = clients.get(i);
      String assertion = assertions.get(i);
      requests.add(pool.submit(() -> {
        await(start);
        return c.post("/api/auth/passkeys/authentication", assertion);
      }));
    }
    Path file = Files.writeString(dir.resolve("nueva-" + UUID.randomUUID()), NEW_PASSWORD + "\n");
    Future<ToolTestAccess.Recovered> recovery = pool.submit(() -> {
      await(start);
      return ToolTestAccess.recover(email, file);
    });
    start.countDown();

    recovery.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    for (Future<ApiClient.Response> request : requests) {
      int status = request.get(WAIT.toSeconds(), TimeUnit.SECONDS).status();
      assertThat(status).as("sin errores 500").isIn(200, 401, 429);
    }
    // Las diez sesiones (también las que llegaron a F, con su cookie nueva) quedan inválidas.
    assertRecovered(email, userId, clients);
  }

  static int count(String sql, Object... params) throws Exception {
    return EmergencyRecoveryTests.count(sql, params);
  }
}
