package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.passkey.CeremonyObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Consumo de challenges y peticiones simultáneas de una misma sesión (diseño aprobado y
 * diagnóstico de la 6.2.3): un solo consumo, sin respuestas 500, consumo confirmado antes de la
 * verificación, rotación sin resurrección del identificador anterior y fallos contados (D7).
 * Las pruebas de carrera se repiten para detectar resultados intermitentes.
 */
@Import(ChallengeConsumptionTests.Hooks.class)
class ChallengeConsumptionTests extends SecurityTestSupport {

  /** Observador de pruebas: puede actuar tras el consumo y dentro del paso del contador. */
  static final class TestObserver implements CeremonyObserver {
    volatile BiConsumer<UUID, String> afterConsume = (id, ceremony) -> {};
    volatile Consumer<UUID> inCounterStep = id -> {};

    @Override
    public void challengeConsumed(UUID challengeId, String ceremony) {
      afterConsume.accept(challengeId, ceremony);
    }

    @Override
    public void counterStep(UUID credentialId) {
      inCounterStep.accept(credentialId);
    }
  }

  @TestConfiguration
  static class Hooks {
    @Bean
    @Primary
    TestObserver testCeremonyObserver() {
      return new TestObserver();
    }
  }

  @Autowired TestObserver observer;

  @AfterEach
  void resetHooks() {
    observer.afterConsume = (id, ceremony) -> {};
    observer.inCounterStep = id -> {};
  }

  // --- Veinte peticiones simultáneas, misma sesión y mismo challenge ------------------------------

  @RepeatedTest(5)
  void veinteVerificacionesSimultaneasDelMismoChallenge() throws Exception {
    Account account = p2Account();
    ApiClient client = account.client();
    String oldCookie = client.cookie(SecurityConfig.SESSION_COOKIE);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    String body = account.authenticator().assertion(options.body());

    List<HttpResponse<String>> responses = simultaneously(client, "/api/auth/passkeys/authentication", body, 20);

    List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
    assertThat(statuses).as("sin 500").allMatch(s -> s == 200 || s == 401);
    assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
    UUID user = userIdOf(account.email());
    // Un único consumo y una única transición efectiva a F.
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_challenge WHERE user_id = ?", user)).containsExactly("0");
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_assertion WHERE user_id = ?", user)).containsExactly("1");
    String newCookie = sessionCookieOf(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow());
    assertThat(newCookie).isNotEqualTo(oldCookie);
    // El identificador antiguo deja de valer (también tras los guardados tardíos) y el nuevo sigue en F.
    Thread.sleep(200);
    assertThat(sessionRows(sessionId(oldCookie))).isZero();
    assertThat(me(oldCookie).statusCode()).isEqualTo(401);
    assertThat(sessionRows(sessionId(newCookie))).isEqualTo(1);
    assertThat(me(newCookie).body()).startsWith("{\"state\":\"AUTHENTICATED\"");
    assertThat(query("cms_api", "SELECT count(*) FROM spring_session WHERE principal_name = ?"
        + " AND session_id = ?", user.toString(), sessionId(oldCookie))).containsExactly("0");
  }

