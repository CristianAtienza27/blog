package com.cristianatienza.cms;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.testsupport.ApiDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * /actuator/health por HTTP real: estado general y de la base de datos en «UP», sin detalles
 * internos, y ningún otro endpoint de gestión expuesto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    ApiDatabase.register(registry);
  }

  @Value("${local.server.port}")
  int port;

  @Autowired PathMappedEndpoints endpoints;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void saludUpConLaBaseDeDatos() throws Exception {
    HttpResponse<String> response = get("/actuator/health");
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode health = JsonMapper.builder().build().readTree(response.body());
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
    // Sin detalles: ni el nombre del motor ni la consulta de validación.
    assertThat(health.path("components").path("db").has("details")).isFalse();
  }

  /**
   * Sondas (6.2.7.2): públicas y sin detalles. Liveness solo mira la JVM (ningún componente de base
   * de datos); readiness incluye PostgreSQL. Solo GET: el resto del actuador sigue cerrado.
   */
  @Test
  void sondasLivenessYReadiness() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    HttpResponse<String> liveness = get("/actuator/health/liveness");
    assertThat(liveness.statusCode()).isEqualTo(200);
    JsonNode live = json.readTree(liveness.body());
    assertThat(live.path("status").asString()).isEqualTo("UP");
    assertThat(live.path("components").has("db")).as("liveness no depende de PostgreSQL").isFalse();

    HttpResponse<String> readiness = get("/actuator/health/readiness");
    assertThat(readiness.statusCode()).isEqualTo(200);
    JsonNode ready = json.readTree(readiness.body());
    assertThat(ready.path("status").asString()).isEqualTo("UP");
    assertThat(ready.path("components").path("db").path("status").asString()).isEqualTo("UP");
    assertThat(ready.path("components").path("db").has("details")).isFalse();

    HttpRequest post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/liveness"))
        .POST(HttpRequest.BodyPublishers.noBody()).build();
    assertThat(http.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
    assertThat(get("/actuator/health/db").statusCode()).as("otros grupos o componentes, cerrados").isEqualTo(401);
  }

  @Test
  void ningunOtroEndpointDeGestionExpuesto() throws Exception {
    // Desde la 6.2.2 la seguridad deniega antes de llegar al enrutador (401 sin sesión, en lugar
    // de 404); lo que se comprueba es que el actuador solo publica el endpoint de salud.
    for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator"}) {
      assertThat(get(path).statusCode()).as(path).isEqualTo(401);
    }
    assertThat(endpoints.getAllPaths()).containsExactly("/actuator/health");
  }
}
