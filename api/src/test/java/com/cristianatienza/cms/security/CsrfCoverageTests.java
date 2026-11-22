package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * CSRF en todos los métodos que cambian estado, y GET sin efectos (6.2.5). Se distingue el estado
 * de NEGOCIO y de SEGURIDAD (usuarios, passkeys, códigos, dispositivos, contadores, auditoría,
 * challenges y contenido: comparado antes y después) de los efectos internos legítimos: el último
 * acceso de Spring Session y la emisión de la cookie CSRF, que no se comparan.
 */
class CsrfCoverageTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  /** Huella del estado de negocio y de seguridad, leída como propietario (cms_migrator + SET ROLE). */
  private static String businessState() throws Exception {
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      try (ResultSet rs = s.executeQuery("SELECT concat_ws(' | ',"
          + " (SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') || ':' || coalesce(max(last_login_at)::text, '')"
          + "   || ':' || sum(enroll_required::int) || ':' || sum(enabled::int) FROM app_user),"
          + " (SELECT count(*) || ':' || coalesce(sum(sign_count), 0) || ':' || count(suspended_at) || ':'"
          + "   || coalesce(max(last_used_at)::text, '') FROM passkey_credential),"
          + " (SELECT count(*) || ':' || count(used_at) || ':' || count(invalidated_at) FROM recovery_code),"
          + " (SELECT count(*) FROM webauthn_user_handle), (SELECT count(*) FROM webauthn_challenge),"
          + " (SELECT count(*) FROM webauthn_assertion), (SELECT count(*) FROM audit_event),"
          + " (SELECT count(*) || ':' || coalesce(sum(failures), 0) FROM login_attempt),"
          + " (SELECT count(*) || ':' || coalesce(string_agg(state, ',' ORDER BY id), '') FROM trusted_device),"
          + " (SELECT count(*) FROM trusted_device_presentation),"
          + " (SELECT count(*) || ':' || coalesce(sum(failures), 0) FROM auth_session_failure),"
          + " (SELECT count(*) FROM article), (SELECT count(*) FROM article_revision),"
          // Publicación (6.3.4): versiones, época y reconciliación.
          + " (SELECT count(*) || ':' || coalesce(string_agg(state, ',' ORDER BY version), '') FROM content_publication),"
          + " (SELECT epoch FROM content_publication_lease), (SELECT status FROM content_publication_check))")) {
        rs.next();
        String state = rs.getString(1);
        c.rollback();
        return state;
      }
    }
  }

  /** Rutas registradas que cambian estado (todo salvo GET), con {id} sustituido. */
  private List<String[]> changingRoutes(Map<String, String> ids) {
    List<String[]> out = new ArrayList<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (RequestMethod method : info.getMethodsCondition().getMethods()) {
        if (method == RequestMethod.GET) {
          continue;
        }
        for (String path : info.getPatternValues()) {
          String concrete = path.replace("{version}", "v1").replace("{numero}", "1");
          for (var id : ids.entrySet()) {
            if (path.startsWith(id.getKey())) {
              concrete = concrete.replace("{id}", id.getValue());
            }
          }
          out.add(new String[] {method.name(), concrete});
        }
      }
    }
    return out;
  }

  @Test
  void ningunMetodoQueCambiaEstadoFuncionaSinElTokenCsrf() throws Exception {
    Account account = fullAccount();
    String passkeyId = JSON.readTree(account.client().get("/api/auth/passkeys").body()).get(0).path("id").asString();
    String deviceId = JSON.readTree(account.client().get("/api/auth/devices").body()).get(0).path("id").asString();
    List<String[]> routes = changingRoutes(Map.of("/api/auth/passkeys/", passkeyId, "/api/auth/devices/", deviceId,
        "/api/cms/articulos/", "alicante-desde-el-puerto", "/api/cms/secciones/", "viajes",
        "/api/cms/etiquetas/", "costa", "/api/cms/autores/", "cristian-atienza", "/api/cms/lugares/", "alicante",
        "/api/cms/itinerarios/", "costa-brava", "/api/cms/proyectos/", "revista-cristian-atienza"));
    // 13 de autenticación, 2 de publicación (6.3.4: POST /api/cms/publicaciones y …/vN/activar) y 23
    // editoriales (6.4.3: crear, validar, metadatos y guardar una revisión; 6.4.4: restaurar; 6.4.5:
    // publicar, despublicar y reordenar una sección; 6.4.6: crear, editar y borrar una etiqueta; 6.4.7:
    // crear, editar y borrar autores, lugares, itinerarios y proyectos).
    assertThat(routes).as("las 38 rutas que cambian estado (un endpoint nuevo exige revisar este test)").hasSize(38);
    for (ApiClient client : List.of(anonymous(), account.client())) {
      for (String[] route : routes) {
        String before = businessState();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + route[1]))
            .header("Content-Type", "application/json").method(route[0], HttpRequest.BodyPublishers.ofString("{}"));
        var response = client.send(request, false);
        assertThat(response.status()).as(route[0] + " " + route[1]).isEqualTo(403);
        assertThat(response.body()).as(route[0] + " " + route[1]).contains("\"code\":\"csrf\"");
        assertThat(businessState()).as("sin cambios: " + route[0] + " " + route[1]).isEqualTo(before);
      }
    }
    // Nada se ejecutó: la sesión F sigue viva (ni logout ni revoke-all) y la passkey y el dispositivo existen.
    assertThat(account.client().get("/api/auth/me").body()).startsWith("{\"state\":\"AUTHENTICATED\"");
  }

  @Test
  void ningunGetCambiaElEstadoDeNegocioNiDeSeguridad() throws Exception {
    List<String> gets = new ArrayList<>(List.of("/actuator/health"));
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      if (info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
        // Lecturas editoriales (6.4.2): un artículo real y su primera revisión.
        info.getPatternValues().forEach(path -> gets.add(
            path.replace("{id}", "alicante-desde-el-puerto").replace("{numero}", "1")));
      }
    }
    assertThat(gets).contains("/api/auth/csrf", "/api/auth/me", "/api/auth/passkeys", "/api/auth/devices");
    Account full = fullAccount();
    String email = createUser(true, true);
    ApiClient p1 = client();
    p1.login(email, PASSWORD);
    ApiClient p2 = passwordSession(full.email());
    for (ApiClient client : List.of(anonymous(), p1, p2, full.client())) {
      for (String path : gets) {
        String before = businessState();
        client.get(path);
        assertThat(businessState()).as("GET " + path).isEqualTo(before);
      }
    }
  }

  private ApiClient anonymous() throws Exception {
    ApiClient client = client();
    client.get("/api/auth/csrf");
    return client;
  }
}
