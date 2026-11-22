package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import cmstest.CmsTestControllers;
import com.cristianatienza.cms.security.SessionAbsoluteExpiryTests.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Política de autorización del CMS (G3) con controladores de pruebas: los 7 estados reales contra
 * @CmsEditor, @CmsAdmin y @CmsAdmin + @RecentPasskey; el comprobador arquitectónico detecta un
 * endpoint sin política; y el 500 genérico y la correlación (también con 100 peticiones simultáneas).
 */
@Import({CmsTestControllers.Annotated.class, CmsTestControllers.Unannotated.class,
    SessionAbsoluteExpiryTests.ClockOverride.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationPolicyTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Autowired MutableClock clock;

  private ApiClient as(String state) throws Exception {
    return switch (state) {
      case "A" -> {
        ApiClient c = client();
        c.get("/api/auth/csrf");
        yield c;
      }
      case "P1" -> {
        ApiClient c = client();
        c.login(createUser(true, true), PASSWORD);
        yield c;
      }
      case "P2" -> {
        String email = createUser(true, true);
        ApiClient first = client();
        first.login(email, PASSWORD);
        enroll(first);
        yield passwordSession(email);
      }
      case "R" -> {
        Account account = fullAccount();
        String code = JSON.readTree(account.client().post("/api/auth/recovery-codes", null).body())
            .path("codes").get(0).asString();
        ApiClient c = passwordSession(account.email());
        c.post("/api/auth/recovery", "{\"code\": \"" + code + "\"}");
        yield c;
      }
      case "F-EDITOR" -> fullAccount("EDITOR").client();
      case "F-ADMIN" -> fullAccount("ADMIN").client();
      case "F-ADMIN-ANTIGUA" -> {
        ApiClient c = fullAccount("ADMIN").client();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        yield c;
      }
      default -> throw new IllegalArgumentException(state);
    };
  }

  @Test
  void cadaEstadoContraCadaPolitica() throws Exception {
    // estado → [GET editor, POST admin, POST admin-recent]
    Map<String, int[]> expected = Map.of(
        "A", new int[] {401, 401, 401},
        "P1", new int[] {403, 403, 403},
        "P2", new int[] {403, 403, 403},
        "R", new int[] {403, 403, 403},
        "F-EDITOR", new int[] {200, 403, 403},
        "F-ADMIN", new int[] {200, 200, 200},
        "F-ADMIN-ANTIGUA", new int[] {200, 200, 403});
    for (var entry : expected.entrySet()) {
      int[] codes = entry.getValue();
      assertThat(as(entry.getKey()).get("/api/cms/test/editor").status()).as(entry.getKey() + " editor").isEqualTo(codes[0]);
      assertThat(as(entry.getKey()).post("/api/cms/test/admin", "{}").status()).as(entry.getKey() + " admin").isEqualTo(codes[1]);
      ApiClient client = as(entry.getKey());
      var recent = client.post("/api/cms/test/admin-recent", "{}");
      assertThat(recent.status()).as(entry.getKey() + " admin-recent").isEqualTo(codes[2]);
      if (codes[2] == 403) {
        assertThat(recent.body()).contains("\"code\":\"" + (recent.body().contains("csrf") ? "csrf" : "forbidden") + "\"");
      }
    }
  }

  @Test
  void elComprobadorDetectaUnEndpointSinPolitica() {
    assertThat(EndpointPolicy.violations(mappings.getHandlerMethods()))
        .containsExactly("[GET] /api/cms/test-unannotated");
  }

  @Test
  void unErrorInesperadoEsUn500GenericoConSuCorrelacion(CapturedOutput output) throws Exception {
    ApiClient client = fullAccount("EDITOR").client();
    var response = client.get("/api/cms/test/boom");
    assertThat(response.status()).isEqualTo(500);
    assertThat(response.header("Content-Type")).startsWith("application/problem+json");
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.path("code").asString()).isEqualTo("internal_error");
    assertThat(body.path("detail").asString()).isEqualTo("error interno");
    assertThat(body.path("correlationId").asString()).isEqualTo(response.header("X-Correlation-Id"));
    assertThat(response.body()).doesNotContain("secreto", "Secreto", "IllegalState", "Exception", "com.cristianatienza",
        "trace", "at ");
    // En el registro del servidor, con el mismo identificador de correlación.
    assertThat(output.getAll()).contains("[" + response.header("X-Correlation-Id") + "]");
  }

  @Test
  void cienPeticionesSimultaneasTienenCadaUnaSuIdentificadorSinMezclarse() throws Exception {
    ApiClient client = fullAccount("EDITOR").client();
    String cookies = client.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining("; "));
    HttpClient http = HttpClient.newHttpClient();
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
          return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cms/test/mdc"))
              .header("Cookie", cookies).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }));
    }
    start.countDown();
    Set<String> ids = new HashSet<>();
    for (var call : calls) {
      HttpResponse<String> response = call.join();
      assertThat(response.statusCode()).isEqualTo(200);
      String header = response.headers().firstValue("X-Correlation-Id").orElseThrow();
      // El MDC que vio el código es el de su propia petición.
      assertThat(JSON.readTree(response.body()).path("mdc").asString()).isEqualTo(header);
      ids.add(header);
    }
    assertThat(ids).hasSize(100);
  }

  @Test
  void laAuditoriaLlevaElMismoIdentificador() throws Exception {
    String email = createUser(true, true);
    var response = client().login(email, "mal");
    assertThat(response.status()).isEqualTo(401);
    assertThat(query("cms_security_tool", "SELECT details->>'correlationId' FROM audit_event WHERE user_id = ?"
        + " AND event = 'login.password_failed'", userIdOf(email))).containsExactly(response.header("X-Correlation-Id"));
  }
}
