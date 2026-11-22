package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.schema.PostgresSchemaTest;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * La sesión del bloqueo (fase 6.3.4): un solo uso a la vez de su conexión JDBC (heartbeat y
 * liberación), propagación de una pérdida al hilo dueño, el proceso congelado y el plazo de cada
 * operación ajustado al tiempo restante.
 */
class PublicationLockTests extends PostgresSchemaTest {

  private static PublicationProperties props(Duration wait, Duration heartbeat, Duration idle) {
    return new PublicationProperties(Path.of("x"), Path.of("y"), URI.create("http://127.0.0.1:1"), wait,
        Duration.ofMillis(200), heartbeat, idle, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(30), 20, 50);
  }

  /** Envuelve la conexión: cuenta los usos simultáneos y registra el orden de las consultas. */
  private static final class Instrumented {
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    final List<String> log = new CopyOnWriteArrayList<>();
    final List<long[]> networkTimeouts = new CopyOnWriteArrayList<>(); // [pedido ms, restante ms]
    volatile CountDownLatch blockHeartbeatQuery;
    volatile long deadline;
    volatile int slowTryMillis;

    PublicationLock.ConnectionFactory factory() {
      return timeout -> wrap(DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_LOGIN, TestDatabase.password(APP_LOGIN)));
    }

