package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * El flujo de publicación (fase 6.3.4) con PostgreSQL real y la web simulada: publicar, «sin
 * cambios», cambiar, volver atrás, rechazos, la web caída, una respuesta perdida, la recuperación
 * de la época de la web y la purga.
 */
class PublicationServiceTests extends PublicationTestSupport {

  private static final Actor ADMIN = new Actor(null, "api", Map.of());

  @Test
  void publicaActivaYRegistraUnaSolaVez() throws Exception {
    PublicationService service = service();
    PublicationService.Outcome first = service.publish(ADMIN);
    assertThat(first.result()).isEqualTo("activada");
    assertThat(first.version()).isEqualTo(1);
    assertThat(states()).isEqualTo("v1=activa");
    assertThat(pointerVersion()).isEqualTo("v1");
    assertThat(Files.isDirectory(snapshots.resolve("v1"))).isTrue();
    assertThat(audits("content.published")).isEqualTo(1);
    assertThat(check()).isEqualTo("CONCILIADO");
    // La época: reservada en la web y usada para activar.
    assertThat(web.activations()).containsExactly("activar " + web.epoch() + " v1");

    // Sin cambios en lo publicado: ninguna versión nueva.
    PublicationService.Outcome same = service.publish(ADMIN);
    assertThat(same.result()).isEqualTo("sin cambios");
    assertThat(states()).isEqualTo("v1=activa");
    assertThat(audits("content.published")).isEqualTo(1);
  }

  @Test
  void unCambioPublicaV2YVolverAtrasActivaV1() throws Exception {
    PublicationService service = service();
    service.publish(ADMIN);
    changeContent("Costa cambiada");
    PublicationService.Outcome second = service.publish(ADMIN);
    assertThat(second).extracting("result", "version", "previous").containsExactly("activada", 2, 1);
    assertThat(states()).isEqualTo("v1=retirada v2=activa");
    assertThat(second.contentSha256()).isNotEqualTo(one(String.class,
        "SELECT content_sha256 FROM content_publication WHERE version = 1"));

    PublicationService.Outcome back = service.activate(1, ADMIN);
    assertThat(back).extracting("result", "version", "previous").containsExactly("activada", 1, 2);
    assertThat(states()).isEqualTo("v1=activa v2=retirada");
    assertThat(pointerVersion()).isEqualTo("v1");
    assertThat(audits("content.activated")).isEqualTo(1);
    // Activar la activa: sin cambios. Una inexistente: 404.
    assertThat(service.activate(1, ADMIN).result()).isEqualTo("sin cambios");
    assertThatThrownBy(() -> service.activate(9, ADMIN)).isInstanceOf(PublicationFailure.class)
        .extracting("kind").isEqualTo(PublicationFailure.Kind.NOT_FOUND);
  }

  @Test
  void laWebLaRechazaYLaActivaNoCambia() throws Exception {
    PublicationService service = service();
    service.publish(ADMIN);
    changeContent("Rechazable");
    web.override = path -> path.equals("/interno/activar")
        ? Map.entry(422, "{\"resultado\":\"rechazada\",\"incidencias\":[\"articles[0]: etiqueta desconocida\"]}")
        : null;
    PublicationService.Outcome rejected = service.publish(ADMIN);
    assertThat(rejected.result()).isEqualTo("rechazada");
    assertThat(rejected.issues()).containsExactly("articles[0]: etiqueta desconocida");
    assertThat(states()).isEqualTo("v1=activa v2=rechazada");
    assertThat(pointerVersion()).isEqualTo("v1");
    assertThat(audits("content.publish_rejected")).isEqualTo(1);
    // Una rechazada nunca se activa.
    web.override = path -> null;
    assertThatThrownBy(() -> service.activate(2, ADMIN)).extracting("kind")
        .isEqualTo(PublicationFailure.Kind.NOT_ACTIVATABLE);
  }

