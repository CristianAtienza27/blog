package com.cristianatienza.cms.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Contraseñas con Argon2id (fase 6.2.2): m = 19 MiB, t = 2, p = 1 (recomendación de OWASP),
 * sal de 16 bytes y hash de 32. BouncyCastle reserva esa memoria en el heap de Java, así que un
 * semáforo limita los cálculos simultáneos (por defecto, uno por CPU disponible): con hilos
 * virtuales no hay otro límite. Si no hay permiso en {@code max-wait}, se rechaza con
 * {@link HashingSaturatedException} (429), en lugar de acumular memoria.
 *
 * Esto no sustituye a la limitación de intentos (fase 6.2.4): solo acota el coste por petición.
 */
@Component
public class PasswordHashing {

  public static final int SALT_BYTES = 16;
  public static final int HASH_BYTES = 32;
  public static final int PARALLELISM = 1;
  public static final int MEMORY_KIB = 19 * 1024;
  public static final int ITERATIONS = 2;

  private final Argon2PasswordEncoder encoder =
      new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  private final Semaphore permits;
  private final int maxConcurrent;
  private final Duration maxWait;
  /** Hash de una contraseña aleatoria: se verifica contra él cuando el usuario no existe. */
  private final String dummyHash;

  public PasswordHashing(
      @Value("${cms.security.argon2.max-concurrent:0}") int maxConcurrent,
      @Value("${cms.security.argon2.max-wait:1s}") Duration maxWait) {
    this.maxConcurrent = maxConcurrent > 0 ? maxConcurrent : Runtime.getRuntime().availableProcessors();
    this.permits = new Semaphore(this.maxConcurrent, true);
    this.maxWait = maxWait;
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    this.dummyHash = encoder.encode(Base64.getEncoder().encodeToString(random));
  }

  /** Hash de una contraseña nueva (herramienta de seguridad, fase 6.2.6). */
  public String encode(CharSequence password) {
    return withPermit(() -> encoder.encode(password));
  }

  /**
   * Verifica una contraseña. Con {@code encodedOrNull} nulo (usuario inexistente) calcula igualmente
   * un hash completo contra el ficticio, para que el tiempo no revele si la cuenta existe.
   */
  public boolean matches(CharSequence password, String encodedOrNull) {
    boolean known = encodedOrNull != null;
    boolean ok = withPermit(() -> encoder.matches(password, known ? encodedOrNull : dummyHash));
    return known && ok;
  }

  public int maxConcurrent() {
    return maxConcurrent;
  }

  public Duration maxWait() {
    return maxWait;
  }

  private <T> T withPermit(java.util.function.Supplier<T> work) {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HashingSaturatedException();
    }
    if (!acquired) {
      throw new HashingSaturatedException();
    }
    try {
      return work.get();
    } finally {
      permits.release();
    }
  }

  /** Demasiados cálculos de Argon2id simultáneos: se responde 429 (comportamiento esperado). */
  public static class HashingSaturatedException extends RuntimeException {
    HashingSaturatedException() {
      super("demasiadas verificaciones de contraseña simultáneas", null, false, false);
    }
  }
}
