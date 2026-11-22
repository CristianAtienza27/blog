package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Rotación de __Host-DEVICE con el reloj controlado (reglas aprobadas, tolerancia de 2 min):
 * peticiones simultáneas, respuestas perdidas y desordenadas (el escenario exacto aprobado),
 * tolerancia, y revocación proporcional solo con evidencia suficiente.
 */
class DeviceRotationTests extends LimitsTestSupport {

  private String ip;

  /** Navegador con un token concreto, desde la IP y el agente habituales del usuario. */
  private ApiClient browser(String token) {
    ApiClient client = clientFrom(ip);
    client.cookies().put("__Host-DEVICE", token);
    return client;
  }

  /** Cuenta con un token A ya ACTIVE (presentado una vez) y su autenticador. */
  private record Setup(Account account, String tokenA) {}

  private Setup activeToken() throws Exception {
    ip = freshIp();
    Account account = fullAccountFrom(ip);
    String a = account.client().cookie("__Host-DEVICE");
    browser(a).login(account.email(), PASSWORD); // primera presentación: A pasa a ACTIVE
    assertThat(deviceStates(account.email())).containsExactly("ACTIVE");
    return new Setup(account, a);
  }

  /** Completa F con ese navegador y devuelve el token nuevo que le llega (o null). */
  private String fullAccess(ApiClient client, Account account) throws Exception {
    assertThat(client.login(account.email(), PASSWORD).status()).isEqualTo(200);
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    var full = client.post("/api/auth/passkeys/authentication", account.authenticator().assertion(options.body()));
    assertThat(full.status()).isEqualTo(200);
    String cookie = full.setCookie("__Host-DEVICE");
    return cookie == null ? null : cookie.split(";", 2)[0].split("=", 2)[1];
  }

  private List<String> events(String email, String event) throws Exception {
    return query("cms_security_tool", "SELECT event FROM audit_event WHERE user_id = ? AND event = ?",
        userIdOf(email), event);
  }

  @Test
  void elEscenarioExactoDeRespuestasDesordenadasNoRevocaLaFamilia() throws Exception {
    Setup s = activeToken();
    String b = fullAccess(browser(s.tokenA()), s.account());          // 2. F emite B (PENDING)
    assertThat(b).isNotNull();
    browser(b).login(s.account().email(), PASSWORD);                   // 3. B pasa a ACTIVE y A a RETIRED
    assertThat(deviceStates(s.account().email())).containsExactly("RETIRED", "ACTIVE");
    clock.advance(Duration.ofMinutes(3));                              // 4. Llega tarde Set-Cookie: A
    assertThat(browser(s.tokenA()).login(s.account().email(), PASSWORD).status()).isEqualTo(200); // 5. presenta A
    // Resultado: no se revoca la familia, se registra el token caducado y B sigue valiendo.
    assertThat(deviceStates(s.account().email())).doesNotContain("REVOKED");
    assertThat(events(s.account().email(), "device.token_stale")).hasSize(1);
    browser(b).login(s.account().email(), PASSWORD);
    assertThat(deviceStates(s.account().email())).contains("ACTIVE");
    // Si ese navegador completa F, recibe un sucesor del ACTIVE (B) y vuelve a tener confianza.
    String successor = fullAccess(browser(s.tokenA()), s.account());
    assertThat(successor).isNotNull().isNotEqualTo(b);
    assertThat(deviceStates(s.account().email())).containsExactly("RETIRED", "ACTIVE", "PENDING");
  }

  @Test
  void dentroDeLosDosMinutosElTokenAnteriorConservaLaConfianza() throws Exception {
    Setup s = activeToken();
    String b = fullAccess(browser(s.tokenA()), s.account());
    browser(b).login(s.account().email(), PASSWORD);
    clock.advance(Duration.ofMinutes(1).plusSeconds(59));
    browser(s.tokenA()).login(s.account().email(), PASSWORD);
    assertThat(events(s.account().email(), "device.token_grace_use")).hasSize(1);
    assertThat(events(s.account().email(), "device.token_stale")).isEmpty();
    clock.advance(Duration.ofSeconds(2));
    browser(s.tokenA()).login(s.account().email(), PASSWORD);
    assertThat(events(s.account().email(), "device.token_stale")).as("a los 2:01").hasSize(1);
  }