    private Connection wrap(Connection real) {
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
          (proxy, method, args) -> {
            if (method.getName().equals("setNetworkTimeout") && deadline > 0) {
              networkTimeouts.add(new long[] {(Integer) args[1], TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())});
            }
            Object result = enter(() -> method.invoke(real, args));
            if (result instanceof Statement statement) {
              return wrapStatement(statement);
            }
            return result;
          });
    }

    private Statement wrapStatement(Statement real) {
      return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
          (proxy, method, args) -> enter(() -> {
            if (args != null && args.length == 1 && args[0] instanceof String sql) {
              log.add(sql.contains("pg_locks") ? "latido" : sql.contains("unlock") ? "desbloquear" : "otra");
              if (sql.contains("pg_locks") && blockHeartbeatQuery != null) {
                blockHeartbeatQuery.await(10, TimeUnit.SECONDS);
              }
              if (sql.contains("pg_try_advisory_lock") && slowTryMillis > 0) {
                Thread.sleep(slowTryMillis);
              }
            }
            return method.invoke(real, args);
          }));
    }

    private Object enter(java.util.concurrent.Callable<Object> call) throws Throwable {
      int now = concurrent.incrementAndGet();
      maxConcurrent.accumulateAndGet(now, Math::max);
      try {
        return call.call();
      } catch (InvocationTargetException e) {
        throw e.getCause();
      } finally {
        concurrent.decrementAndGet();
      }
    }
  }

  private static PublicationLock acquire(Instrumented conn, PublicationProperties p, PublicationLock.Hooks hooks) {
    long deadline = System.nanoTime() + p.lockWait().toNanos();
    conn.deadline = deadline;
    return PublicationLock.acquire(conn.factory(), p, deadline, System::nanoTime, hooks);
  }

  /** Espera a que el bloqueo se marque como perdido; la interrupción del dueño es la señal esperada. */
  private static boolean waitLost(PublicationLock lock, long millis) {
    boolean interrupted = false;
    long deadline = System.currentTimeMillis() + millis;
    while (!lock.isLost() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    return interrupted || Thread.interrupted();
  }

  private static boolean freeForOthers() throws Exception {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_LOGIN, TestDatabase.password(APP_LOGIN));
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT pg_try_advisory_lock(" + PublicationLock.KEY_CLASS + ", "
            + PublicationLock.KEY_OBJECT + ")")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  @Test
  void nuncaDosUsosALaVezDeLaConexionDelBloqueo() throws Exception {
    Instrumented conn = new Instrumented();
    PublicationLock lock = acquire(conn, props(Duration.ofSeconds(5), Duration.ofMillis(20), Duration.ofSeconds(1)),
        PublicationLock.Hooks.NONE);
    Thread.sleep(2_000); // ~100 latidos
    // Liberar desde otro hilo mientras el heartbeat sigue latiendo.
    CompletableFuture.runAsync(lock::close).get(10, TimeUnit.SECONDS);
    long beats = conn.log.stream().filter("latido"::equals).count();
    assertThat(beats).isGreaterThan(50);
    assertThat(conn.maxConcurrent.get()).as("usos simultáneos de la conexión").isEqualTo(1);
    assertThat(conn.log.getLast()).isEqualTo("desbloquear");
    assertThat(freeForOthers()).isTrue();
  }

  @Test
  void liberarEsperaAlLatidoEnCurso() throws Exception {
    Instrumented conn = new Instrumented();
    PublicationLock lock = acquire(conn, props(Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(1)),
        PublicationLock.Hooks.NONE);
    CountDownLatch held = new CountDownLatch(1);
    conn.blockHeartbeatQuery = held;
    Thread.sleep(300); // un latido entra y se queda retenido dentro (con el mutex)
    CompletableFuture<Void> closing = CompletableFuture.runAsync(lock::close);
    Thread.sleep(300);
    assertThat(closing).as("liberar espera al latido en curso").isNotDone();
    held.countDown();
    closing.get(15, TimeUnit.SECONDS);
    assertThat(conn.maxConcurrent.get()).isEqualTo(1);
    // El último latido terminó antes del desbloqueo.
    assertThat(conn.log.lastIndexOf("latido")).isLessThan(conn.log.lastIndexOf("desbloquear"));
  }

  @Test
  void unaPerdidaSePropagaAlDuenoYCierraLaConexion() throws Exception {
    Instrumented conn = new Instrumented();
    PublicationLock lock = acquire(conn, props(Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(1)),
        PublicationLock.Hooks.NONE);
    tx("SELECT pg_terminate_backend(" + lock.pid() + ")");
    boolean interrupted = waitLost(lock, 5_000);
    assertThat(lock.isLost()).isTrue();
    assertThat(interrupted).as("el hilo dueño recibe la interrupción").isTrue();
    assertThatThrownBy(lock::ensureHeld).isInstanceOf(PublicationFailure.class)
        .extracting("kind").isEqualTo(PublicationFailure.Kind.LOCK_LOST);
    lock.close();
    assertThat(freeForOthers()).isTrue();
  }

  @Test
  void unProcesoCongeladoPierdeLaSesionYLoDetectaAlDespertar() throws Exception {
    Instrumented conn = new Instrumented();
    AtomicInteger beats = new AtomicInteger();
    // Heartbeat de 300 ms e inactividad de 1 s; el segundo latido se congela 2,5 s.
    PublicationLock lock = acquire(conn, props(Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(1)),
        () -> {
          if (beats.incrementAndGet() == 2) {
            Thread.sleep(2_500);
          }
        });
    Thread.sleep(1_900);
    // Mientras está congelado, PostgreSQL ya cerró la sesión inactiva: el bloqueo quedó libre.
    assertThat(freeForOthers()).isTrue();
    assertThat(waitLost(lock, 5_000)).isTrue();
    assertThat(lock.isLost()).as("al despertar, el latido detecta la pérdida").isTrue();
    lock.close();
  }

  @Test
  void cadaOperacionRespetaElTiempoRestante() throws Exception {
    // Otra sesión tiene el bloqueo: se agota el plazo, con intentos lentos al final.
    try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_LOGIN, TestDatabase.password(APP_LOGIN));
        Statement s = holder.createStatement()) {
      s.execute("SELECT pg_advisory_lock(" + PublicationLock.KEY_CLASS + ", " + PublicationLock.KEY_OBJECT + ")");
      Instrumented conn = new Instrumented();
      conn.slowTryMillis = 150;
      long start = System.nanoTime();
      assertThatThrownBy(() -> acquire(conn, props(Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(60)),
          PublicationLock.Hooks.NONE)).extracting("kind").isEqualTo(PublicationFailure.Kind.BUSY);
      Duration waited = Duration.ofNanos(System.nanoTime() - start);
      assertThat(waited).isBetween(Duration.ofMillis(2_800), Duration.ofMillis(3_500));
      assertThat(conn.networkTimeouts).isNotEmpty();
      for (long[] timeout : conn.networkTimeouts) {
        assertThat(timeout[0]).as("límite pedido frente al tiempo restante").isLessThanOrEqualTo(timeout[1] + 5);
      }
    }
  }
}
