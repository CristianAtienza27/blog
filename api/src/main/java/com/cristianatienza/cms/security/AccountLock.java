package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.ApiErrors.AuthFailure;
import com.cristianatienza.cms.security.ApiErrors.LoginFailedException;
import com.cristianatienza.cms.security.AuthStates.SessionUser;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * Bloqueo por usuario frente a la recuperación de emergencia (H7, fase 6.2.6).
 *
 * <p><b>Qué protege.</b> Las operaciones que conceden un estado o crean material de autenticación
 * (inicio de sesión, registro de passkeys, acceso a F, step-up, canje y generación de códigos) se
 * ejecutan enteras con la fila del usuario bloqueada {@code FOR KEY SHARE} en una transacción propia
 * (T), que se toma ANTES de la operación y se suelta DESPUÉS de completarla. Con el bloqueo tomado
 * se compara el sello de seguridad de la sesión ({@code app_user.password_changed_at}, leído al
 * iniciar sesión) con el actual: si la recuperación lo ha cambiado, la operación no se ejecuta.
 *
 * <p><b>Con la recuperación.</b> La herramienta bloquea la fila {@code FOR UPDATE}, que choca con
 * {@code FOR KEY SHARE}: o la recuperación espera a que terminen las operaciones en curso (y luego
 * revoca todo lo que hayan creado), o las operaciones esperan a la recuperación (y luego ven el
 * sello nuevo y se rechazan). Nunca se intercalan.
 *
 * <p><b>Con las transacciones independientes.</b> T solo sujeta el bloqueo: no escribe nada, y lo
 * demás sigue exactamente como en la 6.2.2–6.2.5, cada cosa en su transacción y en otra conexión
 * (challenge, contador, auditoría, dispositivo, fallos por sesión, Spring Session). Todas se
 * confirman mientras T sigue abierta. {@code FOR KEY SHARE} es compatible con lo que esas
 * transacciones hacen sobre la fila del usuario (las claves foráneas toman {@code FOR KEY SHARE};
 * {@code last_login_at} y {@code complete_passkey_enrollment} toman {@code FOR NO KEY UPDATE}), así
 * que no se esperan a sí mismas. La única escritura que queda fuera es la de Spring Session, que
 * inserta la sesión nueva al confirmar la respuesta, ya sin el bloqueo: si eso ocurre después de una
 * recuperación, la sesión lleva el sello anterior y {@link SecurityStampFilter} la cierra en su
 * primera petición.
 *
 * <p><b>Conexiones.</b> Cada operación ocupa la conexión de T más, como mucho, otra a la vez. Un
 * semáforo limita las operaciones simultáneas para que nunca agoten el pool (se comprueba al
 * arrancar). El cuerpo de la petición se lee antes de bloquear: un cliente lento no retiene nada.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccountLock {

  /** Fallo de bloqueo de PostgreSQL por lock_timeout. */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private final DataSource dataSource;
  private final AuthSessions sessions;
  private final SecurityEvents events;
  private final AccountLockObserver observer;
  private final Semaphore permits;
  private final Duration maxWait;
  private final Duration lockTimeout;

  public AccountLock(DataSource dataSource, AuthSessions sessions, SecurityEvents events,
      ObjectProvider<AccountLockObserver> observer,
      @Value("${cms.security.account-lock.max-concurrent:2}") int maxConcurrent,
      @Value("${cms.security.account-lock.max-wait:5s}") Duration maxWait,
      @Value("${cms.security.account-lock.lock-timeout:10s}") Duration lockTimeout) {
    this.dataSource = dataSource;
    this.sessions = sessions;
    this.events = events;
    this.observer = observer.getIfAvailable(() -> AccountLockObserver.NONE);
    if (maxConcurrent < 1) {
      throw new IllegalStateException("cms.security.account-lock.max-concurrent debe ser 1 o más");
    }
    checkPool(dataSource, maxConcurrent);
    this.permits = new Semaphore(maxConcurrent, true);
    this.maxWait = maxWait;
    this.lockTimeout = lockTimeout;
  }

  /**
   * Cada operación usa dos conexiones a la vez como mucho (T y la de la transacción en curso): con
   * {@code 2 × max-concurrent < tamaño del pool} queda siempre al menos una para lo demás y las
   * operaciones bloqueadas nunca se quedan esperando una conexión que tienen otras.
   */
  private static void checkPool(DataSource dataSource, int maxConcurrent) {
    try {
      if (dataSource.isWrapperFor(HikariDataSource.class)) {
        int pool = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        if (2 * maxConcurrent >= pool) {
          throw new IllegalStateException("cms.security.account-lock.max-concurrent (" + maxConcurrent
              + ") debe cumplir 2 × max-concurrent < maximum-pool-size (" + pool + ")");
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("no se pudo comprobar el pool de conexiones", e);
    }
  }

  /**
   * Operación de una sesión existente (P1, P2, R o F): el sello de la sesión debe ser el actual.
   * Si no lo es, la sesión se cierra y se responde 401 sin ejecutar nada.
   */
  public <T> T forSession(HttpServletRequest request, HttpServletResponse response, SessionUser user, String operation,
      Supplier<T> work) {
    HttpSession session = request.getSession(false);
    Object stamp = session == null ? null : session.getAttribute(AuthStates.SECURITY_STAMP);
    try {
      return locked(user.id(), stamp instanceof Instant expected ? expected : null, operation, work);
    } catch (StaleStamp stale) {
      sessions.close(request, response);
      events.recordBestEffort("session.stale_stamp", user.id(), Map.of("operation", operation));
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "unauthenticated", "no autenticado");
    }
  }

  /**
   * Inicio de sesión: el sello debe ser el leído junto con el hash que validó la contraseña. Si la
   * cuenta ha cambiado entretanto (recuperación), mismo 401 que unas credenciales no válidas.
   */
  public <T> T forLogin(UUID userId, Instant stampReadWithPassword, Supplier<T> work) {
    try {
      return locked(userId, stampReadWithPassword, "login", work);
    } catch (StaleStamp stale) {
      events.recordBestEffort("login.stale_account", userId);
      throw new LoginFailedException();
    }
  }

  private <T> T locked(UUID userId, Instant expected, String operation, Supplier<T> work) {
    observer.beforeLock(userId, operation);
    acquirePermit();
    try {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          Instant current = lockAndReadStamp(connection, userId);
          if (expected == null || !expected.equals(current)) {
            throw new StaleStamp();
          }
          T result = work.get();
          observer.beforeRelease(userId, operation);
          return result;
        } finally {
          // T no ha escrito nada: deshacerla solo suelta el bloqueo.
          connection.rollback();
        }
      } catch (SQLException e) {
        if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
          throw busy();
        }
        throw new CannotGetJdbcConnectionException("bloqueo de la cuenta", e);
      }
    } finally {
      permits.release();
      observer.afterRelease(userId, operation);
    }
  }

  /** {@code FOR KEY SHARE} exige UPDATE en alguna columna: cms_api lo tiene en last_login_at (V7). */
  private Instant lockAndReadStamp(Connection connection, UUID userId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'");
    }
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT password_changed_at FROM app_user WHERE id = ? FOR KEY SHARE")) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, OffsetDateTime.class).toInstant() : null;
      }
    }
  }

  private void acquirePermit() {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy();
    }
    if (!acquired) {
      throw busy();
    }
  }

  private static AuthFailure busy() {
    return new AuthFailure(HttpStatus.TOO_MANY_REQUESTS, "saturated", "demasiadas solicitudes; inténtalo de nuevo en un momento");
  }

  /** El sello no coincide (o el usuario ya no existe): la operación no se ejecuta. */
  private static final class StaleStamp extends RuntimeException {
    StaleStamp() {
      super("sello de seguridad obsoleto", null, false, false);
    }
  }
}
