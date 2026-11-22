package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.cristianatienza.cms.testsupport.TestDatabase;

/**
 * Fencing con la época y el bloqueo (fase 6.3.4): un publicador que pierde el control no escribe
 * ni activa nada; el bloqueo se mantiene en publicaciones largas, se pierde de verdad al cortar la
 * conexión y nunca hace esperar más de 15 s.
 */
class PublicationFencingTests extends PublicationTestSupport {

  private static final Actor ADMIN = new Actor(null, "api", Map.of());

  /** Heartbeat largo: así la pérdida la detecta el fencing, no el latido. */
  private PublicationService slowHeartbeat(PublicationService.Hooks hooks) {
    return service(properties(web.uri(), Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(180)),
        hooks, PublicationLock.Hooks.NONE);
  }

  private static CompletableFuture<Object> async(java.util.concurrent.Callable<Object> task) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return task.call();
      } catch (Throwable e) {
        return e;
      }
    });
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void elBloqueoSePierdeEntreLaComprobacionYLaEscrituraElAntiguoNoEscribe() throws Exception {
    service().publish(ADMIN);
    changeContent("Antiguo");
    CountDownLatch paused = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    // El antiguo ha superado sus comprobaciones y se detiene justo antes de su transacción de P2.
    PublicationService old = slowHeartbeat(point -> {
      if (point.equals("generacion")) {
        paused.countDown();
        await(resume);
      }
    });
    CompletableFuture<Object> oldRun = async(() -> old.publish(ADMIN));
    await(paused);
    terminate(lockHolderPid()); // pierde el bloqueo (corte real)
    PublicationService.Outcome fresh = service().publish(ADMIN); // otro toma el control y publica
    assertThat(fresh.result()).isEqualTo("activada");
    resume.countDown();
    Object result = oldRun.get(30, TimeUnit.SECONDS);
    assertThat(result).isInstanceOf(PublicationFailure.class);
    assertThat(((PublicationFailure) result).kind()).isIn(PublicationFailure.Kind.FENCED, PublicationFailure.Kind.LOCK_LOST);
    // El antiguo no escribió nada: su v2 la cerró el nuevo al tomar el control; v3 es la del nuevo.
    assertThat(states()).isEqualTo("v1=retirada v2=fallida v3=activa");
    assertThat(Files.exists(snapshots.resolve("v2"))).isFalse();
    assertThat(web.activations()).noneMatch(a -> a.endsWith(" v2"));
    assertThat(pointerVersion()).isEqualTo("v3");
  }

  @Test
  void elAntiguoTieneLaFilaDeLaEpocaElNuevoEsperaYDespuesElAntiguoAborta() throws Exception {
    service().publish(ADMIN);
    changeContent("Con la fila");
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    // El antiguo está DENTRO de su transacción de P2, con la fila de la época bloqueada.
    PublicationService old = slowHeartbeat(point -> {
      if (point.equals("dentro:p2")) {
        inside.countDown();
        await(resume);
      }
    });
    CompletableFuture<Object> oldRun = async(() -> old.publish(ADMIN));
    await(inside);
    terminate(lockHolderPid());
    CompletableFuture<Object> freshRun = async(() -> service().publish(ADMIN));
    // El nuevo consigue el bloqueo asesor, pero su incremento de la época espera a la fila.
    long deadline = System.currentTimeMillis() + 10_000;
    long waiting = 0;
    while (waiting == 0 && System.currentTimeMillis() < deadline) {
      waiting = one(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE usename = 'cms_api'"
          + " AND wait_event_type = 'Lock' AND query LIKE 'UPDATE content_publication_lease%'");
      Thread.sleep(50);
    }
    assertThat(waiting).as("el nuevo espera a que el antiguo termine su transacción").isEqualTo(1);
    long epochWhileWaiting = one(Long.class, "SELECT epoch FROM content_publication_lease");
    resume.countDown();
    Object oldResult = oldRun.get(30, TimeUnit.SECONDS);
    Object freshResult = freshRun.get(30, TimeUnit.SECONDS);
    // El antiguo confirmó su P2 (lo tenía legítimamente) y abortó en la siguiente transacción.
    assertThat(oldResult).isInstanceOf(PublicationFailure.class);
    assertThat(((PublicationFailure) oldResult).kind()).isEqualTo(PublicationFailure.Kind.FENCED);
    assertThat(freshResult).isInstanceOf(PublicationService.Outcome.class);
    assertThat(one(Long.class, "SELECT epoch FROM content_publication_lease")).isGreaterThan(epochWhileWaiting);
    assertThat(states()).isEqualTo("v1=retirada v2=fallida v3=activa");
    assertThat(web.activations()).noneMatch(a -> a.endsWith(" v2"));
  }

  @Test
  void unaActivacionAntiguaQueLlegaTardeALaWebSeRechaza() throws Exception {
    service().publish(ADMIN);
    long firstEpoch = web.epoch();
    changeContent("Tarde");
    CountDownLatch release = new CountDownLatch(1);
    web.activationHeld = new CountDownLatch(1);
    web.holdActivation = release;
    CompletableFuture<Object> oldRun = async(() -> slowHeartbeat(PublicationService.Hooks.NONE).publish(ADMIN));
    await(web.activationHeld); // la petición del antiguo está en la web, retenida
    long oldEpoch = one(Long.class, "SELECT epoch FROM content_publication_lease");
    // El antiguo pierde PostgreSQL (su transacción) y el bloqueo; el nuevo toma el control.
    int lock = lockHolderPid();
    tx("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'cms_api' AND pid <> " + lock
        + " AND state LIKE 'idle in transaction%'");
    terminate(lock);
    PublicationService.Outcome fresh = service().publish(ADMIN);
    assertThat(fresh.result()).isEqualTo("activada");
    String pointerAfterFresh = Files.readString(state.resolve("activa.json"));
    release.countDown(); // ahora llega la del antiguo, con su época
    Object oldResult = oldRun.get(30, TimeUnit.SECONDS);
    assertThat(oldResult).isInstanceOf(PublicationFailure.class);
    assertThat(web.activations()).contains("activar " + oldEpoch + " v2");
    assertThat(Files.readString(state.resolve("activa.json"))).isEqualTo(pointerAfterFresh);
    assertThat(web.epoch()).isGreaterThan(oldEpoch).isGreaterThan(firstEpoch);
  }

  @Test
  void perdidaRealDeLaConexionDuranteLaGeneracionAbortaSinRenombrarNiLlamarALaWeb() throws Exception {
    service().publish(ADMIN);
    changeContent("Pérdida");
    // Heartbeat de 1 s: detecta la pérdida mientras la generación «trabaja».
    PublicationService service = service(properties(web.uri(), Duration.ofSeconds(15), Duration.ofSeconds(1),
        Duration.ofSeconds(3)), point -> {
          if (point.equals("generacion")) {
            terminate(lockHolderPid());
            Thread.sleep(10_000); // la interrupción del heartbeat la corta
          }
        }, PublicationLock.Hooks.NONE);
    long start = System.nanoTime();
    assertThatThrownBy(() -> service.publish(ADMIN)).isInstanceOf(PublicationFailure.class)
        .extracting("kind").isEqualTo(PublicationFailure.Kind.LOCK_LOST);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
    assertThat(Files.exists(snapshots.resolve("v2"))).isFalse();
    try (var list = Files.list(snapshots)) {
      assertThat(list.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".tmp"))).isEmpty();
    }
    assertThat(web.activations()).hasSize(1);
    // El siguiente consigue el control enseguida y cierra la generación interrumpida.
    long next = System.nanoTime();
    service().reconcile();
    assertThat(Duration.ofNanos(System.nanoTime() - next)).isLessThan(Duration.ofSeconds(3));
    assertThat(states()).isEqualTo("v1=activa v2=fallida");
    assertThat(audits("content.publish_failed")).isEqualTo(1);
  }

  @Test
  void laEsperaMaximaEsDeQuinceSegundosMasElSondeo() throws Exception {
    try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_LOGIN, TestDatabase.password(APP_LOGIN));
        Statement s = holder.createStatement();
        ResultSet rs = s.executeQuery("SELECT pg_advisory_lock(" + PublicationLock.KEY_CLASS + ", "
            + PublicationLock.KEY_OBJECT + ")")) {
      long start = System.nanoTime();
      assertThatThrownBy(() -> service().publish(ADMIN)).isInstanceOf(PublicationFailure.class)
          .extracting("kind").isEqualTo(PublicationFailure.Kind.BUSY);
      Duration waited = Duration.ofNanos(System.nanoTime() - start);
      assertThat(waited).isBetween(Duration.ofMillis(14_800), Duration.ofMillis(15_500));
    }
    assertThat(states()).isEmpty();
  }

  @Test
  void unaPublicacionDeMasDeDosMinutosConservaElBloqueoYNadieEntra() throws Exception {
    changeContent("Larga");
    CountDownLatch generating = new CountDownLatch(1);
    // Valores de producción: heartbeat 10 s, idle_session_timeout 60 s.
    PublicationService slow = service(point -> {
      if (point.equals("generacion")) {
        generating.countDown();
        Thread.sleep(125_000);
      }
    });
    long start = System.nanoTime();
    CompletableFuture<Object> run = async(() -> slow.publish(ADMIN));
    await(generating);
    int pid = lockHolderPid();
    // Un segundo publicador mientras el primero está protegido: espera 15 s y no entra.
    long second = System.nanoTime();
    assertThatThrownBy(() -> service().publish(ADMIN)).extracting("kind").isEqualTo(PublicationFailure.Kind.BUSY);
    assertThat(Duration.ofNanos(System.nanoTime() - second)).isBetween(Duration.ofMillis(14_800), Duration.ofMillis(15_500));
    // La sesión del bloqueo sigue teniéndolo durante toda la generación (más de 120 s).
    while (!run.isDone()) {
      assertThat(holdsLock(pid)).as("la sesión " + pid + " sigue teniendo el bloqueo").isTrue();
      Thread.sleep(10_000);
      if (Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(124)) > 0) {
        break;
      }
    }
    Object result = run.get(60, TimeUnit.SECONDS);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofSeconds(120));
    assertThat(result).isInstanceOf(PublicationService.Outcome.class);
    assertThat(((PublicationService.Outcome) result).result()).isEqualTo("activada");
    assertThat(states()).isEqualTo("v1=activa");
  }
}
