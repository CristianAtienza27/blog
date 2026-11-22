package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registro de passkeys (P1 y F con verificación reciente; R en RecoveryCodeTests). */
class WebAuthnRegistrationTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void lasOpcionesExigenVerificacionDeUsuarioYNoUsanElCorreoComoId() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    JsonNode options = JSON.readTree(client.post("/api/auth/passkeys/registration/options", null).body());
    assertThat(options.path("rp").path("id").asString()).isEqualTo("localhost");
    assertThat(options.path("authenticatorSelection").path("userVerification").asString()).isEqualTo("required");
    assertThat(options.path("authenticatorSelection").path("residentKey").asString()).isEqualTo("preferred");
    assertThat(options.path("attestation").asString()).isEqualTo("none");
    assertThat(options.path("timeout").asLong()).isEqualTo(300000);
    assertThat(options.path("pubKeyCredParams").findValues("alg").stream().map(JsonNode::asInt))
        .containsExactlyInAnyOrder(-7, -8, -257);
    byte[] userId = Base64.getUrlDecoder().decode(options.path("user").path("id").asString());
    assertThat(userId).hasSize(32);
    assertThat(new String(userId, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(email);
    assertThat(options.path("user").path("name").asString()).isEqualTo(email);
    assertThat(Base64.getUrlDecoder().decode(options.path("challenge").asString())).hasSize(32);
  }

  @Test
  void registrarDesdeP1PasaAP2YNoDaF() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    String p1Session = client.cookie(SecurityConfig.SESSION_COOKIE);
    var options = client.post("/api/auth/passkeys/registration/options", null);
    TestAuthenticator authenticator = new TestAuthenticator();
    var registered = client.post("/api/auth/passkeys/registration", authenticator.register(options.body(), "Portátil"));
    assertThat(registered.status()).isEqualTo(201);
    assertThat(registered.body()).isEqualTo("{\"state\":\"PASSKEY_REQUIRED\"}");
    assertThat(client.cookie(SecurityConfig.SESSION_COOKIE)).isNotEqualTo(p1Session);
    // Registrar no da acceso completo: P1 ya no puede registrar otra y F exige autenticarse.
    assertThat(client.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(403);
    assertThat(client.post("/api/auth/passkeys/registration/options", null).status()).isEqualTo(403);
    UUID id = userIdOf(email);
    assertThat(query("cms_api", "SELECT label || ':' || sign_count || ':' || uv_initialized || ':' || backup_eligible"
        + " FROM passkey_credential WHERE user_id = ?", id)).containsExactly("Portátil:0:true:false");
    assertThat(query("cms_security_tool", "SELECT enroll_required::text FROM app_user WHERE id = ?", id))
        .as("el registro no termina hasta autenticarse").containsExactly("true");
  }

  @Test
  void desdeFConVerificacionRecienteSeAñadeOtraYSigueEnF() throws Exception {
    Account account = fullAccount();
    TestAuthenticator second = enroll(account.client());
    assertThat(account.client().get("/api/auth/me").body())
        .isEqualTo("{\"state\":\"AUTHENTICATED\",\"passkeys\":{\"usable\":2,\"suspended\":0}}");
    assertThat(second).isNotNull();
  }

  @Test
  void cadaComprobacionDelRegistroRechaza() throws Exception {
    rejects(a -> a.uv = false);
    rejects(a -> a.up = false);
    rejects(a -> a.origin = "http://localhost:4201");
    rejects(a -> a.rpId = "example.com");
    rejects(a -> a.typeOverride = "webauthn.get");
    rejects(a -> a.crossOrigin = true);
    rejects(a -> a.challengeOverride = new byte[32]);
  }

  private void rejects(Consumer<TestAuthenticator> change) throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    var options = client.post("/api/auth/passkeys/registration/options", null);
    TestAuthenticator authenticator = new TestAuthenticator();
    change.accept(authenticator);
    var response = client.post("/api/auth/passkeys/registration", authenticator.register(options.body(), null));
    assertThat(response.status()).isEqualTo(400);
    assertThat(query("cms_api", "SELECT count(*) FROM passkey_credential WHERE user_id = ?", userIdOf(email)))
        .containsExactly("0");
    // Sigue en P1 y el challenge ya se consumió: hay que pedir otras opciones.
    assertThat(client.get("/api/auth/me").body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
  }

  @Test
  void unaCredencialYaRegistradaNoSeRegistraOtraVez() throws Exception {
    Account owner = fullAccount();
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    var options = client.post("/api/auth/passkeys/registration/options", null);
    var response = client.post("/api/auth/passkeys/registration", owner.authenticator().register(options.body(), null));
    assertThat(response.status()).isEqualTo(400);
  }

  @Test
  void entradasMalFormadas() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    var options = client.post("/api/auth/passkeys/registration/options", null);
    assertThat(client.post("/api/auth/passkeys/registration", "{\"credential\": 3}").status()).isEqualTo(400);
    assertThat(client.post("/api/auth/passkeys/registration", "no es json").status()).isEqualTo(400);
    options = client.post("/api/auth/passkeys/registration/options", null);
    String tooLong = new TestAuthenticator().register(options.body(), "x".repeat(65));
    assertThat(client.post("/api/auth/passkeys/registration", tooLong).status()).isEqualTo(400);
    // Una petición mal formada no consume el challenge: la respuesta válida sigue sirviendo.
    assertThat(client.post("/api/auth/passkeys/registration", new TestAuthenticator().register(options.body(), null))
        .status()).isEqualTo(201);
    // Sin opciones previas en esta sesión no hay challenge.
    ApiClient other = client();
    other.login(createUser(true, true), PASSWORD);
    String noChallenge = new TestAuthenticator().register(options.body(), null);
    assertThat(other.post("/api/auth/passkeys/registration", noChallenge).status()).isEqualTo(400);
  }
}
