package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * __Host-DEVICE: emisión al completar F, atributos de la cookie, nunca un factor (mismas decisiones
 * de autorización con y sin ella), límites del dispositivo, revocación individual y total, y que la
 * revocación nunca impide entrar con contraseña y passkey.
 */
class DeviceTokenTests extends LimitsTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void seEmiteAlCompletarFConLosAtributosDeUnaCookieHost() throws Exception {
    String email = createUser(true, true);
    ApiClient client = clientFrom(freshIp());
    client.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(client);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    var full = client.post("/api/auth/passkeys/authentication", authenticator.assertion(options.body()));
    String cookie = full.setCookie("__Host-DEVICE");
    assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/").doesNotContain("Domain");
    assertThat(cookie).containsPattern("Max-Age=(77[0-9]{5})");
    assertThat(deviceStates(email)).containsExactly("PENDING");
    // Hasta F no hay cookie: ni la contraseña ni el registro la emiten.
    ApiClient p2 = clientFrom(freshIp());
    assertThat(p2.login(email, PASSWORD).setCookie("__Host-DEVICE")).isNull();
  }

  @Test
  void laCookieNoCambiaNingunaDecisionDeAutorizacion() throws Exception {
    Account account = fullAccountFrom(freshIp());
    for (boolean withCookie : new boolean[] {false, true}) {
      ApiClient client = withCookie ? sameDevice(account.client(), freshIp()) : clientFrom(freshIp());
      client.login(account.email(), PASSWORD);
      // En P2, con o sin dispositivo de confianza, lo mismo: nada de F.
      assertThat(client.get("/api/auth/me").body()).as("cookie=" + withCookie).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
      assertThat(client.get("/api/auth/passkeys").status()).isEqualTo(403);
      assertThat(client.get("/api/auth/devices").status()).isEqualTo(403);
      assertThat(client.post("/api/auth/recovery-codes", null).status()).isEqualTo(403);
      assertThat(client.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(403);
    }
    // Sin contraseña, la cookie sola no da nada.
    ApiClient onlyCookie = sameDevice(account.client(), freshIp());
    assertThat(onlyCookie.get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void elSiguienteAccesoConfirmaYRotaElToken() throws Exception {
    Account account = fullAccountFrom(freshIp());
    String first = account.client().cookie("__Host-DEVICE");
    ApiClient next = sameDevice(account.client(), freshIp());
    next.login(account.email(), PASSWORD);
    assertThat(deviceStates(account.email())).as("presentado: confirmado").containsExactly("ACTIVE");
    assertThat(authenticate(next, account.authenticator()).status()).isEqualTo(200);
    assertThat(next.cookie("__Host-DEVICE")).isNotEqualTo(first);
    assertThat(deviceStates(account.email())).containsExactly("ACTIVE", "PENDING");
  }

  @Test
  void conDispositivoDiezFallosBloqueanYVeinteEn24HorasLoRevocan() throws Exception {
    Account account = fullAccountFrom(freshIp());
    ApiClient browser = sameDevice(account.client(), freshIp());
    for (int i = 0; i < 10; i++) {
      assertThat(sameDevice(account.client(), freshIp()).login(account.email(), "mal").status()).as("fallo " + i)
          .isEqualTo(401);
    }
    assertThat(browser.login(account.email(), PASSWORD).status()).as("dispositivo bloqueado").isEqualTo(429);
    clock.advance(Duration.ofMinutes(16));
    for (int i = 0; i < 10; i++) {
      sameDevice(account.client(), freshIp()).login(account.email(), "mal");
    }
    assertThat(deviceStates(account.email())).containsOnly("REVOKED");
    // Revocado: sin alivio, pero contraseña y passkey siguen dando acceso (pasado el bloqueo).
    clock.advance(Duration.ofHours(2));
    ApiClient after = sameDevice(account.client(), freshIp());
    assertThat(after.login(account.email(), PASSWORD).status()).isEqualTo(200);
    assertThat(authenticate(after, account.authenticator()).status()).isEqualTo(200);
  }

  @Test
  void revocacionIndividualYTotalSinPerderElAcceso() throws Exception {
    Account account = fullAccountFrom(freshIp());
    var devices = JSON.readTree(account.client().get("/api/auth/devices").body());
    assertThat(devices).hasSize(1);
    assertThat(devices.get(0).path("current").asBoolean()).isTrue();
    assertThat(devices.get(0).propertyNames()).containsExactlyInAnyOrder("id", "createdAt", "lastUsedAt", "expiresAt",
        "current");
    String id = devices.get(0).path("id").asString();
    assertThat(account.client().delete("/api/auth/devices/" + id).status()).isEqualTo(204);
    assertThat(account.client().delete("/api/auth/devices/" + id).status()).isEqualTo(404);
    assertThat(deviceStates(account.email())).containsOnly("REVOKED");
    // Revocado: el acceso sigue siendo contraseña + passkey.
    ApiClient again = sameDevice(account.client(), freshIp());
    again.login(account.email(), PASSWORD);
    assertThat(authenticate(again, account.authenticator()).status()).isEqualTo(200);
    assertThat(deviceStates(account.email())).as("familia nueva").contains("PENDING");

    // Cerrar todas las sesiones revoca también los dispositivos (por defecto)…
    assertThat(again.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(204);
    assertThat(deviceStates(account.email())).containsOnly("REVOKED");
    // …salvo que se pida conservarlos.
    ApiClient third = clientFrom(freshIp());
    third.login(account.email(), PASSWORD);
    authenticate(third, account.authenticator());
    assertThat(third.post("/api/auth/sessions/revoke-all", "{\"devices\": false}").status()).isEqualTo(204);
    assertThat(deviceStates(account.email())).contains("PENDING");
  }

  @Test
  void unDispositivoRevocadoNoDaAlivio() throws Exception {
    Account account = fullAccountFrom(freshIp());
    String id = JSON.readTree(account.client().get("/api/auth/devices").body()).get(0).path("id").asString();
    assertThat(account.client().delete("/api/auth/devices/" + id).status()).isEqualTo(204);
    String ip = freshIp();
    for (int i = 0; i < 5; i++) {
      assertThat(sameDevice(account.client(), ip).login(account.email(), "mal").status()).isEqualTo(401);
    }
    // Límite normal (5), no el atenuado (10): la cookie revocada ya no exime de nada.
    assertThat(sameDevice(account.client(), ip).login(account.email(), PASSWORD).status()).isEqualTo(429);
  }

  @Test
  void otroUsuarioNoPuedeRevocarlo() throws Exception {
    Account owner = fullAccountFrom(freshIp());
    String id = JSON.readTree(owner.client().get("/api/auth/devices").body()).get(0).path("id").asString();
    Account other = fullAccountFrom(freshIp());
    assertThat(other.client().delete("/api/auth/devices/" + id).status()).isEqualTo(404);
    assertThat(deviceStates(owner.email())).doesNotContain("REVOKED");
    assertThat(List.of(owner.email())).isNotEmpty();
  }
}
