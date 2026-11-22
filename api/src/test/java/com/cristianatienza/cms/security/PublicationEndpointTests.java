package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Autorización de /api/cms/publicaciones (fase 6.3.4): publicar y activar exigen ADMIN con passkey
 * reciente y CSRF; el listado, ADMIN. En este contexto la publicación no está configurada: una
 * petición autorizada llega al servicio y recibe 503 «publication_not_configured».
 */
@Import(SessionAbsoluteExpiryTests.ClockOverride.class)
class PublicationEndpointTests extends SecurityTestSupport {

  private static final String PUBLISH = "/api/cms/publicaciones";
  private static final String ACTIVATE = "/api/cms/publicaciones/v1/activar";

  @Autowired SessionAbsoluteExpiryTests.MutableClock clock;

  @Test
  void anonimoYSinCuentaCompleta() throws Exception {
    ApiClient anonymous = client();
    anonymous.get("/api/auth/csrf");
    assertThat(anonymous.post(PUBLISH, "{}").status()).isEqualTo(401);
    assertThat(anonymous.post(ACTIVATE, "{}").status()).isEqualTo(401);
    assertThat(anonymous.get(PUBLISH).status()).isEqualTo(401);

    ApiClient p1 = client();
    p1.login(createUser(true, true), PASSWORD);
    assertThat(p1.post(PUBLISH, "{}").status()).isEqualTo(403);
    assertThat(p1.get(PUBLISH).status()).isEqualTo(403);
  }

  @Test
  void editorNoPublicaNiVeElListado() throws Exception {
    ApiClient editor = fullAccount("EDITOR").client();
    assertThat(editor.post(PUBLISH, "{}").status()).isEqualTo(403);
    assertThat(editor.post(ACTIVATE, "{}").status()).isEqualTo(403);
    assertThat(editor.get(PUBLISH).status()).isEqualTo(403);
  }

  @Test
  void adminConPasskeyRecienteLlegaAlServicio() throws Exception {
    ApiClient admin = fullAccount("ADMIN").client();
    var published = admin.post(PUBLISH, "{}");
    assertThat(published.status()).isEqualTo(503);
    assertThat(published.body()).contains("\"code\":\"publication_not_configured\"");
    assertThat(admin.post(ACTIVATE, "{}").status()).isEqualTo(503);
    var list = admin.get(PUBLISH);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body()).isEqualTo("{\"configurada\":false}");
  }

  @Test
  void adminSinPasskeyRecienteOSinCsrf() throws Exception {
    ApiClient admin = fullAccount("ADMIN").client();
    var withoutCsrf = admin.postWithoutCsrf(PUBLISH, "{}");
    assertThat(withoutCsrf.status()).isEqualTo(403);
    assertThat(withoutCsrf.body()).contains("\"code\":\"csrf\"");
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    var stale = admin.post(PUBLISH, "{}");
    assertThat(stale.status()).isEqualTo(403);
    assertThat(stale.body()).contains("\"code\":\"forbidden\"");
    assertThat(admin.post(ACTIVATE, "{}").status()).isEqualTo(403);
    // El listado solo lee: sin passkey reciente.
    assertThat(admin.get(PUBLISH).status()).isEqualTo(200);
  }

  @Test
  void laVersionDeLaRutaSoloAdmiteVN() throws Exception {
    ApiClient admin = fullAccount("ADMIN").client();
    for (String version : new String[] {"1", "v0", "v01", "V1", "v1x", "v1234567890", "%2e%2e", "v1%00"}) {
      int status = admin.post("/api/cms/publicaciones/" + version + "/activar", "{}").status();
      assertThat(status).as(version).isIn(400, 404);
    }
  }
}
