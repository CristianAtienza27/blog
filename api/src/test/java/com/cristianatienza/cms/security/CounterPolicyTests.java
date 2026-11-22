package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Política del contador con la API real: cada clasificación, la procedencia en webauthn_assertion
 * y los escenarios de orden aprobados (emisiones y verificaciones en distintos órdenes). Solo se
 * trata como benigno lo que el registro demuestra.
 */
class CounterPolicyTests extends SecurityTestSupport {

  /** Usuario con una passkey de contador controlado (registrada con {@code initial}). */
  private record Setup(String email, UUID user, TestAuthenticator authenticator) {}

  private Setup setup(long initial) throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    TestAuthenticator authenticator = new TestAuthenticator();
    authenticator.counter = initial;
    enroll(client, authenticator);
    return new Setup(email, userIdOf(email), authenticator);
  }

  /** Opciones en una sesión P2 nueva. */
  private record Pending(ApiClient client, String options) {}

  private Pending issue(Setup s) throws Exception {
    ApiClient client = passwordSession(s.email());
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    assertThat(options.status()).isEqualTo(200);
    return new Pending(client, options.body());
  }

  private String sign(Setup s, Pending p, long counter) {
    s.authenticator().counter = counter;
    return s.authenticator().assertion(p.options());
  }

  private int verify(Pending p, String body) throws Exception {
    return p.client().post("/api/auth/passkeys/authentication", body).status();
  }

  private List<String> classifications(Setup s) throws Exception {
    return query("cms_api", "SELECT outcome || ':' || classification FROM webauthn_assertion WHERE user_id = ?"
        + " ORDER BY id", s.user());
  }

  private String credential(Setup s) throws Exception {
    return query("cms_api", "SELECT sign_count || ':' || anomaly_count || ':' || (suspended_at IS NOT NULL)"
        + " FROM passkey_credential WHERE user_id = ?", s.user()).get(0);
  }

  @Test
  void sinContadorSeAceptaSiempre() throws Exception {
    Setup s = setup(0);
    for (int i = 0; i < 2; i++) {
      Pending p = issue(s);
      assertThat(verify(p, sign(s, p, 0))).isEqualTo(200);
    }
    assertThat(classifications(s)).containsExactly("accepted:no_counter", "accepted:no_counter");
    assertThat(credential(s)).isEqualTo("0:0:false");
  }

  @Test
  void crecienteSeAceptaYSeGuarda() throws Exception {
    Setup s = setup(0);
    Pending a = issue(s);
    assertThat(verify(a, sign(s, a, 5))).isEqualTo(200);
    Pending b = issue(s);
    assertThat(verify(b, sign(s, b, 9))).isEqualTo(200);
    assertThat(classifications(s)).containsExactly("accepted:increasing", "accepted:increasing");
    assertThat(credential(s)).isEqualTo("9:0:false");
  }

  /** Escenario 2: emitir B, emitir A, firmar A (n+1) y B (n+2), verificar B y luego A. */
  @Test
  void solapada() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending b = issue(s);
    Pending a = issue(s);
    String bodyA = sign(s, a, 6);
    String bodyB = sign(s, b, 7);
    assertThat(verify(b, bodyB)).isEqualTo(200);
    assertThat(verify(a, bodyA)).isEqualTo(401);
    assertThat(classifications(s)).containsExactly("accepted:increasing", "accepted:increasing", "rejected:overlapped");
    assertThat(credential(s)).as("no cuenta para la suspensión").isEqualTo("7:0:false");
  }

  /** Escenario 3: emitir A, emitir B, firmar A (n+1) y B (n+2), verificar B y luego A. */
  @Test
  void retrasada() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending a = issue(s);
    Pending b = issue(s);
    String bodyA = sign(s, a, 6);
    String bodyB = sign(s, b, 7);
    assertThat(verify(b, bodyB)).isEqualTo(200);
    assertThat(verify(a, bodyA)).isEqualTo(401);
    assertThat(classifications(s)).containsExactly("accepted:increasing", "accepted:increasing", "rejected:delayed");
    assertThat(credential(s)).isEqualTo("7:0:false");
  }

  /** Escenario 1: emitir A y B, verificar A (n+1) y luego B (n+2): ambas normales. */
  @Test
  void enOrdenAmbasNormales() throws Exception {
    Setup s = setup(0);
    Pending a = issue(s);
    Pending b = issue(s);
    String bodyA = sign(s, a, 1);
    String bodyB = sign(s, b, 2);
    assertThat(verify(a, bodyA)).isEqualTo(200);
    assertThat(verify(b, bodyB)).isEqualTo(200);
    assertThat(classifications(s)).containsExactly("accepted:increasing", "accepted:increasing");
  }

  /** Escenarios 4 y «dos débiles en 30 días»: contador igual al guardado, sin otra aserción. */
  @Test
  void igualSinConcurrenciaEsDebilYDosSuspenden() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending first = issue(s);
    assertThat(verify(first, sign(s, first, 5))).isEqualTo(401);
    assertThat(credential(s)).isEqualTo("5:1:false");
    Pending second = issue(s);
    assertThat(verify(second, sign(s, second, 5))).isEqualTo(401);
    assertThat(credential(s)).as("segunda débil: suspendida").isEqualTo("5:2:true");
    assertThat(classifications(s)).containsExactly("accepted:increasing", "rejected:weak_anomaly",
        "rejected:weak_anomaly");
    // Suspendida: no se ofrece y me lo indica.
    ApiClient client = passwordSession(s.email());
    assertThat(client.post("/api/auth/passkeys/authentication/options", null).status()).isEqualTo(409);
    assertThat(client.get("/api/auth/me").body())
        .isEqualTo("{\"state\":\"PASSKEY_REQUIRED\",\"passkeys\":{\"usable\":0,\"suspended\":1}}");
  }

  @Test
  void regresionSuspendeDeInmediato() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending p = issue(s);
    assertThat(verify(p, sign(s, p, 3))).isEqualTo(401);
    assertThat(credential(s)).isEqualTo("5:1:true");
    assertThat(classifications(s)).last().isEqualTo("rejected:strong_anomaly");
  }

  @Test
  void pasarDePositivoACeroSuspendeDeInmediato() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending p = issue(s);
    assertThat(verify(p, sign(s, p, 0))).isEqualTo(401);
    assertThat(credential(s)).isEqualTo("5:1:true");
  }

  /**
   * Escenario 5: el contador lo sube una aserción verificada ANTES de emitir A; A llega con un
   * contador menor. Aunque sea justo después, no hay concurrencia demostrable: anomalía fuerte.
   */
  @Test
  void sinConcurrenciaDemostrableAunqueSeaJustoDespues() throws Exception {
    Setup s = setup(0);
    Pending b = issue(s);
    assertThat(verify(b, sign(s, b, 7))).isEqualTo(200);
    Pending a = issue(s);
    assertThat(verify(a, sign(s, a, 6))).isEqualTo(401);
    assertThat(classifications(s)).containsExactly("accepted:increasing", "rejected:strong_anomaly");
    assertThat(credential(s)).isEqualTo("7:1:true");
  }

  /**
   * Escenario 6: una aserción rechazada no sirve como evidencia. El contador sube sin ninguna
   * aserción aceptada que lo explique (solo una rechazada con contador mayor): anomalía fuerte.
   */
  @Test
  void unaAsercionRechazadaNoEsEvidencia() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending a = issue(s);
    UUID credential = UUID.fromString(query("cms_api", "SELECT id FROM passkey_credential WHERE user_id = ?", s.user()).get(0));
    try (var c = connectAs("cms_api")) {
      try (var ps = c.prepareStatement("INSERT INTO webauthn_assertion (credential_id, user_id, ceremony, challenge_id,"
          + " challenge_issued_at, counter_at_issue, verified_at, received_counter, stored_before, stored_after, outcome,"
          + " classification) VALUES (?, ?, 'authenticate', ?, clock_timestamp(), 5, clock_timestamp(), 8, 5, 5,"
          + " 'rejected', 'weak_anomaly')")) {
        ps.setObject(1, credential);
        ps.setObject(2, s.user());
        ps.setObject(3, UUID.randomUUID());
        ps.executeUpdate();
      }
      try (var ps = c.prepareStatement("UPDATE passkey_credential SET sign_count = 8 WHERE id = ?")) {
        ps.setObject(1, credential);
        ps.executeUpdate();
      }
    }
    assertThat(verify(a, sign(s, a, 6))).isEqualTo(401);
    assertThat(classifications(s)).last().isEqualTo("rejected:strong_anomaly");
  }

  /** Escenario 7: tras el consumo, el challenge no existe y el registro conserva su procedencia. */
  @Test
  void laProcedenciaSobreviveAlChallenge() throws Exception {
    Setup s = setup(0);
    Pending p = issue(s);
    assertThat(verify(p, sign(s, p, 4))).isEqualTo(200);
    assertThat(query("cms_api", "SELECT count(*) FROM webauthn_challenge WHERE user_id = ?", s.user())).containsExactly("0");
    assertThat(query("cms_api", "SELECT (challenge_id IS NOT NULL) || ':' || (challenge_issued_at <= verified_at)"
        + " || ':' || counter_at_issue || ':' || received_counter || ':' || stored_before || ':' || stored_after"
        + " FROM webauthn_assertion WHERE user_id = ?", s.user())).containsExactly("true:true:0:4:0:4");
  }

  /** Verificaciones simultáneas de la misma credencial: serializadas, ninguna anomalía contada. */
  @Test
  void verificacionesSimultaneasSeSerializan() throws Exception {
    Setup s = setup(0);
    Pending base = issue(s);
    assertThat(verify(base, sign(s, base, 5))).isEqualTo(200);
    Pending a = issue(s);
    Pending b = issue(s);
    String bodyA = sign(s, a, 6);
    String bodyB = sign(s, b, 7);
    CountDownLatch start = new CountDownLatch(1);
    var fa = CompletableFuture.supplyAsync(() -> race(start, a, bodyA));
    var fb = CompletableFuture.supplyAsync(() -> race(start, b, bodyB));
    start.countDown();
    List<Integer> statuses = List.of(fa.join(), fb.join());
    assertThat(statuses).contains(200).allMatch(st -> st == 200 || st == 401);
    assertThat(credential(s)).as("contador al máximo aceptado y ninguna anomalía").isEqualTo("7:0:false");
    assertThat(classifications(s)).noneMatch(c -> c.contains("anomaly"));
  }

  private int race(CountDownLatch start, Pending p, String body) {
    try {
      start.await();
      return verify(p, body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