  @Test
  void diezAccesosCompletosSimultaneosConElMismoTokenRotanUnaSolaVez() throws Exception {
    Setup s = activeToken();
    List<ApiClient> browsers = new ArrayList<>();
    List<String> bodies = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      ApiClient client = browser(s.tokenA());
      client.login(s.account().email(), PASSWORD);
      bodies.add(s.account().authenticator().assertion(
          client.post("/api/auth/passkeys/authentication/options", null).body()));
      browsers.add(client);
    }
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<ApiClient.Response>> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      ApiClient client = browsers.get(i);
      String body = bodies.get(i);
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
          return client.post("/api/auth/passkeys/authentication", body);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }));
    }
    start.countDown();
    List<ApiClient.Response> responses = calls.stream().map(CompletableFuture::join).toList();
    assertThat(responses).allMatch(r -> r.status() == 200);
    assertThat(responses.stream().filter(r -> r.setCookie("__Host-DEVICE") != null).count()).isEqualTo(1);
    assertThat(deviceStates(s.account().email())).containsExactly("ACTIVE", "PENDING");
  }

  @Test
  void unaRespuestaPerdidaNoRevocaYSeEmiteOtroSucesor() throws Exception {
    Setup s = activeToken();
    String lost = fullAccess(browser(s.tokenA()), s.account()); // el navegador no recibe B
    clock.advance(Duration.ofSeconds(40));
    assertThat(fullAccess(browser(s.tokenA()), s.account())).as("rotación en curso: sin Set-Cookie").isNull();
    clock.advance(Duration.ofMinutes(2));
    String next = fullAccess(browser(s.tokenA()), s.account());
    assertThat(next).isNotNull().isNotEqualTo(lost);
    assertThat(deviceStates(s.account().email())).containsExactly("ACTIVE", "SUPERSEDED", "PENDING");
    // Llega tarde la respuesta perdida (B) y se presenta: su sustituto sigue pendiente → se acepta.
    browser(lost).login(s.account().email(), PASSWORD);
    assertThat(events(s.account().email(), "device.token_out_of_order")).hasSize(1);
    assertThat(deviceStates(s.account().email())).containsExactly("RETIRED", "ACTIVE", "SUPERSEDED");
  }

  @Test
  void tokensAlternadosDesdeDosOrigenesEnQuinceMinutosRevocanLaFamilia() throws Exception {
    Setup s = activeToken();
    String b = fullAccess(browser(s.tokenA()), s.account());
    browser(b).login(s.account().email(), PASSWORD);                   // legítimo, origen habitual
    clock.advance(Duration.ofMinutes(3));
    ApiClient copy = clientFrom(freshIp()).agent("otro-navegador/9");   // copia de A desde otro origen
    copy.cookies().put("__Host-DEVICE", s.tokenA());
    copy.login(s.account().email(), PASSWORD);
    assertThat(deviceStates(s.account().email())).containsOnly("REVOKED");
    // Revocado: nunca impide entrar con contraseña y passkey.
    assertThat(fullAccess(browser(b), s.account())).isNotNull();
  }

  @Test
  void tresPresentacionesCaducadasEn24HorasSinAccesoCompletoRevocan() throws Exception {
    Setup s = activeToken();
    String b = fullAccess(browser(s.tokenA()), s.account());
    browser(b).login(s.account().email(), PASSWORD);
    clock.advance(Duration.ofMinutes(20)); // fuera de la ventana de alternancia
    for (int i = 0; i < 2; i++) {
      browser(s.tokenA()).login(s.account().email(), PASSWORD);
      assertThat(deviceStates(s.account().email())).as("presentación " + (i + 1)).doesNotContain("REVOKED");
    }
    browser(s.tokenA()).login(s.account().email(), PASSWORD);
    assertThat(deviceStates(s.account().email())).containsOnly("REVOKED");
  }

  @Test
  void conUnTokenCaducadoRigenLosLimitesNormalesYAlcanzarlosRevoca() throws Exception {
    Setup s = activeToken();
    String b = fullAccess(browser(s.tokenA()), s.account());
    browser(b).login(s.account().email(), PASSWORD);
    clock.advance(Duration.ofMinutes(20));
    for (int i = 0; i < 5; i++) {
      assertThat(browser(s.tokenA()).login(s.account().email(), "mal").status()).isEqualTo(401);
    }
    // Límite normal (5), no el atenuado (10); y alcanzarlo con un token caducado revoca la familia.
    assertThat(browser(s.tokenA()).login(s.account().email(), PASSWORD).status()).isEqualTo(429);
    assertThat(deviceStates(s.account().email())).containsOnly("REVOKED");
  }
}
