package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * De extremo a extremo con la WEB REAL (fase 6.3.4, R7): el servidor Node del build de instantáneas
 * (`npm run build:snapshot`) con carpetas temporales, y la API (este servicio) con PostgreSQL real.
 * Si el build no existe, se omite. El Compose y los volúmenes son de la 6.3.5.
 */
class PublicationWebEndToEndTests extends PublicationTestSupport {

  private static final Path SERVER = Path.of("../dist/cristian-atienza-instantaneas/server/server.mjs");
  private static final Actor ADMIN = new Actor(null, "api", Map.of());
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private Process node;
  private int port;
  private int internal;
  private final StringBuilder log = new StringBuilder();

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private void startNodeWeb() throws Exception {
    synchronized (log) {
      log.setLength(0);
    }
    ProcessBuilder builder = new ProcessBuilder("node", SERVER.toAbsolutePath().toString()).redirectErrorStream(true);
    builder.environment().putAll(Map.of("PORT", Integer.toString(port), "CONTENT_SNAPSHOTS_DIR", snapshots.toString(),
        "CONTENT_STATE_DIR", state.toString(), "WEB_INTERNAL_BIND", "127.0.0.1:" + internal));
    node = builder.start();
    long deadline = System.currentTimeMillis() + 30_000;
    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
      synchronized (log) {
        log.append(line).append('\n');
      }
    }));
    while (System.currentTimeMillis() < deadline) {
      synchronized (log) {
        if (log.toString().contains("listening")) {
          return;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("la web no arranca:\n" + log);
  }

  private void stopNodeWeb() throws Exception {
    if (node != null && node.isAlive()) {
      node.destroy();
      node.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @AfterEach
  void stopNode() throws Exception {
    stopNodeWeb();
  }

  private String version() throws Exception {
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
        + "/content/version.json")).build(), HttpResponse.BodyHandlers.ofString());
    return response.statusCode() == 200 ? PublicationRepository.JSON.readTree(response.body()).path("version").asString()
        : "HTTP " + response.statusCode();
  }

  private int internalPost(String path, String body) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + internal + path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void publicarVolverAtrasCaidasYRecuperacionConLaWebReal() throws Exception {
    assumeTrue(Files.exists(SERVER), "sin el build de instantáneas (npm run build:snapshot)");
    web.close(); // aquí no se usa la web simulada
    port = freePort();
    internal = freePort();
    startNodeWeb();
    PublicationService service = service(properties(URI.create("http://127.0.0.1:" + internal)),
        PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
    assertThat(version()).isEqualTo("HTTP 503"); // volumen vacío: sin versión activa

    // 1. Publicar v1.
    assertThat(service.publish(ADMIN).result()).isEqualTo("activada");
    assertThat(version()).isEqualTo("v1");

    // 2. Un cambio en PostgreSQL: publicado y servido en menos de 60 s, sin build.
    changeContent("Costa publicada");
    long start = System.nanoTime();
    assertThat(service.publish(ADMIN).result()).isEqualTo("activada");
    while (!version().equals("v2") && Duration.ofNanos(System.nanoTime() - start).toSeconds() < 60) {
      Thread.sleep(100);
    }
    Duration visible = Duration.ofNanos(System.nanoTime() - start);
    assertThat(version()).isEqualTo("v2");
    assertThat(visible).isLessThan(Duration.ofSeconds(60));
    System.out.println("Cambio visible en la web en " + visible.toMillis() + " ms");

    // 3. Volver atrás.
    assertThat(service.activate(1, ADMIN).result()).isEqualTo("activada");
    assertThat(version()).isEqualTo("v1");
    assertThat(states()).isEqualTo("v1=activa v2=retirada");

    // 4. Con la web parada, la publicación queda fallida; al arrancar, la web sigue con v1.
    stopNodeWeb();
    changeContent("Con la web parada");
    assertThat(service.publish(ADMIN).result()).isEqualTo("fallida");
    startNodeWeb();
    assertThat(version()).isEqualTo("v1");

    // 5. control.json dañado: la web arranca bloqueada (sirviendo v1) y la API la recupera al publicar.
    stopNodeWeb();
    Files.writeString(state.resolve("control.json"), "{dañado");
    startNodeWeb();
    assertThat(version()).isEqualTo("v1");
    assertThat(internalPost("/interno/reservar", "{\"epoch\":999}")).isEqualTo(423);
    assertThat(service.publish(ADMIN).result()).isEqualTo("activada");
    assertThat(version()).isEqualTo("v4");
    assertThat(audits("content.web_epoch_recovered")).isEqualTo(1);
    long epoch = one(Long.class, "SELECT epoch FROM content_publication_lease");
    assertThat(internalPost("/interno/reservar", "{\"epoch\":" + (epoch - 1) + "}")).isEqualTo(409);
    assertThat(states()).isEqualTo("v1=retirada v2=retirada v3=fallida v4=activa");
    assertThat(check()).isEqualTo("CONCILIADO");
  }
}
