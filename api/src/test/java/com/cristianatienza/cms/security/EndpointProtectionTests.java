package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.SessionAbsoluteExpiryTests.MutableClock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Matriz de autorización (6.2.3): todo endpoint registrado debe estar clasificado, y cada estado
 * real (A, P1, P2, R, F de ADMIN y de EDITOR, y F sin verificación reciente) solo llega a lo
 * permitido. Un endpoint nuevo sin clasificar hace fallar el test. Ningún camino da acceso completo
 * sin contraseña y passkey del mismo usuario.
 */
@Import(SessionAbsoluteExpiryTests.ClockOverride.class)
class EndpointProtectionTests extends SecurityTestSupport {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Autowired MutableClock clock;

  private static final List<String> STATES = List.of("A", "P1", "P2", "R", "F", "F-EDITOR", "F-ANTIGUA");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Estado → «pasa» (la seguridad deja pasar: cualquier cosa salvo 401/403) o el código esperado. */
  private static final Map<String, Map<String, String>> MATRIX = new LinkedHashMap<>();

  private static void route(String route, String a, String p1, String p2, String r, String f, String fStale) {
    route(route, a, p1, p2, r, f, f, fStale);
  }

  /** Con un resultado distinto para F de ADMIN y F de EDITOR (endpoints de @CmsAdmin). */
  private static void route(String route, String a, String p1, String p2, String r, String fAdmin, String fEditor,
      String fStale) {
    MATRIX.put(route, Map.of("A", a, "P1", p1, "P2", p2, "R", r, "F", fAdmin, "F-EDITOR", fEditor, "F-ANTIGUA", fStale));
  }

