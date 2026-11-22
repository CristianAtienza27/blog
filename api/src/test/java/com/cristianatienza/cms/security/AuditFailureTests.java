package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E2 con fallos REALES de persistencia de la auditoría (el propietario retira a cms_app el INSERT
 * sobre audit_event y lo devuelve al terminar): los contadores ya están confirmados, no se concede
 * acceso, y cerrar sesión o todas las sesiones se completa sin que se deshaga ninguna revocación.
 */
class AuditFailureTests extends LimitsTestSupport {

  @Autowired SecretKeys keys;

  private static void breakAudit() throws Exception {
    asOwner("REVOKE INSERT ON audit_event FROM cms_app");
  }

  @AfterEach
  void restoreAudit() throws Exception {
    asOwner("GRANT INSERT ON audit_event TO cms_app");
  }

  @Test
  void unFalloConAuditoriaRotaDejaElContadorIncrementadoYNoDaAcceso() throws Exception {
    String email = createUser(true, true);
    String ip = freshIp();
    breakAudit();
    assertThat(clientFrom(ip).login(email, "mal").status()).isEqualTo(500);
    // El contador se confirmó antes de intentar auditar.
    assertThat(query("cms_api", "SELECT failures FROM login_attempt WHERE key_type = 'account' AND key_hash = ?",
        keys.attemptKey("account", email))).containsExactly("1");
    // Con la contraseña correcta tampoco hay acceso: sin auditoría no se crea la sesión.
    ApiClient client = clientFrom(ip);
    assertThat(client.login(email, PASSWORD).status()).isEqualTo(500);
    assertThat(client.cookie(SecurityConfig.SESSION_COOKIE)).isNull();
  }

  @Test
  void conAuditoriaRotaNoSeLlegaAF() throws Exception {
    String email = createUser(true, true);
    ApiClient client = clientFrom(freshIp());
    client.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(client);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    breakAudit();
    var response = client.post("/api/auth/passkeys/authentication", authenticator.assertion(options.body()));
    assertThat(response.status()).isEqualTo(500);
    assertThat(response.setCookie("__Host-DEVICE")).as("sin dispositivo").isNull();
    restoreAudit();
    assertThat(client.get("/api/auth/me").body()).as("sigue en P2").startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
  }

  @Test
  void cerrarSesionYCerrarTodasSeCompletanAunqueFalleLaAuditoria() throws Exception {
    Account account = fullAccountFrom(freshIp());
    ApiClient second = clientFrom(freshIp());
    second.login(account.email(), PASSWORD);
    breakAudit();
    assertThat(second.post("/api/auth/logout", null).status()).isEqualTo(204);
    assertThat(second.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(account.client().post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(204);
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
    restoreAudit();
    // La revocación de dispositivos no se deshizo por el fallo de auditoría.
    assertThat(deviceStates(account.email())).containsOnly("REVOKED");
    assertThat(query("cms_api", "SELECT count(*) FROM spring_session WHERE principal_name = ?",
        userIdOf(account.email()).toString())).containsExactly("0");
  }
}
