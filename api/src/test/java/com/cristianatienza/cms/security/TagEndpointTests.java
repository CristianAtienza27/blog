package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.CodeUsages;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Etiquetas (fase 6.4.6) con la API real y PostgreSQL 18.6 con el contenido real: crear, editar y
 * borrar; reglas de validate.ts; slug único; el id no cambia; borrar una en uso (también solo en una
 * revisión antigua o en un proyecto) es 409; xmin como token opaco; concurrencia; permisos por rol y
 * auditoría en la misma transacción.
 */
class TagEndpointTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));

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

  private static JsonNode json(ApiClient.Response response) {
    return JSON.readTree(response.body());
  }

  private JsonNode tag(String id) throws Exception {
    for (JsonNode tag : json(editor.client().get("/api/cms/etiquetas")).values()) {
      if (tag.get("id").asString().equals(id)) {
        return tag;
      }
    }
    return null;
  }

  private static String body(String slug, String label) {
    return JsonNodeFactory.instance.objectNode().put("slug", slug).put("label", label).toString();
  }

  private static String update(String version, String slug, String label) {
    return JsonNodeFactory.instance.objectNode().put("version", version).put("slug", slug).put("label", label)
        .toString();
  }

  private static String version(String version) {
    return JsonNodeFactory.instance.objectNode().put("version", version).toString();
  }

  /** DELETE con cuerpo JSON y CSRF. */
  private ApiClient.Response delete(Account account, String path, String json) throws Exception {
    return account.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").method("DELETE", HttpRequest.BodyPublishers.ofString(json)), true);
  }

  private static List<String> paths(ApiClient.Response response) {
    List<String> out = new ArrayList<>();
    json(response).path("errors").values().forEach(e -> out.add(e.get("path").asString()));
    return out;
  }

  private static String fingerprint() throws Exception {
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT string_agg(id || ':' || slug || ':' || label || ':' || xmin::text, ','"
            + " ORDER BY id) FROM tag")) {
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

  // --- Lista ------------------------------------------------------------------------------------

  @Test
  void laListaTraeVersionOpacaYSiEstaEnUso() throws Exception {
    JsonNode list = json(editor.client().get("/api/cms/etiquetas"));
    assertThat(list.size()).isEqualTo(CONTENT.collections().get("tags").size());
    Set<String> used = new HashSet<>();
    CONTENT.collections().get("articles").values().forEach(a -> a.get("tags").values().forEach(t -> used.add(t.asString())));
    CONTENT.collections().get("projects").values().forEach(p -> p.get("stack").values().forEach(t -> used.add(t.asString())));
    for (JsonNode tag : list.values()) {
      assertThat(tag.get("version").isString()).isTrue();
      assertThat(tag.get("version").asString()).matches("[0-9]+");
      assertThat(tag.get("inUse").asBoolean()).as(tag.get("id").asString()).isEqualTo(used.contains(tag.get("id").asString()));
    }
  }

  // --- Crear ------------------------------------------------------------------------------------

  @Test
  void crearUnaEtiquetaConSuAuditoria() throws Exception {
    ApiClient.Response created = editor.client().post("/api/cms/etiquetas", body("cafeterias", "Cafeterías"));
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat(created.header("Location")).isEqualTo("/api/cms/etiquetas/cafeterias");
    JsonNode tag = json(created);
    assertThat(tag.get("id").asString()).isEqualTo("cafeterias");
    assertThat(tag.get("slug").asString()).isEqualTo("cafeterias");
    assertThat(tag.get("label").asString()).isEqualTo("Cafeterías");
    assertThat(tag.get("inUse").asBoolean()).isFalse();
    JsonNode list = json(editor.client().get("/api/cms/etiquetas"));
    assertThat(list.get(list.size() - 1).get("id").asString()).isEqualTo("cafeterias");
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.tag_created");
    assertThat(audit[1]).isEqualTo(userIdOf(editor.email()).toString());
    assertThat(JSON.readTree(audit[2]).get("tag").asString()).isEqualTo("cafeterias");
  }

  @Test
  void crearConDatosNoValidosEs422YNoCreaNada() throws Exception {
    String before = fingerprint();
    ApiClient.Response invalid = editor.client().post("/api/cms/etiquetas",
        "{\"slug\": \"Con Espacios\", \"label\": \"\u00a0\u3000\", \"otro\": 1}");
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(paths(invalid)).containsExactlyInAnyOrder("otro", "slug", "label");
    assertThat(invalid.body()).contains("se esperaba un texto no vacío");
    assertThat(paths(editor.client().post("/api/cms/etiquetas", "{\"slug\": \"x\"}"))).containsExactly("label");
    assertThat(editor.client().post("/api/cms/etiquetas", "{\"slug\": \"x\", \"label\": 5}").status()).isEqualTo(422);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void elTextoNoTieneMasLimiteQueElDelContrato() throws Exception {
    // Como validate.ts (text()) y la base de datos (d_text): sin longitud máxima propia.
    ApiClient.Response created = editor.client().post("/api/cms/etiquetas", body("larga", "a".repeat(300)));
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat(json(created).get("label").asString()).hasSize(300);
  }

  @Test
  void slugOcupadoEs409() throws Exception {
    String before = fingerprint();
    ApiClient.Response taken = editor.client().post("/api/cms/etiquetas", body("costa", "Otra costa"));
    assertThat(taken.status()).isEqualTo(409);
    assertThat(json(taken).get("code").asString()).isEqualTo("slug_taken");
    assertThat(fingerprint()).isEqualTo(before);
    // El slug que fue de otra (renombrada) sigue siendo su id: no se reutiliza.
    JsonNode created = json(editor.client().post("/api/cms/etiquetas", body("provisional", "Provisional")));
    assertThat(editor.client().put("/api/cms/etiquetas/provisional",
        update(created.get("version").asString(), "definitiva", "Definitiva")).status()).isEqualTo(200);
    assertThat(json(editor.client().post("/api/cms/etiquetas", body("provisional", "Otra"))).get("code").asString())
        .isEqualTo("slug_taken");
  }

  // --- Editar -----------------------------------------------------------------------------------

  @Test
  void editarConVersionOpacaYElIdNoCambia() throws Exception {
    JsonNode costa = tag("costa");
    ApiClient.Response updated = editor.client().put("/api/cms/etiquetas/costa",
        update(costa.get("version").asString(), "litoral", "Litoral"));
    assertThat(updated.status()).as(updated.body()).isEqualTo(200);
    JsonNode after = json(updated);
    assertThat(after.get("id").asString()).isEqualTo("costa");
    assertThat(after.get("slug").asString()).isEqualTo("litoral");
    assertThat(after.get("label").asString()).isEqualTo("Litoral");
    assertThat(after.get("inUse").asBoolean()).isTrue();
    assertThat(after.get("version").asString()).isNotEqualTo(costa.get("version").asString());
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.tag_updated");
    assertThat(JSON.readTree(audit[2]).get("fields").asString()).isEqualTo("slug,label");
    // Los artículos la siguen referenciando por su id y siguen siendo válidos.
    JsonNode article = json(editor.client().get("/api/cms/articulos/itinerario-costa-brava"));
    assertThat(article.get("content").get("tags").toString()).contains("\"costa\"");
    assertThat(article.get("validation").get("errors").size()).isZero();
    // Con la versión vieja: 409 con la actual.
    ApiClient.Response stale = editor.client().put("/api/cms/etiquetas/costa",
        update(costa.get("version").asString(), "costa", "Costa"));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(json(stale).get("code").asString()).isEqualTo("version_conflict");
    assertThat(json(stale).get("currentVersion").asString()).isEqualTo(after.get("version").asString());
    // Sin cambios: 200, misma versión y sin auditoría.
    long audits = auditCount();
    ApiClient.Response same = editor.client().put("/api/cms/etiquetas/costa",
        update(after.get("version").asString(), "litoral", "Litoral"));
    assertThat(json(same).get("version").asString()).isEqualTo(after.get("version").asString());
    assertThat(auditCount()).isEqualTo(audits);
  }

  @Test
  void editarConVersionNoValidaSlugOcupadoOInexistente() throws Exception {
    JsonNode costa = tag("costa");
    String before = fingerprint();
    // La versión es un texto opaco: un número JSON o un texto que no es una versión, 422.
    assertThat(paths(editor.client().put("/api/cms/etiquetas/costa",
        "{\"version\": 123, \"slug\": \"costa\", \"label\": \"Costa\"}"))).containsExactly("version");
    assertThat(paths(editor.client().put("/api/cms/etiquetas/costa", update("abc", "costa", "Costa"))))
        .containsExactly("version");
    assertThat(editor.client().put("/api/cms/etiquetas/costa", update(costa.get("version").asString(), "europa",
        "Costa")).status()).isEqualTo(409);
    assertThat(editor.client().put("/api/cms/etiquetas/no-existe", update("1", "no-existe", "X")).status())
        .isEqualTo(404);
    assertThat(editor.client().put("/api/cms/etiquetas/Mal", update("1", "mal", "X")).status()).isEqualTo(404);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void dosEdicionesSimultaneasConLaMismaVersionSoloAplicanUna() throws Exception {
    Account other = fullAccount("EDITOR");
    String version = tag("costa").get("version").asString();
    List<Integer> statuses = concurrently(
        () -> editor.client().put("/api/cms/etiquetas/costa", update(version, "costa", "Costa A")).status(),
        () -> other.client().put("/api/cms/etiquetas/costa", update(version, "costa", "Costa B")).status());
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
  }

  // --- Borrar -----------------------------------------------------------------------------------

  @Test
  void borrarUnaSinUsoSoloAdmin() throws Exception {
    JsonNode created = json(editor.client().post("/api/cms/etiquetas", body("efimera", "Efímera")));
    String before = fingerprint();
    assertThat(delete(editor, "/api/cms/etiquetas/efimera", version(created.get("version").asString())).status())
        .isEqualTo(403);
    assertThat(fingerprint()).isEqualTo(before);
    assertThat(delete(admin, "/api/cms/etiquetas/efimera", version("1")).status()).isEqualTo(409); // versión vieja
    ApiClient.Response deleted = delete(admin, "/api/cms/etiquetas/efimera", version(created.get("version").asString()));
    assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
    assertThat(tag("efimera")).isNull();
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.tag_deleted");
    assertThat(audit[1]).isEqualTo(userIdOf(admin.email()).toString());
    assertThat(delete(admin, "/api/cms/etiquetas/efimera", version("1")).status()).isEqualTo(404);
  }

  @Test
  void borrarUnaEnUsoEs409ConSuUso() throws Exception {
    int articles = 0;
    for (JsonNode a : CONTENT.collections().get("articles").values()) {
      for (JsonNode t : a.get("tags").values()) {
        if (t.asString().equals("costa")) {
          articles++;
        }
      }
    }
    String before = fingerprint();
    ApiClient.Response used = delete(admin, "/api/cms/etiquetas/costa", version(tag("costa").get("version").asString()));
    assertThat(used.status()).isEqualTo(409);
    JsonNode problem = json(used);
    assertThat(problem.get("code").asString()).isEqualTo("in_use");
    assertThat(problem.get("usage").get("articles").asInt()).isEqualTo(articles);
    assertThat(problem.get("usage").get("projects").asInt()).isZero();
    // Una que solo usa un proyecto.
    String stackTag = CONTENT.collections().get("projects").get(0).get("stack").get(0).asString();
    ApiClient.Response project = delete(admin, "/api/cms/etiquetas/" + stackTag,
        version(tag(stackTag).get("version").asString()));
    assertThat(project.status()).isEqualTo(409);
    assertThat(json(project).get("usage").get("projects").asInt()).isPositive();
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void unaEtiquetaQueSoloUsaUnaRevisionAntiguaTampocoSeBorra() throws Exception {
    // Nueva etiqueta, usada por una revisión y quitada en la siguiente: la revisión antigua es
    // inmutable y la sigue usando.
    editor.client().post("/api/cms/etiquetas", body("temporal", "Temporal"));
    JsonNode detail = json(editor.client().get("/api/cms/articulos/alicante-desde-el-puerto"));
    ObjectNode content = (ObjectNode) detail.get("content").deepCopy();
    content.remove("readingMinutes");
    ((ArrayNode) content.get("tags")).add("temporal");
    JsonNode saved = json(editor.client().post("/api/cms/articulos/alicante-desde-el-puerto/revisiones",
        JsonNodeFactory.instance.objectNode().put("baseRevisionId",
            detail.get("article").get("headRevision").get("id").asLong()).set("content", content).toString()));
    ObjectNode without = (ObjectNode) saved.get("content").deepCopy();
    without.remove("readingMinutes");
    ArrayNode tags = JsonNodeFactory.instance.arrayNode();
    without.get("tags").values().forEach(t -> {
      if (!t.asString().equals("temporal")) {
        tags.add(t.asString());
      }
    });
    without.set("tags", tags);
    assertThat(editor.client().post("/api/cms/articulos/alicante-desde-el-puerto/revisiones",
        JsonNodeFactory.instance.objectNode().put("baseRevisionId",
            saved.get("article").get("headRevision").get("id").asLong()).set("content", without).toString())
        .status()).isEqualTo(201);
    assertThat(tag("temporal").get("inUse").asBoolean()).isTrue();
    ApiClient.Response response = delete(admin, "/api/cms/etiquetas/temporal",
        version(tag("temporal").get("version").asString()));
    assertThat(response.status()).isEqualTo(409);
    assertThat(json(response).get("usage").get("articles").asInt()).isEqualTo(1);
  }

  @Test
  void borrarExigeUnCuerpoConLaVersion() throws Exception {
    String before = fingerprint();
    assertThat(admin.client().delete("/api/cms/etiquetas/costa").status()).isEqualTo(415);
    assertThat(paths(delete(admin, "/api/cms/etiquetas/costa", "{}"))).containsExactly("version");
    assertThat(fingerprint()).isEqualTo(before);
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