  static {
    String ok = "pasa";
    //                                                     A      P1     P2     R      F      F sin verificación reciente
    route("GET /api/auth/csrf",                            ok,    ok,    ok,    ok,    ok,    ok);
    route("POST /api/auth/login",                          ok,    ok,    ok,    ok,    ok,    ok);
    route("GET /api/auth/me",                              "401", ok,    ok,    ok,    ok,    ok);
    route("POST /api/auth/logout",                         "401", ok,    ok,    ok,    ok,    ok);
    route("POST /api/auth/passkeys/registration/options",  "401", ok,    "403", ok,    ok,    "403");
    route("POST /api/auth/passkeys/registration",          "401", ok,    "403", ok,    ok,    "403");
    route("POST /api/auth/passkeys/authentication/options", "401", "403", ok,   "403", "403", "403");
    route("POST /api/auth/passkeys/authentication",        "401", "403", ok,    "403", "403", "403");
    route("POST /api/auth/recovery",                       "401", "403", ok,    "403", "403", "403");
    route("POST /api/auth/recovery-codes",                 "401", "403", "403", "403", ok,    "403");
    route("POST /api/auth/step-up/options",                "401", "403", "403", "403", ok,    ok);
    route("POST /api/auth/step-up",                        "401", "403", "403", "403", ok,    ok);
    route("GET /api/auth/passkeys",                        "401", "403", "403", "403", ok,    ok);
    route("DELETE /api/auth/passkeys/{id}",                "401", "403", "403", "403", ok,    "403");
    route("POST /api/auth/sessions/revoke-all",            "401", "403", "403", "403", ok,    ok);
    route("GET /api/auth/devices",                         "401", "403", "403", "403", ok,    ok);
    route("DELETE /api/auth/devices/{id}",                 "401", "403", "403", "403", ok,    ok);
    // Publicación (6.3.4). F de ADMIN «pasa» (aquí la publicación no está configurada: 503); EDITOR, 403.
    //                                                       A      P1     P2     R      F admin F editor F sin verificación reciente
    route("POST /api/cms/publicaciones",                     "401", "403", "403", "403", ok,    "403",   "403");
    route("POST /api/cms/publicaciones/{version}/activar",   "401", "403", "403", "403", ok,    "403",   "403");
    route("GET /api/cms/publicaciones",                      "401", "403", "403", "403", ok,    "403",   ok);
    // Lectura editorial (6.4.2): ADMIN y EDITOR con acceso completo, sin verificación reciente.
    //                                                       A      P1     P2     R      F      F sin verificación reciente
    route("GET /api/cms/articulos",                          "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/articulos/{id}",                     "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/articulos/{id}/revisiones",          "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/articulos/{id}/revisiones/{numero}", "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/categorias",                         "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/etiquetas",                          "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/autores",                            "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/lugares",                            "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/itinerarios",                        "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/proyectos",                          "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/imagenes",                           "401", "403", "403", "403", ok,    ok);
    route("GET /api/cms/galerias",                           "401", "403", "403", "403", ok,    ok);
    // Escrituras editoriales (6.4.3): ADMIN y EDITOR con acceso completo (los metadatos de un publicado,
    // solo ADMIN: lo decide el servicio, con el artículo).
    route("POST /api/cms/articulos",                         "401", "403", "403", "403", ok,    ok);
    route("POST /api/cms/articulos/validacion",              "401", "403", "403", "403", ok,    ok);
    route("PUT /api/cms/articulos/{id}/metadatos",           "401", "403", "403", "403", ok,    ok);
    route("POST /api/cms/articulos/{id}/revisiones",         "401", "403", "403", "403", ok,    ok);
    // Restaurar una revisión (6.4.4): ADMIN y EDITOR con acceso completo.
    route("POST /api/cms/articulos/{id}/revisiones/{numero}/restaurar", "401", "403", "403", "403", ok, ok);
    // Publicación de artículos y orden de las secciones (6.4.5): solo ADMIN (@CmsAdmin, D3), sin verificación reciente.
    //                                                       A      P1     P2     R      F admin F editor F sin verificación reciente
    route("POST /api/cms/articulos/{id}/publicacion",        "401", "403", "403", "403", ok,    "403",   ok);
    route("POST /api/cms/articulos/{id}/despublicacion",     "401", "403", "403", "403", ok,    "403",   ok);
    route("PUT /api/cms/secciones/{id}/orden",               "401", "403", "403", "403", ok,    "403",   ok);
    // Etiquetas (6.4.6): crear y editar, ADMIN y EDITOR; borrar, solo ADMIN.
    route("POST /api/cms/etiquetas",                         "401", "403", "403", "403", ok,    ok);
    route("PUT /api/cms/etiquetas/{id}",                     "401", "403", "403", "403", ok,    ok);
    route("DELETE /api/cms/etiquetas/{id}",                  "401", "403", "403", "403", ok,    "403",   ok);
    // Autores, lugares, itinerarios y proyectos (6.4.7): leer, crear y editar, ADMIN y EDITOR; borrar, solo ADMIN.
    route("GET /api/cms/autores/{id}", "401", "403", "403", "403", ok, ok);
    route("POST /api/cms/autores", "401", "403", "403", "403", ok, ok);
    route("PUT /api/cms/autores/{id}", "401", "403", "403", "403", ok, ok);
    route("DELETE /api/cms/autores/{id}", "401", "403", "403", "403", ok, "403", ok);
    route("GET /api/cms/lugares/{id}", "401", "403", "403", "403", ok, ok);
    route("POST /api/cms/lugares", "401", "403", "403", "403", ok, ok);
    route("PUT /api/cms/lugares/{id}", "401", "403", "403", "403", ok, ok);
    route("DELETE /api/cms/lugares/{id}", "401", "403", "403", "403", ok, "403", ok);
    route("GET /api/cms/itinerarios/{id}", "401", "403", "403", "403", ok, ok);
    route("POST /api/cms/itinerarios", "401", "403", "403", "403", ok, ok);
    route("PUT /api/cms/itinerarios/{id}", "401", "403", "403", "403", ok, ok);
    route("DELETE /api/cms/itinerarios/{id}", "401", "403", "403", "403", ok, "403", ok);
    route("GET /api/cms/proyectos/{id}", "401", "403", "403", "403", ok, ok);
    route("POST /api/cms/proyectos", "401", "403", "403", "403", ok, ok);
    route("PUT /api/cms/proyectos/{id}", "401", "403", "403", "403", ok, ok);
    route("DELETE /api/cms/proyectos/{id}", "401", "403", "403", "403", ok, "403", ok);
  }

