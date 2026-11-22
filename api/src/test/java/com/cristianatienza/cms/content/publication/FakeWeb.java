package com.cristianatienza.cms.content.publication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web simulada para los tests de la API (fase 6.3.4): el puerto interno con las MISMAS reglas que la
 * web real (src/app/content/snapshot/activation.ts, probadas en Vitest): reservar, activar con la
 * época reservada, estado bloqueado y recuperación con nonce. Escribe activa.json como la real.
 * Comprueba la versión con SnapshotVerifier en lugar de validateContent() (eso es de la web).
 * Además, para provocar los casos difíciles: rechazar, caerse, perder la respuesta y retener
 * peticiones.
 */
final class FakeWeb implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Path snapshots;
  private final Path state;
  private long epoch;
  private String blocked;
  private String nonce;
  private Integer active;
  /** Peticiones recibidas: «ruta epoca version». */
  final List<String> calls = new ArrayList<>();
  /** Si devuelve algo, se responde eso en vez de procesar (por ejemplo, un 422). */
  volatile Function<String, Map.Entry<Integer, String>> override = path -> null;
  /** Activa y luego corta la conexión sin responder (respuesta perdida). */
  volatile boolean dropResponseAfterActivate;
  /** Retiene la siguiente activación hasta que se libere el cerrojo. */
  volatile CountDownLatch holdActivation;
  volatile CountDownLatch activationHeld = new CountDownLatch(1);

  FakeWeb(Path snapshots, Path state) throws IOException {
    this.snapshots = snapshots;
    this.state = state;
    Files.createDirectories(state);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/interno/", this::handle);
    server.start();
  }

  URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  synchronized void block(String reason) {
    blocked = reason;
    epoch = -1;
  }

  synchronized long epoch() {
    return epoch;
  }

  synchronized boolean blocked() {
    return blocked != null;
  }

  synchronized List<String> activations() {
    return calls.stream().filter(c -> c.startsWith("activar")).toList();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
    Map.Entry<Integer, String> forced = override.apply(path);
    if (forced != null) {
      send(exchange, forced.getKey(), forced.getValue());
      return;
    }
    if (path.equals("/interno/activar") && holdActivation != null) {
      CountDownLatch hold = holdActivation;
      holdActivation = null;
      activationHeld.countDown();
      try {
        hold.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    Map.Entry<Integer, String> result;
    synchronized (this) {
      result = switch (path) {
        case "/interno/reservar" -> reserve(body.path("epoch").longValue());
        case "/interno/activar" -> activate(body.path("version").asString(""), body.path("epoch").longValue());
        case "/interno/recuperacion/iniciar" -> begin();
        case "/interno/recuperacion/completar" -> complete(body.path("epoch").longValue(), body.path("nonce").asString(""));
        default -> Map.entry(404, "{\"resultado\":\"no encontrado\"}");
      };
    }
    if (path.equals("/interno/activar") && dropResponseAfterActivate && result.getKey() == 200) {
      dropResponseAfterActivate = false;
      exchange.close(); // sin respuesta: el cliente ve un error de E/S
      return;
    }
    send(exchange, result.getKey(), result.getValue());
  }

  private Map.Entry<Integer, String> reserve(long requested) {
    calls.add("reservar " + requested);
    if (blocked != null) {
      return Map.entry(423, "{\"resultado\":\"recuperación necesaria\"}");
    }
    if (requested < epoch) {
      return Map.entry(409, "{\"resultado\":\"época obsoleta\"}");
    }
    epoch = requested;
    return Map.entry(200, "{\"resultado\":\"reservada\",\"epoca\":" + requested + "}");
  }

  private Map.Entry<Integer, String> activate(String version, long requested) {
    calls.add("activar " + requested + " " + version);
    if (blocked != null) {
      return Map.entry(423, "{\"resultado\":\"recuperación necesaria\"}");
    }
    if (requested != epoch) {
      return Map.entry(409, "{\"resultado\":\"" + (requested < epoch ? "época obsoleta" : "época no reservada") + "\"}");
    }
    Path dir = snapshots.resolve(version);
    if (!Files.isDirectory(dir) || !com.cristianatienza.cms.content.snapshot.SnapshotVerifier.verify(dir).isEmpty()) {
      return Map.entry(422, "{\"resultado\":\"rechazada\",\"incidencias\":[\"la versión " + version + " no existe\"]}");
    }
    int number = Integer.parseInt(version.substring(1));
    String sha = com.cristianatienza.cms.content.snapshot.SnapshotVerifier.summary(dir).orElseThrow().contentSha256();
    if (active != null && active == number) {
      return Map.entry(200, "{\"resultado\":\"sin cambios\",\"version\":\"" + version + "\"}");
    }
    String pointer = "{\"schemaVersion\":1,\"version\":\"" + version + "\",\"contentSha256\":\"" + sha
        + "\",\"activatedAt\":\"" + Instant.now() + "\",\"previous\":" + (active == null ? "null" : "\"v" + active + "\"")
        + ",\"epoch\":" + requested + "}";
    try {
      Files.writeString(state.resolve("activa.json"), pointer);
    } catch (IOException e) {
      return Map.entry(500, "{\"resultado\":\"error\"}");
    }
    active = number;
    return Map.entry(200, "{\"resultado\":\"activada\",\"version\":\"" + version + "\"}");
  }

  private Map.Entry<Integer, String> begin() {
    calls.add("iniciar");
    if (blocked == null) {
      return Map.entry(409, "{\"resultado\":\"no bloqueada\"}");
    }
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    nonce = HexFormat.of().formatHex(bytes);
    return Map.entry(200, "{\"resultado\":\"iniciada\",\"nonce\":\"" + nonce + "\"}");
  }

  private Map.Entry<Integer, String> complete(long requested, String given) {
    calls.add("completar " + requested);
    if (blocked == null) {
      return Map.entry(409, "{\"resultado\":\"no bloqueada\"}");
    }
    if (nonce == null || !nonce.equals(given)) {
      return Map.entry(409, "{\"resultado\":\"nonce no válido\"}");
    }
    nonce = null;
    blocked = null;
    epoch = requested;
    return Map.entry(200, "{\"resultado\":\"recuperada\",\"epoca\":" + requested + "}");
  }

  /** Para las peticiones «antiguas» que los tests envían directamente. */
  int post(String path, String body) throws Exception {
    var request = java.net.http.HttpRequest.newBuilder(uri().resolve(path))
        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    return java.net.http.HttpClient.newHttpClient()
        .send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
