package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Códigos de recuperación con la API real: generación (solo HMAC en la base de datos), uso único
 * (también simultáneo), estado R (solo registrar), cierre de las demás sesiones y fin de la
 * recuperación. Un código nunca da acceso completo.
 */
class RecoveryCodeTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private List<String> generate(ApiClient client) throws Exception {
    var response = client.post("/api/auth/recovery-codes", null);
    assertThat(response.status()).isEqualTo(201);
    assertThat(response.header("Cache-Control")).contains("no-store");
    List<String> codes = new ArrayList<>();
    JSON.readTree(response.body()).path("codes").forEach(n -> codes.add(n.asString()));
    return codes;
  }

  private ApiClient.Response redeem(ApiClient client, String code) throws Exception {
    return client.post("/api/auth/recovery", "{\"code\": \"" + code + "\"}");
  }

  @Test
  void seGeneranDiezCodigosYLaBaseDeDatosSoloGuardaSuHmac() throws Exception {
    Account account = fullAccount();
    List<String> codes = generate(account.client());
    assertThat(codes).hasSize(10).doesNotHaveDuplicates()
        .allMatch(c -> c.matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"));
    UUID user = userIdOf(account.email());
    List<String> stored = query("cms_api", "SELECT r::text FROM recovery_code r WHERE user_id = ?", user);
    assertThat(stored).hasSize(10);
    for (String code : codes) {
      String plain = code.replace("-", "");
      assertThat(stored).noneMatch(row -> row.contains(plain) || row.contains(code));
    }
    assertThat(query("cms_api", "SELECT DISTINCT length(code_hmac) FROM recovery_code WHERE user_id = ?", user))
        .containsExactly("32");
  }

  @Test
  void canjeEnP2PasaARQueSoloPermiteRegistrarYCierraLasDemasSesiones() throws Exception {
    Account account = fullAccount();
    List<String> codes = generate(account.client());
    ApiClient lost = passwordSession(account.email());
    String before = lost.cookie(SecurityConfig.SESSION_COOKIE);
    var redeemed = redeem(lost, codes.get(0));
    assertThat(redeemed.status()).isEqualTo(200);
    assertThat(redeemed.body()).isEqualTo("{\"state\":\"RECOVERY\"}");
    assertThat(lost.cookie(SecurityConfig.SESSION_COOKIE)).isNotEqualTo(before);
    // La sesión F anterior se cierra.
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
    // R: solo registrar una passkey (ni F, ni autenticar, ni códigos, ni gestión).
    assertThat(lost.get("/api/auth/me").body()).isEqualTo("{\"state\":\"RECOVERY\"}");
    assertThat(lost.post("/api/auth/passkeys/authentication/options", null).status()).isEqualTo(403);
    assertThat(lost.post("/api/auth/recovery", "{\"code\": \"" + codes.get(1) + "\"}").status()).isEqualTo(403);
    assertThat(lost.post("/api/auth/recovery-codes", null).status()).isEqualTo(403);
    assertThat(lost.get("/api/auth/passkeys").status()).isEqualTo(403);
    assertThat(lost.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(403);
    assertThat(lost.post("/api/auth/step-up/options", null).status()).isEqualTo(403);

    // Registrar la passkey nueva termina la recuperación: P2, y los códigos restantes ya no valen.
    TestAuthenticator replacement = enroll(lost);
    assertThat(lost.get("/api/auth/me").body()).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
    UUID user = userIdOf(account.email());
    assertThat(query("cms_api", "SELECT count(*) FROM recovery_code WHERE user_id = ? AND used_at IS NULL"
        + " AND invalidated_at IS NULL", user)).containsExactly("0");
    assertThat(redeem(passwordSession(account.email()), codes.get(2)).status()).isEqualTo(401);
    // Acceso completo solo autenticándose con la passkey nueva.
    assertThat(authenticate(lost, replacement).body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");
  }

  @Test
  void unCodigoSirveUnaSolaVez() throws Exception {
    Account account = fullAccount();
    List<String> codes = generate(account.client());
    assertThat(redeem(passwordSession(account.email()), codes.get(0)).status()).isEqualTo(200);
    assertThat(redeem(passwordSession(account.email()), codes.get(0)).status()).isEqualTo(401);
  }

  @Test
  void deVeinteCanjesSimultaneosDelMismoCodigoSoloUnoTieneExito() throws Exception {
    Account account = fullAccount();
    String code = generate(account.client()).get(0);
    List<ApiClient> clients = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      clients.add(passwordSession(account.email()));
    }
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Integer>> calls = new ArrayList<>();
    for (ApiClient client : clients) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
          return redeem(client, code).status();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }));
    }
    start.countDown();
    List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
    assertThat(statuses).as("sin 500").allMatch(s -> s == 200 || s == 401);
    assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
    assertThat(query("cms_api", "SELECT count(*) FROM recovery_code WHERE user_id = ? AND used_at IS NOT NULL",
        userIdOf(account.email()))).containsExactly("1");
  }

  @Test
  void regenerarInvalidaLosAnteriores() throws Exception {
    Account account = fullAccount();
    List<String> first = generate(account.client());
    List<String> second = generate(account.client());
    assertThat(second).doesNotContainAnyElementsOf(first);
    assertThat(redeem(passwordSession(account.email()), first.get(0)).status()).isEqualTo(401);
    assertThat(redeem(passwordSession(account.email()), second.get(0)).status()).isEqualTo(200);
  }

  @Test
  void normalizaLoQueTecleaElUsuario() throws Exception {
    Account account = fullAccount();
    String code = generate(account.client()).get(0);
    String typed = " " + code.toLowerCase().replace("-", " ").replace('0', 'o').replace('1', 'l') + " ";
    assertThat(redeem(passwordSession(account.email()), typed).status()).isEqualTo(200);
  }

  @Test
  void codigosAjenosMalFormadosYCincoFallos() throws Exception {
    Account owner = fullAccount();
    String foreign = generate(owner.client()).get(0);
    Account account = fullAccount();
    ApiClient client = passwordSession(account.email());
    assertThat(redeem(client, "no-es-un-codigo").status()).isEqualTo(400);
    assertThat(client.post("/api/auth/recovery", "{}").status()).isEqualTo(400);
    assertThat(redeem(client, foreign).status()).as("código de otro usuario").isEqualTo(401);
    for (int i = 2; i <= 4; i++) {
      assertThat(redeem(client, "0000-0000-0000-000" + i).status()).isEqualTo(401);
    }
    assertThat(client.get("/api/auth/me").status()).as("4 fallos").isEqualTo(200);
    assertThat(redeem(client, "0000-0000-0000-0009").status()).isEqualTo(401);
    assertThat(client.get("/api/auth/me").status()).as("quinto fallo: sesión cerrada").isEqualTo(401);
    // El código ajeno sigue sin usar para su dueño.
    assertThat(redeem(passwordSession(owner.email()), foreign).status()).isEqualTo(200);
  }

  @Test
  void soloEnP2() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    assertThat(redeem(client, "0000-0000-0000-0000").status()).as("P1").isEqualTo(403);
    Account full = fullAccount();
    assertThat(redeem(full.client(), "0000-0000-0000-0000").status()).as("F").isEqualTo(403);
  }
}