  @Test
  void todosLosEndpointsDeLaApiEstanClasificados() {
    TreeSet<String> registered = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      for (var method : info.getMethodsCondition().getMethods()) {
        for (String path : info.getPatternValues()) {
          registered.add(method + " " + path);
        }
      }
    }
    // Los de la aplicación (los de Spring, como /error, no tienen método HTTP fijo).
    assertThat(registered).isEqualTo(new TreeSet<>(MATRIX.keySet()));
  }

  /** Un cliente en ese estado, siempre por el camino real (contraseña, passkey, código). */
  private ApiClient as(String state) throws Exception {
    switch (state) {
      case "A" -> {
        ApiClient client = client();
        client.get("/api/auth/csrf");
        return client;
      }
      case "P1" -> {
        ApiClient client = client();
        client.login(createUser(true, true), PASSWORD);
        return client;
      }
      case "P2" -> {
        String email = createUser(true, true);
        ApiClient first = client();
        first.login(email, PASSWORD);
        enroll(first);
        return passwordSession(email);
      }
      case "R" -> {
        Account account = fullAccount();
        var codes = account.client().post("/api/auth/recovery-codes", null);
        String code = JSON.readTree(codes.body()).path("codes").get(0).asString();
        ApiClient client = passwordSession(account.email());
        assertThat(client.post("/api/auth/recovery", "{\"code\": \"" + code + "\"}").body())
            .isEqualTo("{\"state\":\"RECOVERY\"}");
        return client;
      }
      case "F" -> {
        return fullAccount("ADMIN").client();
      }
      case "F-EDITOR" -> {
        return fullAccount("EDITOR").client();
      }
      case "F-ANTIGUA" -> {
        ApiClient client = fullAccount("ADMIN").client();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        return client;
      }
      default -> throw new IllegalArgumentException(state);
    }
  }

  @Test
  void cadaEstadoSoloLlegaALoPermitido() throws Exception {
    for (String state : STATES) {
      for (var entry : MATRIX.entrySet()) {
        ApiClient client = as(state);
        String[] route = entry.getKey().split(" ");
        String path = route[1].replace("{id}", UUID.randomUUID().toString()).replace("{version}", "v1")
            .replace("{numero}", "1");
        // Cuerpo vacío y válido para la seguridad: lo que se comprueba es si llega al controlador.
        var response = switch (route[0]) {
          case "GET" -> client.get(path);
          case "DELETE" -> client.delete(path);
          case "PUT" -> client.put(path, "{}");
          default -> client.post(path, "{}");
        };
        String expected = entry.getValue().get(state);
        if (expected.equals("pasa")) {
          assertThat(response.status()).as(state + " " + entry.getKey()).isNotIn(401, 403);
        } else {
          assertThat(response.status()).as(state + " " + entry.getKey()).isEqualTo(Integer.parseInt(expected));
        }
      }
      // Cualquier otra ruta: denegada (401 sin sesión, 403 con ella), también las de Spring WebAuthn.
      ApiClient client = as(state);
      int expectedOther = state.equals("A") ? 401 : 403;
      for (String other : List.of("/api/otra", "/webauthn/register/options", "/login/webauthn",
          "/webauthn/authenticate/options", "/")) {
        assertThat(client.post(other, "{}").status()).as(state + " POST " + other).isEqualTo(expectedOther);
      }
      assertThat(client.get("/api/otra").status()).as(state + " GET /api/otra").isEqualTo(expectedOther);
      assertThat(client.get("/webauthn/register").status()).as(state + " GET /webauthn/register").isEqualTo(expectedOther);
    }
  }

  @Test
  void sinCorsNiCabecerasDeOtrosOrigenes() throws Exception {
    ApiClient client = client();
    var preflight = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
            + "/api/auth/login"))
        .method("OPTIONS", java.net.http.HttpRequest.BodyPublishers.noBody())
        .header("Origin", "https://otro-origen.example")
        .header("Access-Control-Request-Method", "POST"), false);
    var get = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
        + "/api/auth/csrf")).header("Origin", "https://otro-origen.example").GET(), false);
    for (var response : List.of(preflight, get)) {
      assertThat(response.raw().headers().map().keySet()).noneMatch(h -> h.toLowerCase().startsWith("access-control-"));
    }
  }
}
