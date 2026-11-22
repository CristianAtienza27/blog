package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.publication.PublicationFailure.Kind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Bloqueo asesor de la publicación (fase 6.3.4): exclusión entre publicadores y espera acotada. La
 * SEGURIDAD no depende de él sino de la época ({@link PublicationControl}); este bloqueo evita que
 * dos publicadores trabajen a la vez y detecta pronto una pérdida.
 *
 * <ul>
 *   <li><b>Conexión propia, fuera del pool.</b> Cerrarla termina la sesión de PostgreSQL y libera el
 *       bloqueo pase lo que pase (una conexión del pool volvería al pool con el bloqueo tomado).
 *   <li><b>Plazo global.</b> Un único plazo monotónico cubre la conexión y todos los intentos: la
 *       conexión solo se intenta con al menos 1 s restante y con connectTimeout/loginTimeout =
 *       floor(restante); cada intento ({@code pg_try_advisory_lock}, que no bloquea) lleva
 *       {@code setNetworkTimeout} y {@code statement_timeout} iguales al tiempo restante.
 *   <li><b>Heartbeat.</b> Cada {@code lockHeartbeat} comprueba en {@code pg_locks} que ESTA sesión
 *       sigue teniendo el bloqueo; así la sesión nunca está inactiva más de un intervalo y
 *       {@code idle_session_timeout} (≥ 3 intervalos) solo actúa si el proceso se congela. Si falla,
 *       la sesión queda perdida: se interrumpe el hilo dueño y se cierra la conexión.
 *   <li><b>Una conexión, un uso a la vez.</b> Adquirir (antes del heartbeat), el heartbeat y liberar
 *       (después de pararlo) usan la conexión bajo un {@link ReentrantLock}. El hilo de publicación
 *       nunca la toca: {@link #ensureHeld()} solo lee un estado volátil.
 * </ul>
 */
final class PublicationLock implements AutoCloseable {

  /** Claves del bloqueo asesor: «CMS» y la fase de publicación. */
  static final int KEY_CLASS = 0x434D53;
  static final int KEY_OBJECT = 63;

  /** Abre una conexión nueva (fuera del pool) con ese límite, en segundos, para conectar. */
  @FunctionalInterface
  interface ConnectionFactory {
    Connection open(int timeoutSeconds) throws SQLException;
  }

  /** Puntos de prueba (los tests pausan o fallan el heartbeat). */
  @FunctionalInterface
  interface Hooks {
    void beforeHeartbeat() throws InterruptedException;

    Hooks NONE = () -> {};
  }

  /** Otro publicador tiene el control y no lo soltó dentro del plazo. */
  static PublicationFailure busy() {
    return new PublicationFailure(Kind.BUSY, "hay otra publicación en curso");
  }

  private static final ExecutorService NETWORK = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "publicacion-red");
    thread.setDaemon(true);
    return thread;
  });
  private static final int HEARTBEAT_NETWORK_MS = 5_000;

  private final Connection connection;
  private final int pid;
  private final ReentrantLock mutex = new ReentrantLock();
  private final ScheduledExecutorService heartbeat;
  private final AtomicReference<Throwable> lost = new AtomicReference<>();
  private final Thread owner;
  private final Hooks hooks;
  private volatile boolean closed;

  private PublicationLock(Connection connection, int pid, Duration every, Hooks hooks) {
    this.connection = connection;
    this.pid = pid;
    this.owner = Thread.currentThread();
    this.hooks = hooks;
    this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "publicacion-latido");
      thread.setDaemon(true);
      return thread;
    });
    long ms = every.toMillis();
    heartbeat.scheduleWithFixedDelay(this::beat, ms, ms, TimeUnit.MILLISECONDS);
  }

  /** Toma el bloqueo antes de {@code deadline} (nanos de {@code clock}) o lanza {@link #busy()}. */
  static PublicationLock acquire(ConnectionFactory factory, PublicationProperties properties, long deadline,
      LongSupplier clock, Hooks hooks) {
    long remaining = deadline - clock.getAsLong();
    if (remaining < TimeUnit.SECONDS.toNanos(1)) {
      throw busy();
    }
    Connection connection;
    try {
      connection = factory.open((int) TimeUnit.NANOSECONDS.toSeconds(remaining));
    } catch (SQLException e) {
      throw new PublicationFailure(Kind.DATABASE, "no se puede conectar con PostgreSQL", e);
    }
    try {
      connection.setAutoCommit(true);
      while (true) {
        if (tryOnce(connection, deadline, clock)) {
          break;
        }
        long wait = Math.min(properties.lockPoll().toNanos(), deadline - clock.getAsLong());
        if (wait <= 0) {
          throw busy();
        }
        TimeUnit.NANOSECONDS.sleep(wait);
      }
      int pid = configure(connection, properties);
      return new PublicationLock(connection, pid, properties.lockHeartbeat(), hooks);
    } catch (PublicationFailure e) {
      abortQuietly(connection);
      throw e;
    } catch (InterruptedException e) {
      abortQuietly(connection);
      Thread.currentThread().interrupt();
      throw new PublicationFailure(Kind.LOCK_LOST, "interrumpido mientras esperaba el bloqueo", e);
    } catch (SQLException e) {
      abortQuietly(connection);
      // Un intento que agota el plazo en la red o en el servidor: ocupado (cerrar la conexión
      // libera el bloqueo aunque el servidor lo hubiera llegado a conceder).
      if (deadline - clock.getAsLong() <= 0) {
        throw busy();
      }
      throw new PublicationFailure(Kind.DATABASE, "no se puede tomar el bloqueo de publicación", e);
    }
  }

  /** Un intento, con sus dos viajes de ida y vuelta limitados por el tiempo restante. */
  private static boolean tryOnce(Connection connection, long deadline, LongSupplier clock) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      int ms = remainingMillis(deadline, clock);
      connection.setNetworkTimeout(NETWORK, ms);
      statement.execute("SET statement_timeout = " + ms);
      ms = remainingMillis(deadline, clock);
      connection.setNetworkTimeout(NETWORK, ms);
      try (ResultSet rs = statement.executeQuery(
          "SELECT pg_try_advisory_lock(" + KEY_CLASS + ", " + KEY_OBJECT + ")")) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static int remainingMillis(long deadline, LongSupplier clock) {
    long remaining = deadline - clock.getAsLong();
    if (remaining <= 0) {
      throw busy();
    }
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining)));
  }

  /** Límites de la sesión del bloqueo una vez tomado; devuelve su PID. */
  private static int configure(Connection connection, PublicationProperties properties) throws SQLException {
    connection.setNetworkTimeout(NETWORK, HEARTBEAT_NETWORK_MS);
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET statement_timeout = " + HEARTBEAT_NETWORK_MS);
      statement.execute("SET idle_session_timeout = " + properties.lockIdleTimeout().toMillis());
      // Un corte de red sin cierre: PostgreSQL lo detecta en ~60 s y termina la sesión.
      statement.execute("SET tcp_keepalives_idle = 30");
      statement.execute("SET tcp_keepalives_interval = 10");
      statement.execute("SET tcp_keepalives_count = 3");
      try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** PID de la sesión que tiene el bloqueo (diagnóstico y pruebas). */
  int pid() {
    return pid;
  }

  /** Si la sesión se perdió, lanza: la publicación no puede seguir como si estuviera protegida. */
  void ensureHeld() {
    Throwable cause = lost.get();
    if (cause != null) {
      throw new PublicationFailure(Kind.LOCK_LOST, "se perdió el bloqueo de publicación", cause);
    }
    if (closed) {
      throw new PublicationFailure(Kind.LOCK_LOST, "el bloqueo de publicación ya se liberó");
    }
  }

  boolean isLost() {
    return lost.get() != null;
  }

  private void beat() {
    try {
      hooks.beforeHeartbeat();
    } catch (InterruptedException e) {
      return; // liberando
    }
    mutex.lock();
    try {
      if (closed || lost.get() != null) {
        return;
      }
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
              + " AND pid = pg_backend_pid() AND classid = " + KEY_CLASS + " AND objid = " + KEY_OBJECT
              + " AND objsubid = 2 AND granted")) {
        rs.next();
        if (rs.getInt(1) != 1) {
          markLost(new IllegalStateException("la sesión ya no tiene el bloqueo"));
        }
      }
    } catch (SQLException | RuntimeException e) {
      markLost(e);
    } finally {
      mutex.unlock();
    }
  }

  /** Pérdida detectada fuera del heartbeat (una transacción protegida no ve el bloqueo). */
  void lostDetected(Throwable cause) {
    mutex.lock();
    try {
      markLost(cause);
    } finally {
      mutex.unlock();
    }
  }

  /** La sesión se perdió: se avisa al dueño (interrupción) y se cierra la conexión. */
  private void markLost(Throwable cause) {
    if (lost.compareAndSet(null, cause)) {
      owner.interrupt();
      abortQuietly(connection);
    }
  }

  /**
   * Libera: para el heartbeat (esperando a que termine el que esté en curso), desbloquea y cierra
   * la conexión físicamente. Nunca lanza: un fallo aquí no tapa el error de la publicación.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    // La interrupción solo servía para avisar al dueño: no se arrastra a lo que haga después.
    if (lost.get() != null && Thread.currentThread() == owner) {
      Thread.interrupted();
    }
    heartbeat.shutdown();
    try {
      if (!heartbeat.awaitTermination(HEARTBEAT_NETWORK_MS, TimeUnit.MILLISECONDS)) {
        heartbeat.shutdownNow();
      }
    } catch (InterruptedException e) {
      heartbeat.shutdownNow();
      Thread.currentThread().interrupt();
    }
    mutex.lock();
    try {
      if (lost.get() == null) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("SELECT pg_advisory_unlock(" + KEY_CLASS + ", " + KEY_OBJECT + ")");
        } catch (SQLException ignored) {
          // Cerrar la conexión termina la sesión y libera el bloqueo igualmente.
        }
      }
    } finally {
      try {
        connection.close();
      } catch (SQLException e) {
        abortQuietly(connection);
      }
      mutex.unlock();
    }
  }

  private static void abortQuietly(Connection connection) {
    try {
      connection.abort(NETWORK);
    } catch (SQLException | RuntimeException ignored) {
      // Nada más que hacer: la sesión termina al cerrarse el socket.
    }
  }
}
