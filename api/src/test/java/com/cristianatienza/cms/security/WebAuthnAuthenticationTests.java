package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.session.Session;

/**
 * Autenticación con passkey (P2 → F), con la API real, conexiones reales y el autenticador de
 * pruebas: cada comprobación de la verificación tiene su caso negativo.
 */
class WebAuthnAuthenticationTests extends SecurityTestSupport {

  @Test
  void contraseñaRegistroYPasskeyLleganAF() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(client);
    String p2Session = client.cookie(SecurityConfig.SESSION_COOKIE);
    String p2Csrf = client.cookie(SecurityConfig.CSRF_COOKIE);
    Instant authenticatedAt = sessionAttribute(p2Session, AuthStates.AUTHENTICATED_AT);

    var full = authenticate(client, authenticator);
    assertThat(full.status()).isEqualTo(200);
    assertThat(full.body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");
    // Identificador de sesión y token CSRF nuevos; la caducidad absoluta sigue contando desde la contraseña.
    String fSession = client.cookie(SecurityConfig.SESSION_COOKIE);
    assertThat(fSession).isNotEqualTo(p2Session);
    assertThat(sessionRows(sessionId(p2Session))).isZero();
    assertThat(client.cookie(SecurityConfig.CSRF_COOKIE)).isNotEqualTo(p2Csrf);
    assertThat((Instant) sessionAttribute(fSession, AuthStates.AUTHENTICATED_AT)).isEqualTo(authenticatedAt);
    assertThat(client.get("/api/auth/me").body())
        .isEqualTo("{\"state\":\"AUTHENTICATED\",\"passkeys\":{\"usable\":1,\"suspended\":0}}");
    // El registro se completa (función controlada) y la aserción deja su procedencia.
    UUID id = userIdOf(email);
    assertThat(query("cms_security_tool", "SELECT enroll_required::text FROM app_user WHERE id = ?", id))
        .containsExactly("false");
    assertThat(query("cms_api", "SELECT outcome || ':' || classification FROM webauthn_assertion WHERE user_id = ?", id))
        .containsExactly("accepted:no_counter");
  }

  @Test
  void conUnaPasskeyRegistradaElSiguienteAccesoEsP2YNoPermiteRegistrarOtra() throws Exception {
    String email = createUser(true, true);
    ApiClient first = client();
    first.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(first);
    // Abandona sin autenticarse: el siguiente acceso exige la passkey (P2), no otro registro.
    ApiClient second = client();
    assertThat(second.login(email, PASSWORD).body()).isEqualTo("{\"state\":\"PASSKEY_REQUIRED\"}");
    assertThat(second.post("/api/auth/passkeys/registration/options", null).status()).isEqualTo(403);
    assertThat(authenticate(second, authenticator).status()).isEqualTo(200);
  }

  @Test
  void unaPasskeyDeOtroUsuarioCierraLaSesion() throws Exception {
    Account other = fullAccount();
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    enroll(client);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    var response = client.post("/api/auth/passkeys/authentication", other.authenticator().assertion(options.body()));
    assertThat(response.status()).isEqualTo(401);
    assertThat(client.get("/api/auth/me").status()).as("sesión cerrada").isEqualTo(401);
  }

  @Test
  void unUserHandleAjenoCierraLaSesion() throws Exception {
    Account account = p2Account();
    account.authenticator().userHandle = new byte[32];
    assertThat(authenticate(account.client(), account.authenticator()).status()).isEqualTo(401);
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void cadaComprobacionDeLaVerificacionRechaza() throws Exception {
    rejects(a -> a.uv = false);
    rejects(a -> a.up = false);
    rejects(a -> a.origin = "http://localhost:4201");
    rejects(a -> a.origin = "https://evil.example");
    rejects(a -> a.rpId = "example.com");
    rejects(a -> a.typeOverride = "webauthn.create");
    rejects(a -> a.crossOrigin = true);
    rejects(a -> a.tamperSignature = true);
    rejects(a -> a.challengeOverride = new byte[32]);
    rejects(a -> a.backupEligible = true);
  }

  private void rejects(Consumer<TestAuthenticator> change) throws Exception {
    Account account = p2Account();
    change.accept(account.authenticator());
    assertThat(authenticate(account.client(), account.authenticator()).status()).isEqualTo(401);
    // Un fallo no cierra la sesión (hasta el límite) ni da acceso.
    assertThat(account.client().get("/api/auth/me").body()).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
  }

  @Test
  void unaPasskeyNoRegistradaSeRechaza() throws Exception {
    Account account = p2Account();
    assertThat(authenticate(account.client(), new TestAuthenticator()).status()).isEqualTo(401);
  }

  @Test
  void sinPedirOpcionesNoHayChallenge() throws Exception {
    Account account = p2Account();
    var response = account.client().post("/api/auth/passkeys/authentication",
        account.authenticator().assertionFor(new byte[32]));
    assertThat(response.status()).isEqualTo(401);
  }

  @Test
  void cincoFallosCierranLaSesion() throws Exception {
    Account account = p2Account();
    account.authenticator().tamperSignature = true;
    for (int i = 1; i <= 4; i++) {
      assertThat(authenticate(account.client(), account.authenticator()).status()).isEqualTo(401);
      assertThat(account.client().get("/api/auth/me").status()).as("fallo " + i).isEqualTo(200);
    }
    assertThat(authenticate(account.client(), account.authenticator()).status()).isEqualTo(401);
    assertThat(account.client().get("/api/auth/me").status()).as("quinto fallo").isEqualTo(401);
  }

  @Test
  void unUsuarioDesactivadoTrasLaContraseñaNoLlegaAF() throws Exception {
    Account account = p2Account();
    try (var c = connectAs("cms_security_tool");
        var ps = c.prepareStatement("UPDATE app_user SET enabled = false WHERE email = ?")) {
      ps.setString(1, account.email());
      ps.executeUpdate();
    }
    assertThat(authenticate(account.client(), account.authenticator()).status()).isEqualTo(401);
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void unaPasskeySuspendidaNoSeOfreceNiSirve() throws Exception {
    Account account = p2Account();
    var options = account.client().post("/api/auth/passkeys/authentication/options", null);
    try (var c = connectAs("cms_api");
        var ps = c.prepareStatement("UPDATE passkey_credential SET suspended_at = now() WHERE user_id = ?")) {
      ps.setObject(1, userIdOf(account.email()));
      ps.executeUpdate();
    }
    var response = account.client().post("/api/auth/passkeys/authentication",
        account.authenticator().assertion(options.body()));
    assertThat(response.status()).isEqualTo(401);
    assertThat(account.client().get("/api/auth/me").body())
        .isEqualTo("{\"state\":\"PASSKEY_REQUIRED\",\"passkeys\":{\"usable\":0,\"suspended\":1}}");
    assertThat(account.client().post("/api/auth/passkeys/authentication/options", null).status()).isEqualTo(409);
  }

  /** Usuario con una passkey registrada y una sesión nueva en P2. */
  private Account p2Account() throws Exception {
    String email = createUser(true, true);
    ApiClient enrollClient = client();
    enrollClient.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(enrollClient);
    return new Account(passwordSession(email), authenticator, email);
  }

  @SuppressWarnings("unchecked")
  private <T> T sessionAttribute(String cookie, String name) {
    Session session = (Session) sessions.findById(sessionId(cookie));
    return session.getAttribute(name);
  }
}