  @Test
  void conLaWebCaidaLaVersionQuedaFallidaConHuellaYSeActivaDespues() throws Exception {
    PublicationService service = service();
    service.publish(ADMIN);
    changeContent("Con la web caída");
    web.override = path -> path.equals("/interno/activar") || path.equals("/interno/reservar")
        ? Map.entry(503, "{}") : null;
    // La web no reserva ni activa: la versión queda fallida (con huella) y la activa no cambia.
    PublicationService.Outcome failed = service.publish(ADMIN);
    assertThat(failed.result()).isEqualTo("fallida");
    assertThat(audits("content.publish_failed")).isEqualTo(1);
    // Y con la web realmente caída (nadie escucha), lo mismo.
    changeContent("Con la web caída de verdad");
    web.close();
    PublicationService down = service(properties(java.net.URI.create("http://127.0.0.1:1")),
        PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
    assertThat(down.publish(ADMIN).result()).isEqualTo("fallida");
    assertThat(states()).contains("v1=activa").contains("=fallida");
    assertThat(pointerVersion()).isEqualTo("v1");
    // Sigue teniendo huella y archivos: se puede activar cuando vuelva la web.
    int failedVersion = failed.version();
    assertThat(one(String.class, "SELECT content_sha256 FROM content_publication WHERE version = ?", failedVersion))
        .isNotNull();
    web = new FakeWeb(snapshots, state);
    PublicationService back = service();
    // La web nueva no conoce la época anterior (empieza en 0): la nueva época es mayor y se reserva.
    assertThat(back.activate(failedVersion, ADMIN).result()).isEqualTo("activada");
    assertThat(pointerVersion()).isEqualTo("v" + failedVersion);
  }

  @Test
  void unaRespuestaPerdidaSeResuelveConActivaJson() throws Exception {
    PublicationService service = service();
    web.dropResponseAfterActivate = true;
    PublicationService.Outcome outcome = service.publish(ADMIN);
    // La web activó pero no respondió: activa.json ya señala v1, así que se registra activa.
    assertThat(outcome.result()).isEqualTo("activada");
    assertThat(states()).isEqualTo("v1=activa");
    assertThat(audits("content.activated")).isEqualTo(1);
  }

  @Test
  void webBloqueadaLaApiRecuperaLaEpocaYPublica() throws Exception {
    web.block("control.json dañado");
    PublicationService.Outcome outcome = service().publish(ADMIN);
    assertThat(outcome.result()).isEqualTo("activada");
    assertThat(web.blocked()).isFalse();
    long epoch = web.epoch();
    assertThat(epoch).isEqualTo(one(Long.class, "SELECT epoch FROM content_publication_lease"));
    assertThat(audits("content.web_epoch_recovered")).isEqualTo(1);
    // Peticiones antiguas que llegan después de la recuperación: rechazadas.
    assertThat(web.post("/interno/reservar", "{\"epoch\":" + (epoch - 1) + "}")).isEqualTo(409);
    assertThat(web.post("/interno/activar", "{\"version\":\"v1\",\"epoch\":" + (epoch - 1) + "}")).isEqualTo(409);
    assertThat(web.post("/interno/recuperacion/completar", "{\"epoch\":1,\"nonce\":\"" + "a".repeat(32) + "\"}"))
        .isEqualTo(409);
    assertThat(web.epoch()).isEqualTo(epoch);
  }

  @Test
  void unaEpocaMayorEnActivaJsonSubeLaDePostgres() throws Exception {
    // Como tras restaurar PostgreSQL de una copia: activa.json tiene una época mayor que la tabla.
    service().publish(ADMIN);
    String pointer = Files.readString(state.resolve("activa.json")).replaceAll("\"epoch\":\\d+", "\"epoch\":100");
    Files.writeString(state.resolve("activa.json"), pointer);
    changeContent("Tras restaurar");
    assertThat(service().publish(ADMIN).result()).isEqualTo("activada");
    assertThat(one(Long.class, "SELECT epoch FROM content_publication_lease")).isGreaterThan(100L);
    assertThat(web.post("/interno/activar", "{\"version\":\"v1\",\"epoch\":100}")).isEqualTo(409);
  }

  @Test
  void purgaSimuladaYConfirmadaSinTocarLaActivaLaAnteriorNiLasRecientes() throws Exception {
    PublicationProperties props = new PublicationProperties(snapshots, state, web.uri(), java.time.Duration.ofSeconds(15),
        java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(60),
        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(30), 1, 50);
    PublicationService service = service(props, PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
    for (int i = 1; i <= 5; i++) {
      changeContent("Versión " + i);
      service.publish(ADMIN);
    }
    service.activate(2, ADMIN); // activa v2, anterior v5; la más reciente (keep = 1) es v5
    PublicationService.PurgeReport dry = service.purge(false, ADMIN);
    assertThat(dry.eligible()).containsExactly(1, 3, 4);
    assertThat(dry.purged()).isEmpty();
    assertThat(Files.isDirectory(snapshots.resolve("v1"))).isTrue();

    PublicationService.PurgeReport done = service.purge(true, ADMIN);
    assertThat(done.purged()).containsExactly(1, 3, 4);
    for (int v : new int[] {1, 3, 4}) {
      assertThat(Files.exists(snapshots.resolve("v" + v))).isFalse();
    }
    assertThat(Files.isDirectory(snapshots.resolve("v2"))).isTrue();
    assertThat(Files.isDirectory(snapshots.resolve("v5"))).isTrue();
    // Las filas se conservan, con la fecha de la purga, y no se pueden activar.
    assertThat(one(Long.class, "SELECT count(*) FROM content_publication WHERE files_purged_at IS NOT NULL")).isEqualTo(3L);
    assertThat(audits("content.snapshot_purged")).isEqualTo(3);
    assertThatThrownBy(() -> service.activate(3, ADMIN)).isInstanceOf(PublicationFailure.class);
    // Repetir la purga no hace nada más.
    assertThat(service.purge(true, ADMIN).purged()).isEmpty();
  }

  @Test
  void dosPublicacionesALaVezLaSegundaEsperaYTermina() throws Exception {
    java.util.concurrent.CountDownLatch generating = new java.util.concurrent.CountDownLatch(1);
    PublicationService slow = service(point -> {
      if (point.equals("generacion")) {
        generating.countDown();
        Thread.sleep(2_000);
      }
    });
    var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> slow.publish(ADMIN));
    assertThat(generating.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    long start = System.nanoTime();
    PublicationService.Outcome second = service().publish(ADMIN);
    long waited = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
    assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS).result()).isEqualTo("activada");
    // La segunda esperó a la primera y, con el mismo contenido, no creó otra versión.
    assertThat(waited).isGreaterThan(1_000);
    assertThat(second.result()).isEqualTo("sin cambios");
    assertThat(states()).isEqualTo("v1=activa");
  }
}
