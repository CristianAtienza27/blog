package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ciclo de vida de la sesión: identificador nuevo, cierre y revocación de todas. */
class SessionLifecycleTests extends SecurityTestSupport {

  @Test
  void cadaInicioDeSesionDaUnIdentificadorNuevoYAnulaElAnterior() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    String first = client.cookie(SecurityConfig.SESSION_COOKIE);
    // Volver a iniciar sesión con la sesión abierta: identificador nuevo (contra la fijación).
    client.post("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}");
    String second = client.cookie(SecurityConfig.SESSION_COOKIE);
    assertThat(second).isNotBlank().isNotEqualTo(first);
    assertThat(sessionRows(sessionId(first))).isZero();
    // El identificador antiguo ya no sirve.
    ApiClient old = client();
    old.cookies().put(SecurityConfig.SESSION_COOKIE, first);
    assertThat(old.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(client.get("/api/auth/me").status()).isEqualTo(200);
  }

  @Test
  void cerrarSesionBorraLaSesionYCaducaLaCookie() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    String cookie = client.cookie(SecurityConfig.SESSION_COOKIE);
    String csrfBefore = client.cookie(SecurityConfig.CSRF_COOKIE);
    assertThat(sessionRows(sessionId(cookie))).isEqualTo(1);

    var logout = client.post("/api/auth/logout", null);
    assertThat(logout.status()).isEqualTo(204);
    assertThat(logout.setCookie(SecurityConfig.SESSION_COOKIE)).contains("Max-Age=0");
    assertThat(sessionRows(sessionId(cookie))).isZero();
    assertThat(client.cookie(SecurityConfig.CSRF_COOKIE)).isNotBlank().isNotEqualTo(csrfBefore);
    // Ni con la cookie antigua.
    ApiClient old = client();
    old.cookies().put(SecurityConfig.SESSION_COOKIE, cookie);
    assertThat(old.get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void sinSesionNoSePuedeCerrarSesion() throws Exception {
    ApiClient client = client();
    client.get("/api/auth/csrf");
    assertThat(client.post("/api/auth/logout", null).status()).isEqualTo(401);
  }

  @Test
  void revocarTodasLasSesionesSoloEnFYCierraTambienLaActual() throws Exception {
    String email = createUser(true, false);
    ApiClient first = client();
    ApiClient second = client();
    first.login(email, PASSWORD);
    second.login(email, PASSWORD);
    // En P2 (sin passkey) no puede.
    assertThat(first.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(403);

    promoteToFull(first.cookie(SecurityConfig.SESSION_COOKIE));
    assertThat(first.get("/api/auth/me").body()).startsWith("{\"state\":\"AUTHENTICATED\"");
    String firstId = sessionId(first.cookie(SecurityConfig.SESSION_COOKIE));
    String secondId = sessionId(second.cookie(SecurityConfig.SESSION_COOKIE));

    assertThat(first.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(204);
    assertThat(sessionRows(firstId)).isZero();
    assertThat(sessionRows(secondId)).isZero();
    assertThat(second.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(first.get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void laRevocacionNoAfectaALasSesionesDeOtroUsuario() throws Exception {
    String mine = createUser(true, false);
    String other = createUser(true, false);
    ApiClient me = client();
    ApiClient them = client();
    me.login(mine, PASSWORD);
    them.login(other, PASSWORD);
    promoteToFull(me.cookie(SecurityConfig.SESSION_COOKIE));
    assertThat(me.post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(204);
    assertThat(them.get("/api/auth/me").status()).isEqualTo(200);
  }
}
