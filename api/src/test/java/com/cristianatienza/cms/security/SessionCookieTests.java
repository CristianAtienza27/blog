package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Atributos exactos de las cookies __Host- (requisitos del prefijo y del diseño aprobado). */
class SessionCookieTests extends SecurityTestSupport {

  private static List<String> attributes(String setCookie) {
    return Arrays.stream(setCookie.split(";")).skip(1).map(String::strip).map(a -> a.toLowerCase(Locale.ROOT)).toList();
  }

  @Test
  void cookieDeSesion() throws Exception {
    String email = createUser(true, true);
    var response = client().login(email, PASSWORD);
    String cookie = response.setCookie(SecurityConfig.SESSION_COOKIE);
    assertThat(cookie).as("Set-Cookie de la sesión").isNotNull();
    List<String> attributes = attributes(cookie);
    assertThat(attributes).contains("secure", "httponly", "path=/", "samesite=strict");
    assertThat(attributes).noneMatch(a -> a.startsWith("domain="));
    // __Host-: exige Secure, Path=/ y ausencia de Domain (RFC 6265bis).
    assertThat(cookie).startsWith("__Host-SESSION=");
  }

  @Test
  void cookieCsrf() throws Exception {
    var response = client().get("/api/auth/csrf");
    String cookie = response.setCookie(SecurityConfig.CSRF_COOKIE);
    assertThat(cookie).as("Set-Cookie del token CSRF").isNotNull();
    List<String> attributes = attributes(cookie);
    assertThat(attributes).contains("secure", "path=/", "samesite=strict");
    assertThat(attributes).noneMatch(a -> a.startsWith("domain="));
    // El CMS (mismo origen) debe poder leerla para enviarla en X-XSRF-TOKEN.
    assertThat(attributes).doesNotContain("httponly");
    assertThat(response.body()).contains("\"headerName\":\"X-XSRF-TOKEN\"");
  }

  @Test
  void sinSesionNoSeCreaCookieDeSesion() throws Exception {
    // Las peticiones anónimas (CSRF, estado de salud, rechazos) no crean sesiones.
    ApiClient client = client();
    assertThat(client.get("/api/auth/csrf").setCookie(SecurityConfig.SESSION_COOKIE)).isNull();
    assertThat(client.get("/actuator/health").setCookie(SecurityConfig.SESSION_COOKIE)).isNull();
    assertThat(client.get("/api/auth/me").setCookie(SecurityConfig.SESSION_COOKIE)).isNull();
  }
}
