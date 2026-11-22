package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Interrupciones en cada punto del flujo (P1–P4) y la reconciliación (fase 6.3.4): PostgreSQL
 * termina siempre conciliado con activa.json, sin transiciones fuera de la matriz, sin eventos
 * duplicados (reconciliar 3 veces) y declarando INCONSISTENTE lo que no puede resolver solo.
 */
class PublicationRecoveryTests extends PublicationTestSupport {

  private static final Actor ADMIN = new Actor(null, "api", Map.of());

  /** Una caída en ese punto: un Error no lo atrapa nadie, como un proceso que muere. */
  private PublicationService crashingAt(String point) {
    return service(at -> {
      if (at.equals(point)) {
        throw new Error("caída simulada en " + point);
      }
    });
  }

  /** Reconciliar 3 veces: los mismos estados y ningún evento nuevo después de la primera. */
  private void reconcileThriceIdempotent(String expectedStates, String expectedCheck) throws Exception {
    service().reconcile();
    String states = states();
    long audits = allAudits();
    service().reconcile();
    service().reconcile();
    assertThat(states()).isEqualTo(states).isEqualTo(expectedStates);
    assertThat(allAudits()).as("sin eventos duplicados").isEqualTo(audits);
    assertThat(check()).startsWith(expectedCheck);
  }

  // --- Interrupciones -----------------------------------------------------------------------------

