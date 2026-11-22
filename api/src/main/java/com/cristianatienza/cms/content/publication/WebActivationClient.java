package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.publication.PublicationFailure.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Cliente del puerto interno de la web (fase 6.3.4), con el HttpClient del JDK (sin dependencias
 * nuevas). Solo envía nombres de versión, épocas y el nonce de recuperación: nunca contenido.
 */
class WebActivationClient {

  /** Respuesta de la web: estado HTTP y cuerpo JSON (vacío si no lo es). */
  record Response(int status, JsonNode body) {
    String result() {
      return body.path("resultado").asString("");
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final HttpClient http;
  private final Duration requestTimeout;

  WebActivationClient(URI base, Duration connectTimeout, Duration requestTimeout) {
    this.base = base;
    this.requestTimeout = requestTimeout;
    this.http = HttpClient.newBuilder().connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
  }

  Response reserve(long epoch) {
    return post("/interno/reservar", Map.of("epoch", epoch));
  }

  Response activate(int version, long epoch) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", "v" + version);
    body.put("epoch", epoch);
    return post("/interno/activar", body);
  }

  Response beginRecovery() {
    return post("/interno/recuperacion/iniciar", Map.of());
  }

  Response completeRecovery(long epoch, String nonce) {
    return post("/interno/recuperacion/completar", Map.of("epoch", epoch, "nonce", nonce));
  }

  private Response post(String path, Map<String, ?> body) {
    HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(requestTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode json;
      try {
        json = JSON.readTree(response.body());
      } catch (JacksonException e) {
        json = JsonNodeFactory.instance.objectNode();
      }
      return new Response(response.statusCode(), json == null ? JsonNodeFactory.instance.objectNode() : json);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublicationFailure(Kind.LOCK_LOST, "interrumpido mientras se llamaba a la web", e);
    } catch (IOException e) {
      throw new PublicationFailure(Kind.WEB_UNAVAILABLE, "la web no responde (" + e.getClass().getSimpleName() + ")", e);
    }
  }
}
