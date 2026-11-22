package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Medición (no forma parte de la suite: solo con -Dbench=true) de la PUBLICACIÓN COMPLETA de la
 * 6.3.4 con PostgreSQL real y el servidor Node real del build de instantáneas: tomar el control,
 * reconciliar, leer lo publicado, generar, P2, reservar y activar en la web y reconciliar. Sin carga
 * y con la misma carga que la comparación de la activación (8 clientes, la mitad sin caché).
 * Escribe los percentiles en -Dbench.out.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class PublicationBenchmarkTests extends PublicationTestSupport {

  private static final Path SERVER = Path.of("../dist/cristian-atienza-instantaneas/server/server.mjs");
  private static final Actor ADMIN = new Actor(null, "api", Map.of());
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private Process node;

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @AfterEach
  void stopNode() throws Exception {
    if (node != null) {
      node.destroy();
      node.waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static String stats(List<Double> values) {
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    java.util.function.IntFunction<Double> pick = p -> sorted.get(Math.min(sorted.size() - 1,
        (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    return String.format(java.util.Locale.ROOT,
        "{\"n\":%d,\"p50\":%.1f,\"p95\":%.1f,\"p99\":%.1f,\"media\":%.1f,\"min\":%.1f,\"max\":%.1f}",
        sorted.size(), pick.apply(50), pick.apply(95), pick.apply(99), mean, sorted.getFirst(), sorted.getLast());
  }

  @Test
  void publicacionCompletaSinCargaYBajoCarga() throws Exception {
    assertThat(SERVER).exists();
    web.close();
    int port = freePort();
    int internal = freePort();
    ProcessBuilder builder = new ProcessBuilder("node", SERVER.toAbsolutePath().toString()).redirectErrorStream(true);
    builder.environment().putAll(Map.of("PORT", Integer.toString(port), "CONTENT_SNAPSHOTS_DIR", snapshots.toString(),
        "CONTENT_STATE_DIR", state.toString(), "WEB_INTERNAL_BIND", "127.0.0.1:" + internal));
    node = builder.start();
    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
    StringBuilder log = new StringBuilder();
    Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
      synchronized (log) {
        log.append(line).append('\n');
      }
    }));
    long deadline = System.currentTimeMillis() + 30_000;
    while (!log.toString().contains("listening") && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    PublicationService service = service(properties(URI.create("http://127.0.0.1:" + internal)),
        PublicationService.Hooks.NONE, PublicationLock.Hooks.NONE);
    assertThat(service.publish(ADMIN).result()).isEqualTo("activada"); // primera: no se mide

    AtomicInteger counter = new AtomicInteger();
    List<Double> idle = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      changeContent("Medición " + counter.incrementAndGet());
      long t0 = System.nanoTime();
      assertThat(service.publish(ADMIN).result()).isEqualTo("activada");
      idle.add((System.nanoTime() - t0) / 1e6);
    }

    AtomicBoolean running = new AtomicBoolean(true);
    List<Double> ssrUnderLoad = new CopyOnWriteArrayList<>();
    List<Thread> workers = new ArrayList<>();
    for (int w = 0; w < 8; w++) {
      boolean bypass = w % 2 == 1;
      String path = w % 4 < 2 ? "/" : "/viajes/calas-de-mallorca";
      workers.add(Thread.ofVirtual().start(() -> {
        int n = 0;
        while (running.get()) {
          String url = "http://localhost:" + port + path + (bypass ? "?carga=" + n++ : "");
          long t = System.nanoTime();
          try {
            HttpResponse<Void> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.discarding());
            if (bypass && r.statusCode() == 200) {
              ssrUnderLoad.add((System.nanoTime() - t) / 1e6);
            }
          } catch (Exception ignored) {
            // contabilizado como ausencia de muestra
          }
        }
      }));
    }
    Thread.sleep(1500);
    List<Double> loaded = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      changeContent("Medición " + counter.incrementAndGet());
      long t0 = System.nanoTime();
      assertThat(service.publish(ADMIN).result()).isEqualTo("activada");
      loaded.add((System.nanoTime() - t0) / 1e6);
      Thread.sleep(300);
    }
    running.set(false);
    for (Thread worker : workers) {
      worker.join(30_000);
    }
    String json = "{\"publicacionCompleta\":{\"sinCarga\":" + stats(idle) + ",\"bajoCarga\":" + stats(loaded)
        + "},\"ssrBajoCarga\":" + stats(ssrUnderLoad) + "}";
    System.out.println("MEDICION " + json);
    String out = System.getProperty("bench.out");
    if (out != null) {
      Files.writeString(Path.of(out), json);
    }
  }
}
