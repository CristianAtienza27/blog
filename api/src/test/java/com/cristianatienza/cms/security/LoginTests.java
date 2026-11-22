package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Inicio de sesión con contraseña (primer factor) y CSRF. */
class LoginTests extends SecurityTestSupport {

  @Test
  void sinTokenCsrfNoSePuedeIniciarSesion() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    assertThat(client.postWithoutCsrf("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}")
        .status()).isEqualTo(403);
    // Con la cookie pero sin la cabecera, tampoco.
    client.get("/api/auth/csrf");
    assertThat(client.cookie(SecurityConfig.CSRF_COOKIE)).isNotBlank();
    assertThat(client.postWithoutCsrf("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}")
        .status()).isEqualTo(403);
  }

  @Test
  void elPrimerAccesoSoloPermiteRegistrarLaPasskey() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    var response = client.login(email, PASSWORD);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
    assertThat(client.cookie(SecurityConfig.SESSION_COOKIE)).isNotBlank();
    assertThat(client.get("/api/auth/me").body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
  }

  @Test
  void conPasskeyRegistradaFaltaElSegundoFactor() throws Exception {
    String email = createUser(true, false);
    var response = client().login(email, PASSWORD);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"state\":\"PASSKEY_REQUIRED\"}");
  }

  @Test
  void elCorreoNoDistingueMayusculas() throws Exception {
    String email = createUser(true, true);
    assertThat(client().login(email.toUpperCase(), PASSWORD).status()).isEqualTo(200);
  }

  @Test
  void mismoErrorParaContraseñaIncorrectaUsuarioInexistenteODesactivado() throws Exception {
    String enabled = createUser(true, true);
    String disabled = createUser(false, true);
    var wrongPassword = client().login(enabled, "otra-contraseña-equivocada");
    var unknownUser = client().login("nadie-" + enabled, PASSWORD);
    var disabledUser = client().login(disabled, PASSWORD);
    for (var response : new ApiClient.Response[] {wrongPassword, unknownUser, disabledUser}) {
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.body()).contains("\"code\":\"invalid_credentials\"", "\"detail\":\"credenciales no válidas\"");
      assertThat(response.setCookie(SecurityConfig.SESSION_COOKIE)).as("sin sesión").isNull();
    }
  }

  @Test
  void elInicioDeSesionRegistraElUltimoAccesoYNadaMas() throws Exception {
    String email = createUser(true, true);
    UserRow before = userRow(email);
    assertThat(before.lastLoginAt()).isNull();
    Instant start = Instant.now().minusSeconds(1);
    assertThat(client().login(email, PASSWORD).status()).isEqualTo(200);
    UserRow after = userRow(email);
    assertThat(after.lastLoginAt()).isBetween(start, Instant.now().plusSeconds(1));
    assertThat(after.updatedAt()).as("updated_at intacto").isEqualTo(before.updatedAt());
    assertThat(after.enrollRequired()).as("enroll_required intacto").isTrue();
  }

  @Test
  void unInicioFallidoNoRegistraElAcceso() throws Exception {
    String enabled = createUser(true, true);
    String disabled = createUser(false, true);
    assertThat(client().login(enabled, "otra-contraseña-equivocada").status()).isEqualTo(401);
    assertThat(client().login(disabled, PASSWORD).status()).isEqualTo(401);
    assertThat(userRow(enabled).lastLoginAt()).isNull();
    assertThat(userRow(disabled).lastLoginAt()).isNull();
  }

  @Test
  void soloJsonYCamposObligatorios() throws Exception {
    ApiClient client = client();
    client.get("/api/auth/csrf");
    assertThat(client.post("/api/auth/login", "{\"email\": \"a@b.c\"}").status()).isEqualTo(400);
    assertThat(client.post("/api/auth/login", "{\"email\": \"\", \"password\": \"x\"}").status()).isEqualTo(400);
    assertThat(client.post("/api/auth/login", "no es json").status()).isEqualTo(400);
    assertThat(client.post("/api/auth/login", "{\"email\": \"a@b.c\", \"password\": \"" + "x".repeat(1025) + "\"}")
        .status()).isEqualTo(400);
    var form = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
        + "/api/auth/login")).header("Content-Type", "text/plain")
        .header(SecurityConfig.CSRF_HEADER, client.cookie(SecurityConfig.CSRF_COOKIE))
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("email=a&password=b")), false);
    assertThat(form.status()).isEqualTo(415);
  }

  @Test
  void elTokenCsrfCambiaAlIniciarSesion() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.get("/api/auth/csrf");
    String before = client.cookie(SecurityConfig.CSRF_COOKIE);
    client.post("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}");
    assertThat(client.cookie(SecurityConfig.CSRF_COOKIE)).isNotBlank().isNotEqualTo(before);
    // El nuevo funciona.
    assertThat(client.post("/api/auth/logout", null).status()).isEqualTo(204);
  }
}
