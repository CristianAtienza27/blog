package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Limitación de intentos con los valores reales: pareja cuenta + IP, IP, bloqueo creciente, mismo
 * comportamiento para un usuario inexistente, reinicio solo al completar F, distribución entre IP
 * (la cuenta nunca se bloquea) y concurrencia (contadores exactos, sin 500).
 */
class BruteForceTests extends LimitsTestSupport {

  @Autowired SecretKeys keys;

  private static final String WRONG = "contraseña-equivocada";

  @Test
  void cincoFallosBloqueanSoloLaParejaConEsperaCreciente() throws Exception {
    String email = createUser(true, true);
    String ip = freshIp();
    for (int i = 0; i < 5; i++) {
      assertThat(clientFrom(ip).login(email, WRONG).status()).isEqualTo(401);
    }
    var blocked = clientFrom(ip).login(email, PASSWORD);
    assertThat(blocked.status()).as("bloqueado aun con la contraseña correcta").isEqualTo(429);
    assertThat(Long.parseLong(blocked.header("Retry-After"))).isBetween(1L, 15 * 60L + 1);
    assertThat(blocked.body()).contains("\"code\":\"rate_limited\"", "\"detail\":\"demasiados intentos; inténtalo más tarde\"");
    // Otra IP no está afectada: un atacante solo se bloquea a sí mismo.
    assertThat(clientFrom(freshIp()).login(email, PASSWORD).status()).isEqualTo(200);
    clock.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(clientFrom(ip).login(email, PASSWORD).status()).as("pasado el bloqueo").isEqualTo(200);
    // Segundo bloqueo: el doble (30 min).
    for (int i = 0; i < 5; i++) {
      clientFrom(ip).login(email, WRONG);
    }
    clock.advance(Duration.ofMinutes(16));
    assertThat(clientFrom(ip).login(email, PASSWORD).status()).as("a los 16 min sigue").isEqualTo(429);
    clock.advance(Duration.ofMinutes(15));
    assertThat(clientFrom(ip).login(email, PASSWORD).status()).isEqualTo(200);
  }

  @Test
  void unUsuarioInexistenteSeComportaIgual() throws Exception {
    String ip = freshIp();
    String nobody = "nadie-" + System.nanoTime() + "@example.test";
    List<String> bodies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      var r = clientFrom(ip).login(nobody, WRONG);
      assertThat(r.status()).isEqualTo(401);
      // Mismo cuerpo salvo el identificador de correlación, que es de cada petición.
      bodies.add(r.body().replaceAll("\"correlationId\":\"[^\"]+\"", ""));
    }
    assertThat(bodies).hasSize(5).allMatch(b -> b.equals(bodies.get(0)))
        .allMatch(b -> b.contains("\"code\":\"invalid_credentials\"") && b.contains("\"detail\":\"credenciales no válidas\""));
    assertThat(clientFrom(ip).login(nobody, WRONG).status()).isEqualTo(429);
  }

  @Test
  void veinteFallosDesdeUnaIpLaBloqueanParaCualquierCuenta() throws Exception {
    String ip = freshIp();
    for (int i = 0; i < 20; i++) {
      // Cuentas distintas: ninguna pareja llega a su límite.
      assertThat(clientFrom(ip).login("ip-" + i + "-" + System.nanoTime() + "@example.test", WRONG).status())
          .isEqualTo(401);
    }
    String email = createUser(true, true);
    assertThat(clientFrom(ip).login(email, PASSWORD).status()).isEqualTo(429);
    assertThat(clientFrom(freshIp()).login(email, PASSWORD).status()).isEqualTo(200);
  }

  @Test
  void laContraseñaCorrectaNoReiniciaElContadorYCompletarFSi() throws Exception {
    Account account = fullAccountFrom(freshIp());
    String ip = freshIp();
    for (int i = 0; i < 4; i++) {
      clientFrom(ip).login(account.email(), WRONG);
    }
    // Contraseña correcta (P2) sin passkey: el contador sigue (E3).
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).isEqualTo(200);
    clientFrom(ip).login(account.email(), WRONG);
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).isEqualTo(429);

    String other = freshIp();
    for (int i = 0; i < 4; i++) {
      clientFrom(other).login(account.email(), WRONG);
    }
    ApiClient full = clientFrom(other);
    full.login(account.email(), PASSWORD);
    assertThat(authenticate(full, account.authenticator()).status()).isEqualTo(200);
    // Tras F, la pareja vuelve a empezar: 4 fallos más no bloquean.
    for (int i = 0; i < 4; i++) {
      assertThat(clientFrom(other).login(account.email(), WRONG).status()).isEqualTo(401);
    }
    assertThat(clientFrom(other).login(account.email(), PASSWORD).status()).isEqualTo(200);
  }

  @Test
  void losFallosDelSegundoFactorCuentanEnLaPareja() throws Exception {
    Account account = fullAccountFrom(freshIp());
    String ip = freshIp();
    ApiClient p2 = clientFrom(ip);
    p2.login(account.email(), PASSWORD);
    account.authenticator().tamperSignature = true;
    for (int i = 0; i < 4; i++) {
      assertThat(authenticate(p2, account.authenticator()).status()).isEqualTo(401);
    }
    account.authenticator().tamperSignature = false;
    // 4 fallos de passkey + 1 de contraseña = 5: la pareja queda bloqueada.
    clientFrom(ip).login(account.email(), WRONG);
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).isEqualTo(429);
  }

  /** 50 fallos simultáneos de la misma pareja: ningún 500 y el contador de la cuenta es exacto. */
  @RepeatedTest(3)
  void cincuentaFallosSimultaneos() throws Exception {
    String email = createUser(true, true);
    String ip = freshIp();
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
          return clientFrom(ip).login(email, WRONG).status();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }));
    }
    start.countDown();
    List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
    assertThat(statuses).as("sin 500").allMatch(s -> s == 401 || s == 429);
    assertThat(statuses).contains(429);
    long failed = statuses.stream().filter(s -> s == 401).count();
    // Cada 401 es un fallo contado en la cuenta, exactamente (incremento atómico).
    assertThat(query("cms_api", "SELECT failures FROM login_attempt WHERE key_type = 'account' AND key_hash = ?",
        keys.attemptKey("account", email))).containsExactly(Long.toString(failed));
    assertThat(clientFrom(ip).login(email, PASSWORD).status()).isEqualTo(429);
  }
}
