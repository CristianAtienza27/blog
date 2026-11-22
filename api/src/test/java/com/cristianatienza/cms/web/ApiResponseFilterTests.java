package com.cristianatienza.cms.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * El filtro directamente, en el hilo del test: el MDC lleva el identificador solo durante la
 * petición y después se restaura (con hilos de plataforma, un identificador olvidado se colaría
 * en lo siguiente que registrara el hilo, como una tarea programada).
 */
class ApiResponseFilterTests {

  @Test
  void elMdcSeLimpiaYSeRestauraTrasLaPeticion() throws Exception {
    ApiResponseFilter filter = new ApiResponseFilter();
    AtomicReference<String> during = new AtomicReference<>();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/csrf"), response,
        (req, res) -> during.set(MDC.get(ApiResponseFilter.MDC_KEY)));
    assertThat(during.get()).isEqualTo(response.getHeader(ApiResponseFilter.CORRELATION_HEADER)).isNotBlank();
    assertThat(MDC.get(ApiResponseFilter.MDC_KEY)).as("limpio después").isNull();

    MDC.put(ApiResponseFilter.MDC_KEY, "valor-anterior");
    try {
      filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/csrf"), new MockHttpServletResponse(), (req, res) -> {});
      assertThat(MDC.get(ApiResponseFilter.MDC_KEY)).as("restaurado").isEqualTo("valor-anterior");
    } finally {
      MDC.remove(ApiResponseFilter.MDC_KEY);
    }
  }

  @Test
  void hstsSoloSiLaPeticionEsSegura() throws Exception {
    ApiResponseFilter filter = new ApiResponseFilter();
    MockHttpServletResponse plain = new MockHttpServletResponse();
    MockHttpServletRequest http = new MockHttpServletRequest("GET", "/api/auth/csrf");
    http.addHeader("X-Forwarded-Proto", "https");
    filter.doFilter(http, plain, (req, res) -> {});
    assertThat(plain.getHeader("Strict-Transport-Security")).isNull();
    MockHttpServletRequest https = new MockHttpServletRequest("GET", "/api/auth/csrf");
    https.setSecure(true);
    MockHttpServletResponse secure = new MockHttpServletResponse();
    filter.doFilter(https, secure, (req, res) -> {});
    assertThat(secure.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
  }
}
