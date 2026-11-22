package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.security.PasswordHashing.HashingSaturatedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Argon2id y su semáforo, sin Spring ni base de datos. */
class PasswordHashingTests {

  private final PasswordHashing hashing = new PasswordHashing(0, Duration.ofSeconds(1));

  @Test
  void argon2idConLosParametrosAprobados() {
    String hash = hashing.encode("una contraseña");
    // Formato PHC: algoritmo, versión 19 (0x13), memoria en KiB, iteraciones y paralelismo.
    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    String[] parts = hash.split("\\$");
    assertThat(java.util.Base64.getDecoder().decode(pad(parts[4]))).as("sal").hasSize(16);
    assertThat(java.util.Base64.getDecoder().decode(pad(parts[5]))).as("hash").hasSize(32);
    // Sal aleatoria: dos hashes de la misma contraseña son distintos.
    assertThat(hashing.encode("una contraseña")).isNotEqualTo(hash);
  }

  private static String pad(String base64) {
    return base64 + "=".repeat((4 - base64.length() % 4) % 4);
  }

  @Test
  void verificaLaContraseña() {
    String hash = hashing.encode("correcta");
    assertThat(hashing.matches("correcta", hash)).isTrue();
    assertThat(hashing.matches("incorrecta", hash)).isFalse();
  }

  @Test
  void conUsuarioInexistenteCalculaIgualmenteUnHashCompleto() {
    String hash = hashing.encode("correcta");
    // Media de varias medidas: Argon2id domina el tiempo (decenas de milisegundos).
    long known = time(() -> hashing.matches("x", hash));
    long unknown = time(() -> assertThat(hashing.matches("x", null)).isFalse());
    assertThat((double) unknown / known).as("tiempo sin usuario / con usuario").isBetween(0.5, 2.0);
  }

  private static long time(Runnable work) {
    work.run(); // calentamiento
    long start = System.nanoTime();
    for (int i = 0; i < 5; i++) {
      work.run();
    }
    return System.nanoTime() - start;
  }

  @Test
  void porDefectoUnaVerificacionSimultaneaPorCpu() {
    assertThat(hashing.maxConcurrent()).isEqualTo(Runtime.getRuntime().availableProcessors());
    assertThat(new PasswordHashing(3, Duration.ofMillis(10)).maxConcurrent()).isEqualTo(3);
  }

  @Test
  void sinHuecoEnElSemaforoSeRechazaEnLugarDeAcumular() throws Exception {
    PasswordHashing one = new PasswordHashing(1, Duration.ofMillis(20));
    String hash = one.encode("correcta");
    CountDownLatch started = new CountDownLatch(1);
    var done = new java.util.concurrent.atomic.AtomicBoolean();
    var executor = Executors.newSingleThreadExecutor();
    try {
      // Otro hilo compite sin parar por el único permiso (un hash dura más que la espera de 20 ms).
      // También él puede ser rechazado: lo ignora y sigue compitiendo hasta que el principal acaba
      // (corregido en la 6.2.3: antes se detenía con su primer rechazo y el test dependía del orden).
      var busy = executor.submit(() -> {
        started.countDown();
        while (!done.get()) {
          try {
            one.matches("correcta", hash);
          } catch (HashingSaturatedException expected) {
            // sigue compitiendo
          }
        }
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(5);
      try {
        assertThatThrownBy(() -> {
          for (int i = 0; i < 50; i++) {
            one.matches("correcta", hash);
          }
        }).isInstanceOf(HashingSaturatedException.class);
      } finally {
        done.set(true);
      }
      busy.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    // Liberado el permiso, vuelve a funcionar.
    assertThat(one.matches("correcta", hash)).isTrue();
  }
}
