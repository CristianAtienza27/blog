package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentExporter;
import com.cristianatienza.cms.content.io.ContentImporter;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Autores, lugares, itinerarios y proyectos (fase 6.4.7) con la API real y PostgreSQL 18.6 con el
 * contenido real: el registro exacto del contrato, crear, editar y borrar; reglas de validate.ts e
 * integrity.ts; coordenadas; slug o id ocupado; xmin como token opaco; lo que una edición podría
 * romper (fotos propias de un autor, artículos que usan un itinerario); en uso; permisos por rol;
 * auditoría; y que la siguiente instantánea (6.3) lee los cambios.
 */
class CatalogEntityEndpointTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));
  private static final Map<String, String> PATHS = Map.of("authors", "autores", "destinations", "lugares",
      "trips", "itinerarios", "projects", "proyectos");

  private Account editor;
  private Account admin;

  @BeforeEach
  void freshContent() throws Exception {
    freshSchema();
    new ContentImporter(new DriverManagerDataSource(TestDatabase.url(), "cms_tool", TestDatabase.password("cms_tool")))
        .importContent(CONTENT, CodeUsages.read(Path.of("../src/app/editorial/retired-media.ts"),
            Path.of("../src/app/layout/navigation.ts")));
    editor = fullAccount("EDITOR");
    admin = fullAccount("ADMIN");
  }

  @AfterAll
  static void cleanSchema() {
    TestDatabase.migrateFromScratch();
  }

  // --- Apoyo -----------------------------------------------------------------------------------

  private static JsonNode json(ApiClient.Response response) {
    return JSON.readTree(response.body());
  }

  private static JsonNode source(String collection, String id) {
    for (JsonNode r : CONTENT.collections().get(collection).values()) {
      if (r.get("id").asString().equals(id)) {
        return r;
      }
    }
    throw new IllegalArgumentException(id);
  }

  private JsonNode detail(String path, String id) throws Exception {
    ApiClient.Response response = editor.client().get("/api/cms/" + path + "/" + id);
    assertThat(response.status()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  /** El registro para PUT: el del contrato sin id, más la versión. */
  private static ObjectNode forUpdate(JsonNode detail) {
    ObjectNode body = ((ObjectNode) detail.get("record")).deepCopy();
    body.remove("id");
    body.put("version", detail.get("version").asString());
    return body;
  }

  private ApiClient.Response delete(Account account, String path, String json) throws Exception {
    return account.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").method("DELETE", HttpRequest.BodyPublishers.ofString(json)), true);
  }

  private static String version(String version) {
    return "{\"version\": \"" + version + "\"}";
  }

  private static List<String> paths(ApiClient.Response response) {
    List<String> out = new ArrayList<>();
    json(response).path("errors").values().forEach(e -> out.add(e.get("path").asString()));
    return out;
  }

  private static String fingerprint() throws Exception {
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT concat_ws('|',"
            + " (SELECT string_agg(id || ':' || xmin::text, ',' ORDER BY id) FROM author),"
            + " (SELECT string_agg(id || ':' || xmin::text, ',' ORDER BY id) FROM destination),"
            + " (SELECT string_agg(id || ':' || xmin::text, ',' ORDER BY id) FROM trip),"
            + " (SELECT string_agg(id || ':' || xmin::text, ',' ORDER BY id) FROM project),"
            + " (SELECT count(*) FROM trip_stop), (SELECT count(*) FROM project_stack_item))")) {
      rs.next();
      return rs.getString(1) + "|" + auditCount();
    }
  }

  private static long auditCount() throws Exception {
    try (Connection c = connectAs("cms_security_tool"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM audit_event WHERE event LIKE 'content.%'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String[] lastAudit() throws Exception {
    try (Connection c = connectAs("cms_security_tool"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT event, user_id::text, details::text FROM audit_event"
            + " WHERE event LIKE 'content.%' ORDER BY occurred_at DESC LIMIT 1")) {
      return rs.next() ? new String[] {rs.getString(1), rs.getString(2), rs.getString(3)} : null;
    }
  }

  // --- Leer -------------------------------------------------------------------------------------

  @Test
  void elDetalleEsElRegistroExactoDelContratoConSuVersionYSuUso() throws Exception {
    for (var entry : PATHS.entrySet()) {
      for (JsonNode r : CONTENT.collections().get(entry.getKey()).values()) {
        String id = r.get("id").asString();
        JsonNode detail = detail(entry.getValue(), id);
        assertThat(detail.get("record")).as(entry.getKey() + " " + id).isEqualTo(r);
        assertThat(detail.get("version").asString()).matches("[0-9]+");
        assertThat(detail.get("usage").isObject()).isTrue();
      }
    }
    assertThat(detail("itinerarios", "costa-brava").get("usage").get("articles").asInt()).isEqualTo(1);
    assertThat(detail("proyectos", "revista-cristian-atienza").get("usage").get("articles").asInt()).isEqualTo(2);
    assertThat(detail("autores", "cristian-atienza").get("usage").get("articles").asInt()).isEqualTo(12);
    assertThat(editor.client().get("/api/cms/lugares/no-existe").status()).isEqualTo(404);
  }

  // --- Crear ------------------------------------------------------------------------------------

  @Test
  void crearUnoDeCadaColeccionConSuAuditoria() throws Exception {
    List<String[]> cases = List.of(
        new String[] {"autores", "author", "{\"id\": \"ana-lopez\", \"name\": \"Ana López\", \"role\": \"Fotógrafa\"}",
            "ana-lopez"},
        new String[] {"lugares", "destination", "{\"slug\": \"denia\", \"name\": \"Dénia\", \"country\": \"ES\","
            + " \"location\": {\"lat\": 38.8408, \"lng\": 0.1057}, \"elevation\": 15, \"summary\": \"Puerto y castillo.\","
            + " \"demo\": true}", "denia"},
        new String[] {"itinerarios", "trip", "{\"slug\": \"levante\", \"title\": \"Levante\", \"summary\": \"Dos ciudades.\","
            + " \"stops\": [{\"order\": 1, \"destination\": \"alicante\"}, {\"order\": 2, \"destination\": \"lisboa\","
            + " \"distanceKm\": 900.5, \"mode\": \"tren\", \"note\": \"Largo\"}], \"demo\": true}", "levante"},
        new String[] {"proyectos", "project", "{\"slug\": \"otro\", \"name\": \"Otro\", \"summary\": \"Uno más.\","
            + " \"status\": \"pausado\", \"year\": 2025, \"stack\": [\"angular\", \"ssr\"],"
            + " \"repositoryUrl\": \"https://example.com/otro\", \"demo\": false}", "otro"});
    for (String[] c : cases) {
      ApiClient.Response created = editor.client().post("/api/cms/" + c[0], c[2]);
      assertThat(created.status()).as(c[0] + " " + created.body()).isEqualTo(201);
      assertThat(created.header("Location")).isEqualTo("/api/cms/" + c[0] + "/" + c[3]);
      JsonNode record = json(created).get("record");
      ObjectNode expected = (ObjectNode) JSON.readTree(c[2]);
      expected.remove("slug");
      ObjectNode withId = JSON.createObjectNode().put("id", c[3]);
      if (!c[0].equals("autores")) {
        withId.put("slug", c[3]);
      }
      withId.setAll(expected);
      for (String key : withId.propertyNames()) {
        assertThat(record.get(key)).as(c[0] + "." + key).isEqualTo(withId.get(key));
      }
      String[] audit = lastAudit();
      assertThat(audit[0]).isEqualTo("content." + c[1] + "_created");
      assertThat(audit[1]).isEqualTo(userIdOf(editor.email()).toString());
    }
  }

  @Test
  void crearConDatosNoValidosEs422ConTodasLasIncidencias() throws Exception {
    String before = fingerprint();
    ApiClient.Response destination = editor.client().post("/api/cms/lugares", "{\"slug\": \"Mal\", \"name\": \"\","
        + " \"country\": \"es\", \"location\": {\"lat\": 91, \"lng\": 0}, \"elevation\": 1.5, \"summary\": \"x\","
        + " \"demo\": true, \"id\": \"x\", \"otro\": 1}");
    assertThat(destination.status()).isEqualTo(422);
    assertThat(paths(destination)).contains("id", "slug", "name", "country", "location.lat", "elevation", "otro");
    // Integridad: portada retirada, parada inexistente, etiqueta inexistente, avatar inexistente.
    assertThat(paths(editor.client().post("/api/cms/lugares", "{\"slug\": \"a\", \"name\": \"A\", \"country\": \"ES\","
        + " \"location\": {\"lat\": 1, \"lng\": 1}, \"summary\": \"x\", \"cover\": \"motor-moto-trail\", \"demo\": true}")))
        .containsExactly("cover");
    ApiClient.Response trip = editor.client().post("/api/cms/itinerarios", "{\"slug\": \"t\", \"title\": \"T\","
        + " \"summary\": \"x\", \"stops\": [{\"order\": 2, \"destination\": \"atlantida\"}, {\"order\": 1,"
        + " \"destination\": \"begur\"}], \"demo\": true}");
    assertThat(trip.status()).isEqualTo(422);
    assertThat(trip.body()).contains("destino inexistente «atlantida»").contains("orden 2, se esperaba 1");
    assertThat(paths(editor.client().post("/api/cms/proyectos", "{\"slug\": \"p\", \"name\": \"P\", \"summary\": \"x\","
        + " \"status\": \"activo\", \"year\": 2026, \"stack\": [\"no-existe\"], \"demo\": true}")))
        .containsExactly("stack[0]");
    assertThat(paths(editor.client().post("/api/cms/autores", "{\"id\": \"b\", \"name\": \"B\", \"role\": \"R\","
        + " \"avatar\": \"no-existe\"}"))).containsExactly("avatar");
    // Coordenadas: más de 6 decimales se rechazan (la base de datos redondearía).
    ApiClient.Response precise = editor.client().post("/api/cms/lugares", "{\"slug\": \"preciso\", \"name\": \"P\","
        + " \"country\": \"ES\", \"location\": {\"lat\": 38.1234567, \"lng\": -0.5}, \"summary\": \"x\", \"demo\": true}");
    assertThat(precise.status()).isEqualTo(422);
    assertThat(paths(precise)).containsExactly("location.lat");
    // La divergencia documentada: Java rechaza la clave constructor.
    assertThat(paths(editor.client().post("/api/cms/autores", "{\"id\": \"c\", \"name\": \"C\", \"role\": \"R\","
        + " \"constructor\": 1}"))).containsExactly("constructor");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void slugOIdOcupadoEs409() throws Exception {
    assertThat(json(editor.client().post("/api/cms/autores", "{\"id\": \"cristian-atienza\", \"name\": \"X\","
        + " \"role\": \"Y\"}")).get("code").asString()).isEqualTo("id_taken");
    assertThat(json(editor.client().post("/api/cms/lugares", "{\"slug\": \"alicante\", \"name\": \"X\","
        + " \"country\": \"ES\", \"location\": {\"lat\": 1, \"lng\": 1}, \"summary\": \"x\", \"demo\": true}"))
        .get("code").asString()).isEqualTo("slug_taken");
    // Un slug cambiado no libera el id.
    ObjectNode renamed = forUpdate(detail("proyectos", "revista-cristian-atienza"));
    renamed.put("slug", "la-revista");
    assertThat(editor.client().put("/api/cms/proyectos/revista-cristian-atienza", renamed.toString()).status())
        .isEqualTo(200);
    assertThat(json(editor.client().post("/api/cms/proyectos", "{\"slug\": \"revista-cristian-atienza\","
        + " \"name\": \"P\", \"summary\": \"x\", \"status\": \"activo\", \"year\": 2026, \"stack\": [], \"demo\": true}"))
        .get("code").asString()).isEqualTo("slug_taken");
  }

  // --- Editar -----------------------------------------------------------------------------------

  @Test
  void editarConVersionOpacaSinCambiarElIdYLaInstantaneaLoLee() throws Exception {
    JsonNode before = detail("lugares", "alicante");
    ObjectNode change = forUpdate(before);
    change.put("name", "Alacant").put("slug", "alacant");
    ApiClient.Response updated = editor.client().put("/api/cms/lugares/alicante", change.toString());
    assertThat(updated.status()).as(updated.body()).isEqualTo(200);
    JsonNode after = json(updated);
    assertThat(after.get("record").get("id").asString()).isEqualTo("alicante");
    assertThat(after.get("record").get("name").asString()).isEqualTo("Alacant");
    assertThat(after.get("version").asString()).isNotEqualTo(before.get("version").asString());
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.destination_updated");
    assertThat(JSON.readTree(audit[2]).get("fields").asString()).isEqualTo("slug,name");
    // La lectura de la 6.3 (P2) ya lo exporta así.
    var exported = ContentExporter.readPublished(
        new DriverManagerDataSource(TestDatabase.url(), "cms_api", TestDatabase.password("cms_api")), null);
    for (JsonNode d : exported.collections().get("destinations").values()) {
      if (d.get("id").asString().equals("alicante")) {
        assertThat(d.get("name").asString()).isEqualTo("Alacant");
      }
    }
    // Versión vieja: 409 con la actual. Sin cambios: 200, la misma versión y sin auditoría.
    ApiClient.Response stale = editor.client().put("/api/cms/lugares/alicante", change.toString());
    assertThat(stale.status()).isEqualTo(409);
    assertThat(json(stale).get("currentVersion").asString()).isEqualTo(after.get("version").asString());
    long audits = auditCount();
    ApiClient.Response same = editor.client().put("/api/cms/lugares/alicante", forUpdate(after).toString());
    assertThat(json(same).get("version").asString()).isEqualTo(after.get("version").asString());
    assertThat(auditCount()).isEqualTo(audits);
    // Versión como número o sin ella, y el id en el cuerpo: 422.
    ObjectNode numeric = forUpdate(after);
    numeric.put("version", 1);
    numeric.put("id", "otro");
    assertThat(paths(editor.client().put("/api/cms/lugares/alicante", numeric.toString())))
        .containsExactlyInAnyOrder("version", "id");
  }

  @Test
  void cambiarSoloLasParadasOLaPilaTambienDaUnaVersionNueva() throws Exception {
    JsonNode trip = detail("itinerarios", "stelvio");
    ObjectNode stops = forUpdate(trip);
    ((ObjectNode) stops.get("stops").get(0)).put("note", "Salida temprano");
    ApiClient.Response updated = editor.client().put("/api/cms/itinerarios/stelvio", stops.toString());
    assertThat(updated.status()).as(updated.body()).isEqualTo(200);
    assertThat(json(updated).get("version").asString()).isNotEqualTo(trip.get("version").asString());
    assertThat(json(updated).get("record").get("stops").get(0).get("note").asString()).isEqualTo("Salida temprano");
    JsonNode project = detail("proyectos", "revista-cristian-atienza");
    ObjectNode stack = forUpdate(project);
    ((ArrayNode) stack.get("stack")).remove(3);
    ApiClient.Response changed = editor.client().put("/api/cms/proyectos/revista-cristian-atienza", stack.toString());
    assertThat(json(changed).get("record").get("stack").size()).isEqualTo(3);
    assertThat(json(changed).get("version").asString()).isNotEqualTo(project.get("version").asString());
  }

  @Test
  void cambiarParadasQueRompenUnArticuloEs409ConLosArticulos() throws Exception {
    // itinerario-costa-brava tiene un capítulo por parada: quitar begur deja su capítulo sin parada.
    JsonNode trip = detail("itinerarios", "costa-brava");
    String before = fingerprint();
    ObjectNode without = forUpdate(trip);
    ArrayNode stops = JSON.createArrayNode();
    int order = 1;
    for (JsonNode stop : trip.get("record").get("stops").values()) {
      if (!stop.get("destination").asString().equals("begur")) {
        ObjectNode copy = (ObjectNode) stop.deepCopy();
        copy.put("order", order++);
        stops.add(copy);
      }
    }
    without.set("stops", stops);
    ApiClient.Response broken = editor.client().put("/api/cms/itinerarios/costa-brava", without.toString());
    assertThat(broken.status()).isEqualTo(409);
    JsonNode problem = json(broken);
    assertThat(problem.get("code").asString()).isEqualTo("breaks_articles");
    assertThat(problem.get("articles").get(0).get("article").asString()).isEqualTo("itinerario-costa-brava");
    assertThat(problem.get("articles").get(0).get("errors").toString())
        .contains("«begur» no es una parada ni un destino del artículo");
    assertThat(fingerprint()).isEqualTo(before);
    // Un cambio que no rompe nada (una nota) se guarda.
    ObjectNode note = forUpdate(trip);
    ((ObjectNode) note.get("stops").get(1)).put("note", "Parada para comer");
    assertThat(editor.client().put("/api/cms/itinerarios/costa-brava", note.toString()).status()).isEqualTo(200);
  }

  @Test
  void renombrarUnAutorQueFirmaFotosPropiasEs409() throws Exception {
    try (Connection c = connectAs("cms_api")) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("INSERT INTO media (id, path, width, height, widths, alt, focal_x, focal_y, placeholder,"
            + " credit_author, credit_source, credit_license, demo, catalog_position) VALUES ('foto-propia',"
            + " 'viajes/foto-propia.0123abcd', 1600, 1000, '{640,1600}', 'Una foto', 0.5, 0.5,"
            + " 'data:image/webp;base64,UklGRg==', 'Cristian Atienza', 'propia', 'derechos-reservados', true, 999)");
        s.execute("INSERT INTO media_original (media_id, file, format, width, height, bytes, sha256) VALUES"
            + " ('foto-propia', 'foto-propia.jpg', 'jpeg', 3000, 2000, 1000000, md5('a') || md5('b'))");
      }
      c.commit();
    }
    JsonNode author = detail("autores", "cristian-atienza");
    assertThat(author.get("usage").get("media").asInt()).isEqualTo(1);
    String before = fingerprint();
    ObjectNode renamed = forUpdate(author);
    renamed.put("name", "C. Atienza");
    ApiClient.Response response = editor.client().put("/api/cms/autores/cristian-atienza", renamed.toString());
    assertThat(response.status()).isEqualTo(409);
    assertThat(json(response).get("code").asString()).isEqualTo("in_use");
    assertThat(json(response).get("usage").get("media").asInt()).isEqualTo(1);
    assertThat(fingerprint()).isEqualTo(before);
    // Cambiar el rol sí.
    ObjectNode role = forUpdate(author);
    role.put("role", "Editor");
    assertThat(editor.client().put("/api/cms/autores/cristian-atienza", role.toString()).status()).isEqualTo(200);
  }

  @Test
  void dosEdicionesSimultaneasConLaMismaVersionSoloAplicanUna() throws Exception {
    Account other = fullAccount("EDITOR");
    JsonNode project = detail("proyectos", "revista-cristian-atienza");
    ObjectNode a = forUpdate(project);
    a.put("name", "A");
    ObjectNode b = forUpdate(project);
    b.put("name", "B");
    List<Integer> statuses = concurrently(
        () -> editor.client().put("/api/cms/proyectos/revista-cristian-atienza", a.toString()).status(),
        () -> other.client().put("/api/cms/proyectos/revista-cristian-atienza", b.toString()).status());
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
  }

  // --- Borrar -----------------------------------------------------------------------------------

  @Test
  void borrarLoQueEstaEnUsoEs409ConSuUso() throws Exception {
    String before = fingerprint();
    for (String[] c : List.of(new String[] {"autores", "cristian-atienza", "articles"},
        new String[] {"lugares", "alicante", "articles"}, new String[] {"lugares", "begur", "trips"},
        new String[] {"itinerarios", "costa-brava", "articles"},
        new String[] {"proyectos", "revista-cristian-atienza", "articles"})) {
      JsonNode detail = detail(c[0], c[1]);
      ApiClient.Response response = delete(admin, "/api/cms/" + c[0] + "/" + c[1],
          version(detail.get("version").asString()));
      assertThat(response.status()).as(c[1]).isEqualTo(409);
      assertThat(json(response).get("code").asString()).isEqualTo("in_use");
      assertThat(json(response).get("usage").get(c[2]).asInt()).as(c[1]).isPositive();
    }
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void borrarLoQueNoSeUsaSoloAdmin() throws Exception {
    JsonNode created = json(editor.client().post("/api/cms/itinerarios", "{\"slug\": \"efimero\", \"title\": \"E\","
        + " \"summary\": \"x\", \"stops\": [{\"order\": 1, \"destination\": \"alicante\"}, {\"order\": 2,"
        + " \"destination\": \"lisboa\"}], \"demo\": true}"));
    String token = created.get("version").asString();
    assertThat(delete(editor, "/api/cms/itinerarios/efimero", version(token)).status()).isEqualTo(403);
    assertThat(delete(admin, "/api/cms/itinerarios/efimero", version("1")).status()).isEqualTo(409);
    ApiClient.Response deleted = delete(admin, "/api/cms/itinerarios/efimero", version(token));
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    assertThat(lastAudit()[0]).isEqualTo("content.trip_deleted");
    assertThat(editor.client().get("/api/cms/itinerarios/efimero").status()).isEqualTo(404);
    // Sus paradas se borran con él (composición).
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM trip_stop WHERE trip_id = 'efimero'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @SafeVarargs
  private static List<Integer> concurrently(Callable<Integer>... tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> task : tasks) {
        futures.add(pool.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();
      List<Integer> out = new ArrayList<>();
      for (Future<Integer> future : futures) {
        out.add(future.get());
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }
}
