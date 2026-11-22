package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Saturación de Argon2id por HTTP: con un solo permiso y una espera mínima, las peticiones que no
 * consiguen hueco reciben 429 con Retry-After (comportamiento esperado, no un fallo) y ninguna un 5xx.
 */
@TestPropertySource(properties = {"cms.security.argon2.max-concurrent=1", "cms.security.argon2.max-wait=1ms"})
class LoginSaturationTests extends SecurityTestSupport {

  @Test
  void lasPeticionesSinHuecoRecibenUn429() throws Exception {
    int parallel = 12;
    CountDownLatch go = new CountDownLatch(1);
    List<Future<ApiClient.Response>> futures = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(parallel)) {
      for (int i = 0; i < parallel; i++) {
        ApiClient client = client();
        client.get("/api/auth/csrf");
        int n = i;
        futures.add(executor.submit(() -> {
          go.await();
          return client.post("/api/auth/login", "{\"email\": \"nadie-" + n + "@example.test\", \"password\": \"x\"}");
        }));
      }
      go.countDown();
      List<ApiClient.Response> responses = new ArrayList<>();
      for (var f : futures) {
        responses.add(f.get());
      }
      List<Integer> statuses = responses.stream().map(ApiClient.Response::status).toList();
      assertThat(statuses).allMatch(s -> s == 401 || s == 429).contains(401, 429);
      for (var r : responses) {
        if (r.status() == 429) {
          assertThat(r.header("Retry-After")).isEqualTo("1");
          assertThat(r.body()).contains("demasiadas solicitudes");
        }
      }
    }
  }
}
