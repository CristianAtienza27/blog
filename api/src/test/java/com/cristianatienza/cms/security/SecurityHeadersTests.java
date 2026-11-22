package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import cmstest.CmsTestControllers;
import cmstest.SendErrorFilterConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cabeceras de seguridad y formato de error en TODOS los caminos de respuesta (6.2.5): controlador,
 * Actuator, punto de entrada y acceso denegado de Spring Security, CSRF, cortafuegos, manejadores de
 * excepciones, limitación de intentos, método no permitido, recurso inexistente, error inesperado y
 * despacho de errores del contenedor. HSTS nunca por HTTP, ni con X-Forwarded-Proto del cliente.
 */
@Import({CmsTestControllers.Annotated.class, SendErrorFilterConfig.class})
class SecurityHeadersTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("X-Content-Type-Options", "nosniff");
    EXPECTED.put("X-Frame-Options", "DENY");
    EXPECTED.put("Cache-Control", "no-store");
    EXPECTED.put("Referrer-Policy", "no-referrer");
    EXPECTED.put("Permissions-Policy", "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(),"
        + " microphone=(), payment=(), usb=()");
    EXPECTED.put("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    EXPECTED.put("Cross-Origin-Resource-Policy", "same-origin");
  }

  /** Cabeceras exactas (un solo valor cada una), correlación UUID, sin HSTS y sin cabeceras de servidor. */
  private static void assertHeaders(String path, ApiClient.Response response) {
    var headers = response.raw().headers();
    EXPECTED.forEach((name, value) ->
        assertThat(headers.allValues(name)).as(path + " " + name).containsExactly(value));
    assertThat(UUID.fromString(response.header("X-Correlation-Id"))).as(path + " correlación").isNotNull();
    assertThat(headers.firstValue("Strict-Transport-Security")).as(path + " HSTS por HTTP").isEmpty();
    assertThat(headers.firstValue("Server")).as(path + " Server").isEmpty();
    assertThat(headers.firstValue("X-Powered-By")).as(path + " X-Powered-By").isEmpty();
  }

  /** Error en ProblemDetail, sin detalles internos, con la correlación de la cabecera. */
  private static void assertProblem(String path, ApiClient.Response response, int status, String code) throws Exception {
    assertThat(response.status()).as(path).isEqualTo(status);
    assertThat(response.header("Content-Type")).as(path).startsWith("application/problem+json");
    JsonNode body = JSON.readTree(response.body());
    // RFC 9457: sin «type» equivale a about:blank.
    assertThat(body.path("type").asString("about:blank")).as(path).isEqualTo("about:blank");
    assertThat(body.path("status").asInt()).as(path).isEqualTo(status);
    assertThat(body.path("title").asString()).as(path).isNotBlank();
    assertThat(body.path("detail").asString()).as(path).isNotBlank();
    assertThat(body.path("code").asString()).as(path).isEqualTo(code);
    assertThat(body.path("correlationId").asString()).as(path).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(response.body()).as(path).doesNotContain("Exception", "exception", "trace", "com.cristianatienza",
        "org.springframework", "\"message\"", "\"path\"");
  }

  @Test
  void respuestasCorrectasDeControladorYActuator() throws Exception {
    ApiClient client = client();
    assertHeaders("csrf", client.get("/api/auth/csrf"));
    assertHeaders("health", client.get("/actuator/health"));
    client.login(createUser(true, true), PASSWORD);
    var logout = client.post("/api/auth/logout", null);
    assertThat(logout.status()).isEqualTo(204);
    assertHeaders("logout", logout);
  }

  @Test
  void erroresDeSpringSecurityYDelCortafuegos() throws Exception {
    var anonymous = client().get("/api/auth/me");
    assertHeaders("401", anonymous);
    assertProblem("401", anonymous, 401, "unauthenticated");

    ApiClient p1 = client();
    p1.login(createUser(true, true), PASSWORD);
    var denied = p1.get("/api/auth/passkeys");
    assertHeaders("403", denied);
    assertProblem("403", denied, 403, "forbidden");

    ApiClient noToken = client();
    noToken.get("/api/auth/csrf");
    var csrf = noToken.postWithoutCsrf("/api/auth/login", "{}");
    assertHeaders("403 csrf", csrf);
    assertProblem("403 csrf", csrf, 403, "csrf");

    // Ruta anómala rechazada por el cortafuegos de Spring Security.
    var rejected = client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/%2e%2e/csrf"))
        .GET(), false);
    assertHeaders("cortafuegos", rejected);
    assertProblem("cortafuegos", rejected, 400, "bad_request");
  }

  @Test
  void erroresDeLosManejadoresYDeLaLimitacion() throws Exception {
    ApiClient client = client();
    client.get("/api/auth/csrf");
    var badJson = client.post("/api/auth/login", "no es json");
    assertHeaders("400", badJson);
    assertProblem("400", badJson, 400, "bad_request");

    var form = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
        .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("x")), true);
    assertHeaders("415", form);
    assertProblem("415", form, 415, "unsupported_media_type");

    String email = createUser(true, true);
    for (int i = 0; i < 5; i++) {
      client().login(email, "mal");
    }
    var limited = client().login(email, PASSWORD);
    assertHeaders("429", limited);
    assertProblem("429", limited, 429, "rate_limited");
    assertThat(limited.header("Retry-After")).isNotBlank();

    var wrong = client().login(createUser(true, true), "mal");
    assertHeaders("401 credenciales", wrong);
    assertProblem("401 credenciales", wrong, 401, "invalid_credentials");
  }

  @Test
  void erroresDeMvcYDelDespachoDeErrores() throws Exception {
    ApiClient editor = fullAccount("EDITOR").client();
    var notFound = editor.get("/api/cms/test/no-existe");
    assertHeaders("404", notFound);
    assertProblem("404", notFound, 404, "not_found");

    ApiClient admin = fullAccount("ADMIN").client();
    var method = admin.get("/api/cms/test/admin");
    assertHeaders("405", method);
    assertProblem("405", method, 405, "method_not_allowed");

    var boom = editor.get("/api/cms/test/boom");
    assertHeaders("500", boom);
    assertProblem("500", boom, 500, "internal_error");

    var sendError = client().get(SendErrorFilterConfig.PATH);
    assertHeaders("despacho de errores", sendError);
    assertProblem("despacho de errores", sendError, 503, "internal_error");
  }

  @Test
  void hstsNuncaPorHttpNiConXForwardedProtoDelCliente() throws Exception {
    var spoofed = client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/csrf"))
        .header("X-Forwarded-Proto", "https").GET(), false);
    assertThat(spoofed.status()).isEqualTo(200);
    assertThat(spoofed.raw().headers().firstValue("Strict-Transport-Security")).isEmpty();
  }
}