  @Test
  void caidaTrasP1SinCarpetaFallidaSinHuella() throws Exception {
    service().publish(ADMIN);
    changeContent("P1");
    assertThatThrownBy(() -> crashingAt("generacion").publish(ADMIN)).isInstanceOf(Error.class);
    assertThat(states()).isEqualTo("v1=activa v2=generando");
    reconcileThriceIdempotent("v1=activa v2=fallida", "CONCILIADO");
    assertThat(one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = 2")).isNull();
    try (var list = Files.list(snapshots)) {
      assertThat(list.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".tmp"))).isEmpty();
    }
    assertThat(audits("content.publish_failed")).isEqualTo(1);
    // Nunca se puede activar.
    assertThatThrownBy(() -> service().activate(2, ADMIN)).extracting("kind")
        .isEqualTo(PublicationFailure.Kind.NOT_ACTIVATABLE);
  }

  @Test
  void corteEntreElRenombradoYElCommitSeAdoptaLaCarpeta() throws Exception {
    service().publish(ADMIN);
    changeContent("P2");
    assertThatThrownBy(() -> crashingAt("p2.antesDelCommit").publish(ADMIN)).isInstanceOf(Error.class);
    // La transacción se deshizo, pero v2 ya se había renombrado.
    assertThat(states()).isEqualTo("v1=activa v2=generando");
    assertThat(Files.isDirectory(snapshots.resolve("v2"))).isTrue();
    reconcileThriceIdempotent("v1=activa v2=fallida", "CONCILIADO");
    assertThat(one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = 2")).isNotNull();
    // Con huella y archivos: se puede activar.
    assertThat(service().activate(2, ADMIN).result()).isEqualTo("activada");
  }

  @Test
  void perdidaDespuesDelRenombradoYAntesDeLaWeb() throws Exception {
    service().publish(ADMIN);
    changeContent("Tras el renombrado");
    // Pérdida REAL de la conexión del bloqueo justo después del COMMIT de P2.
    PublicationService service = service(at -> {
      if (at.equals("p2.despues")) {
        terminate(lockHolderPid());
        Thread.sleep(200);
      }
    });
    assertThatThrownBy(() -> service.publish(ADMIN)).isInstanceOf(PublicationFailure.class);
    assertThat(web.activations()).hasSize(1); // solo la de v1: nunca se llamó a la web para v2
    assertThat(states()).isEqualTo("v1=activa v2=candidata");
    assertThat(Files.isDirectory(snapshots.resolve("v2"))).isTrue();
    reconcileThriceIdempotent("v1=activa v2=fallida", "CONCILIADO");
    assertThat(audits("content.publish_failed")).isEqualTo(1);
    assertThat(service().activate(2, ADMIN).result()).isEqualTo("activada");
  }

  @Test
  void laWebActivoPeroElProcesoCayoAntesDeRegistrarlo() throws Exception {
    service().publish(ADMIN);
    changeContent("P3");
    assertThatThrownBy(() -> crashingAt("p3.despuesDeLaWeb").publish(ADMIN)).isInstanceOf(Error.class);
    assertThat(pointerVersion()).isEqualTo("v2");
    assertThat(states()).isEqualTo("v1=activa v2=candidata");
    reconcileThriceIdempotent("v1=retirada v2=activa", "CONCILIADO");
    assertThat(audits("content.activated")).isEqualTo(1); // por la reconciliación, una vez
    assertThat(audits("content.published")).isEqualTo(1); // solo la de v1
  }

  @Test
  void postgresCaeEnP4TrasActivarEnLaWeb() throws Exception {
    service().publish(ADMIN);
    changeContent("P4");
    // Se termina la sesión de PostgreSQL de la transacción protegida de P3/P4 (no la del bloqueo).
    PublicationService service = service(at -> {
      if (at.equals("p3.despuesDeLaWeb")) {
        int lock = lockHolderPid();
        tx("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'cms_api'"
            + " AND state LIKE 'idle in transaction%' AND pid <> " + lock);
      }
    });
    assertThatThrownBy(() -> service.publish(ADMIN)).isInstanceOf(PublicationFailure.class)
        .extracting("kind").isEqualTo(PublicationFailure.Kind.DATABASE);
    assertThat(pointerVersion()).isEqualTo("v2");
    assertThat(states()).isEqualTo("v1=activa v2=candidata");
    reconcileThriceIdempotent("v1=retirada v2=activa", "CONCILIADO");
    assertThat(audits("content.activated")).isEqualTo(1);
  }

  // --- Reconciliación sin transiciones fuera de la matriz -----------------------------------------

  private void writePointer(String version, String sha) throws Exception {
    Files.writeString(state.resolve("activa.json"), "{\"schemaVersion\":1,\"version\":\"" + version
        + "\",\"contentSha256\":\"" + sha + "\",\"activatedAt\":\"2026-11-20T10:00:00Z\",\"previous\":null}");
  }

  @Test
  void sinPublicacionesNiPunteroVacio() throws Exception {
    reconcileThriceIdempotent("", "VACIO");
  }

  @Test
  void elPunteroSenalaUnaRechazadaInconsistenteSinCambios() throws Exception {
    web.override = path -> path.equals("/interno/activar")
        ? Map.entry(422, "{\"resultado\":\"rechazada\",\"incidencias\":[\"x\"]}") : null;
    service().publish(ADMIN);
    web.override = path -> null;
    writePointer("v1", one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = 1"));
    reconcileThriceIdempotent("v1=rechazada", "INCONSISTENTE activa.json señala v1, registrada como rechazada");
    assertThat(audits("content.reconciliation_failed")).isEqualTo(1);
  }

  @Test
  void punteroAusenteDanadoDeOtraHuellaOSinRegistro() throws Exception {
    service().publish(ADMIN);
    String sha = one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = 1");
    Files.delete(state.resolve("activa.json"));
    reconcileThriceIdempotent("v1=activa", "INCONSISTENTE no hay activa.json");
    Files.writeString(state.resolve("activa.json"), "{roto");
    reconcileThriceIdempotent("v1=activa", "INCONSISTENTE activa.json no válido");
    writePointer("v1", "0".repeat(64));
    reconcileThriceIdempotent("v1=activa", "INCONSISTENTE activa.json señala v1 con una huella distinta");
    writePointer("v7", sha);
    reconcileThriceIdempotent("v1=activa", "INCONSISTENTE activa.json señala v7, que no tiene registro");
    // Un evento por cada cambio de situación, nunca por repetir.
    assertThat(audits("content.reconciliation_failed")).isEqualTo(4);

    // Recuperación explícita: la web vuelve a escribir el puntero al activar (aquí, tras reiniciarla).
    web.close();
    web = new FakeWeb(snapshots, state);
    assertThat(service().activate(1, ADMIN).result()).isEqualTo("activada");
    assertThat(check()).isEqualTo("CONCILIADO");
    long reconciled = audits("content.reconciled");
    reconcileThriceIdempotent("v1=activa", "CONCILIADO");
    assertThat(audits("content.reconciled")).isEqualTo(reconciled);
  }

  @Test
  void conUnaInconsistenciaPublicarNoSeSaltaComoSinCambios() throws Exception {
    service().publish(ADMIN);
    Files.delete(state.resolve("activa.json"));
    web.close();
    web = new FakeWeb(snapshots, state);
    // El mismo contenido, pero sin puntero: se publica una versión nueva y la web vuelve a tenerlo.
    PublicationService.Outcome outcome = service().publish(ADMIN);
    assertThat(outcome.result()).isEqualTo("activada");
    assertThat(outcome.version()).isEqualTo(2);
    assertThat(check()).isEqualTo("CONCILIADO");
  }

  // --- PostgreSQL caído al arrancar ---------------------------------------------------------------

  @Test
  void conPostgresCaidoAlArrancarSeReintentaHastaReconciliar() throws Exception {
    AtomicInteger failures = new AtomicInteger(3);
    DataSource real = appDataSource();
    DataSource flaky = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConnection") && failures.getAndDecrement() > 0) {
            throw new SQLTransientConnectionException("PostgreSQL no disponible (simulado)", "08001");
          }
          return method.invoke(real, args);
        });
    PublicationProperties props = new PublicationProperties(snapshots, state, web.uri(), Duration.ofSeconds(15),
        Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(2),
        Duration.ofSeconds(30), Duration.ofMillis(300), 20, 50);
    PublicationService service = new PublicationService(flaky, props,
        new WebActivationClient(web.uri(), Duration.ofSeconds(2), Duration.ofSeconds(30)),
        new PublicationAudit(new StaticListableBeanFactory().getBeanProvider(com.cristianatienza.cms.security.audit.AuditLog.class)),
        lockConnections(), System::nanoTime, PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
    PublicationStartup startup = new PublicationStartup(service, props);
    startup.onReady();
    long deadline = System.currentTimeMillis() + 20_000;
    while (check().startsWith("SIN_COMPROBAR") && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    startup.stop();
    assertThat(check()).isEqualTo("VACIO");
    assertThat(failures.get()).isLessThanOrEqualTo(0);
  }

  @SuppressWarnings("unused")
  private static Connection unused() throws SQLException {
    return null;
  }
}