  @RepeatedTest(3)
  void veinteFallosSimultaneosSeCuentanYCierranLaSesion() throws Exception {
    Account account = p2Account();
    ApiClient client = account.client();
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    account.authenticator().tamperSignature = true;
    String body = account.authenticator().assertion(options.body());

    List<HttpResponse<String>> responses = simultaneously(client, "/api/auth/passkeys/authentication", body, 20);

    assertThat(responses.stream().map(HttpResponse::statusCode)).as("sin 500").allMatch(s -> s == 401);
    // Cada fallo cuenta (incremento atómico): con 20 se supera el límite de 5 y la sesión se cierra.
    assertThat(client.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_assertion WHERE user_id = ?", userIdOf(account.email())))
        .containsExactly("0");
  }

  @Test
  void cuatroFallosSimultaneosNoCierranLaSesionYElQuintoSi() throws Exception {
    Account account = p2Account();
    ApiClient client = account.client();
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    account.authenticator().tamperSignature = true;
    String body = account.authenticator().assertion(options.body());
    assertThat(simultaneously(client, "/api/auth/passkeys/authentication", body, 4).stream()
        .map(HttpResponse::statusCode)).allMatch(s -> s == 401);
    assertThat(client.get("/api/auth/me").status()).as("4 fallos: la sesión sigue").isEqualTo(200);
    assertThat(client.post("/api/auth/passkeys/authentication", body).status()).isEqualTo(401);
    assertThat(client.get("/api/auth/me").status()).as("quinto fallo").isEqualTo(401);
  }

  // --- Dos challenges distintos a la vez desde la misma sesión ----------------------------------

  /**
   * Desde F: step-up y registro de otra passkey simultáneos, cada uno con su challenge. El registro
   * se retiene tras consumir su challenge hasta que el step-up ha rotado la sesión: su guardado
   * llega después de la rotación y no puede devolver la sesión al identificador anterior.
   */
  @Test
  void dosChallengesDistintosALaVezYUnGuardadoTardioTrasLaRotacion() throws Exception {
    Account account = fullAccount();
    ApiClient client = account.client();
    String oldCookie = client.cookie(SecurityConfig.SESSION_COOKIE);
    var stepUpOptions = client.post("/api/auth/step-up/options", null);
    var registrationOptions = client.post("/api/auth/passkeys/registration/options", null);
    String stepUpBody = account.authenticator().assertion(stepUpOptions.body());
    String registrationBody = new TestAuthenticator().register(registrationOptions.body(), "segunda");

    CountDownLatch stepUpDone = new CountDownLatch(1);
    observer.afterConsume = (id, ceremony) -> {
      if (ceremony.equals("register")) {
        try {
          stepUpDone.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    HttpClient http = HttpClient.newHttpClient();
    String cookies = cookieHeader(client);
    String csrf = client.cookie(SecurityConfig.CSRF_COOKIE);
    CompletableFuture<HttpResponse<String>> registration = http.sendAsync(post("/api/auth/passkeys/registration",
        registrationBody, cookies, csrf), HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> stepUp = http.send(post("/api/auth/step-up", stepUpBody, cookies, csrf),
        HttpResponse.BodyHandlers.ofString());
    stepUpDone.countDown();
    HttpResponse<String> registered = registration.get(30, TimeUnit.SECONDS);

    assertThat(stepUp.statusCode()).isEqualTo(204);
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    String newCookie = sessionCookieOf(stepUp);
    assertThat(newCookie).isNotEqualTo(oldCookie);
    assertThat(me(oldCookie).statusCode()).as("identificador anterior").isEqualTo(401);
    assertThat(sessionRows(sessionId(oldCookie))).isZero();
    assertThat(me(newCookie).body())
        .isEqualTo("{\"state\":\"AUTHENTICATED\",\"passkeys\":{\"usable\":2,\"suspended\":0}}");
  }

  // --- Consumo confirmado antes de verificar --------------------------------------------------------

  @Test
  void elConsumoEstaConfirmadoAntesDeVerificarYUnaFirmaFallidaNoLoDevuelve() throws Exception {
    Account account = p2Account();
    ApiClient client = account.client();
    List<String> seenFromOtherConnection = new CopyOnWriteArrayList<>();
    observer.afterConsume = (id, ceremony) -> {
      try {
        seenFromOtherConnection.addAll(query("cms_api", "SELECT count(*) FROM webauthn_challenge WHERE id = ?", id));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    byte[] challenge = TestAuthenticator.challengeOf(options.body());
    account.authenticator().tamperSignature = true;
    String tampered = account.authenticator().assertionFor(challenge);
    account.authenticator().tamperSignature = false;
    String valid = account.authenticator().assertionFor(challenge);

    assertThat(client.post("/api/auth/passkeys/authentication", tampered).status()).isEqualTo(401);
    // Visto desde otra conexión, entre el consumo y la verificación: la fila ya no existía.
    assertThat(seenFromOtherConnection).containsExactly("0");
    UUID user = userIdOf(account.email());
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_challenge WHERE user_id = ?", user)).containsExactly("0");
    // La respuesta válida para el mismo challenge ya no sirve.
    assertThat(client.post("/api/auth/passkeys/authentication", valid).status()).isEqualTo(401);
    assertThat(client.get("/api/auth/me").body()).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_assertion WHERE user_id = ?", user)).containsExactly("0");
  }

  @Test
  void unFalloEnElPasoDelContadorDejaElChallengeConsumidoYNadaCambia() throws Exception {
    Account account = p2Account();
    ApiClient client = account.client();
    observer.inCounterStep = id -> {
      throw new IllegalStateException("fallo provocado en el paso 3");
    };
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    account.authenticator().counter = 7;
    String body = account.authenticator().assertion(options.body());
    var response = client.post("/api/auth/passkeys/authentication", body);
    assertThat(response.status()).isEqualTo(500);
    assertThat(response.body()).doesNotContain("fallo provocado").doesNotContain("Exception");
    UUID user = userIdOf(account.email());
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_challenge WHERE user_id = ?", user)).containsExactly("0");
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_assertion WHERE user_id = ?", user)).containsExactly("0");
    assertThat(query("cms_api", "SELECT sign_count::text FROM passkey_credential WHERE user_id = ?", user))
        .containsExactly("0");
    assertThat(client.get("/api/auth/me").body()).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
    // Un reintento con la misma respuesta se rechaza: el challenge ya no existe.
    observer.inCounterStep = id -> {};
    assertThat(client.post("/api/auth/passkeys/authentication", body).status()).isEqualTo(401);
  }

  // --- Auxiliares -----------------------------------------------------------------------------------

  /** Usuario con una passkey registrada y una sesión nueva en P2. */
  private Account p2Account() throws Exception {
    String email = createUser(true, true);
    ApiClient enrollClient = client();
    enrollClient.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(enrollClient);
    return new Account(passwordSession(email), authenticator, email);
  }

  /** {@code count} peticiones idénticas a la vez (barrera de salida), con las cookies del cliente. */
  private List<HttpResponse<String>> simultaneously(ApiClient client, String path, String body, int count) {
    String cookies = cookieHeader(client);
    String csrf = client.cookie(SecurityConfig.CSRF_COOKIE);
    HttpClient http = HttpClient.newHttpClient();
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
          return http.send(post(path, body, cookies, csrf), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }));
    }
    start.countDown();
    return calls.stream().map(CompletableFuture::join).toList();
  }

  private HttpRequest post(String path, String body, String cookies, String csrf) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").header("Cookie", cookies)
        .header(SecurityConfig.CSRF_HEADER, csrf).POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private static String cookieHeader(ApiClient client) {
    return client.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("; "));
  }

  private static String sessionCookieOf(HttpResponse<String> response) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith(SecurityConfig.SESSION_COOKIE + "=")).map(c -> c.split(";", 2)[0].split("=", 2)[1])
        .filter(v -> !v.isEmpty()).findFirst().orElseThrow();
  }

  private HttpResponse<String> me(String sessionCookie) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
        .header("Cookie", SecurityConfig.SESSION_COOKIE + "=" + sessionCookie).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
