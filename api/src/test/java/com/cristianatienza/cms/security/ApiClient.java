package com.cristianatienza.cms.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cliente HTTP de los tests con un «navegador» mínimo: guarda las cookies a mano (el cliente de Java
 * no enviaría las cookies Secure por HTTP en las pruebas) y añade la cabecera CSRF desde la cookie
 * __Host-XSRF-TOKEN, como hará el CMS. Conserva las cabeceras Set-Cookie para inspeccionarlas.
 */
final class ApiClient {

  record Response(int status, String body, List<String> setCookies, HttpResponse<String> raw) {
    String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }

    /** La cabecera Set-Cookie de una cookie, o null. */
    String setCookie(String name) {
      return setCookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b).orElse(null);
    }
  }

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  /** IP del cliente vista tras un proxy de confianza (solo en los tests que lo configuran). */
  private String forwardedFor;
  private String userAgent = "navegador-de-pruebas/1.0";

  /** Este cliente llega desde otra IP (cabecera X-Forwarded-For de un proxy de confianza). */
  ApiClient from(String ip) {
    this.forwardedFor = ip;
    return this;
  }

  /** Otro navegador (agente de usuario). */
  ApiClient agent(String agent) {
    this.userAgent = agent;
    return this;
  }

  ApiClient(int port) {
    this.base = "http://localhost:" + port;
    this.port = port;
  }

  private final int port;

  int port() {
    return port;
  }

  /** El mismo «navegador» (sus cookies, IP y agente) contra otra instancia de la API (6.2.7.4). */
  ApiClient on(int otherPort) {
    ApiClient copy = new ApiClient(otherPort);
    copy.cookies.putAll(cookies);
    copy.forwardedFor = forwardedFor;
    copy.userAgent = userAgent;
    return copy;
  }

  Map<String, String> cookies() {
    return cookies;
  }

  String cookie(String name) {
    return cookies.get(name);
  }

  Response get(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), false);
  }

  Response post(String path, String json) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
        .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    if (json != null) {
      b.header("Content-Type", "application/json");
    }
    return send(b, true);
  }

  Response put(String path, String json) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(json)), true);
  }

  Response delete(String path) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE(), true);
  }

  /** POST sin la cabecera CSRF (aunque exista la cookie). */
  Response postWithoutCsrf(String path, String json) throws Exception {
    return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json)), false);
  }

  Response send(HttpRequest.Builder builder, boolean withCsrf) throws Exception {
    if (forwardedFor != null) {
      builder.header("X-Forwarded-For", forwardedFor);
    }
    builder.header("User-Agent", userAgent);
    if (!cookies.isEmpty()) {
      builder.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
          .collect(Collectors.joining("; ")));
    }
    if (withCsrf && cookies.containsKey(SecurityConfig.CSRF_COOKIE)) {
      builder.header(SecurityConfig.CSRF_HEADER, cookies.get(SecurityConfig.CSRF_COOKIE));
    }
    HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    List<String> setCookies = response.headers().allValues("Set-Cookie");
    for (String header : setCookies) {
      String pair = header.split(";", 2)[0];
      String name = pair.substring(0, pair.indexOf('='));
      String value = pair.substring(pair.indexOf('=') + 1);
      boolean expired = header.contains("Max-Age=0") || value.isEmpty();
      if (expired) {
        cookies.remove(name);
      } else {
        cookies.put(name, value);
      }
    }
    return new Response(response.statusCode(), response.body(), setCookies, response);
  }

  /** CSRF y después inicio de sesión. */
  Response login(String email, String password) throws Exception {
    get("/api/auth/csrf");
    return post("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}");
  }
}
