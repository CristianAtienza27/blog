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
import java.util.List;
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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Escrituras editoriales (fase 6.4.3) con la API real y PostgreSQL 18.6 con el contenido real:
 * crear, editar metadatos, guardar y restaurar revisiones (6.4.4), publicar, despublicar y reordenar
 * (6.4.5) y validar sin guardar; 422 por campo, 409 de revisión,
 * versión y slug, concurrencia real, permisos por rol, lectura estricta del cuerpo, auditoría en la
 * misma transacción y avisos en el detalle. Cada test parte de un esquema limpio con el contenido real.
 */
class EditorialWriteEndpointTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));
  private static final List<String> CONTENT_FIELDS = List.of("title", "dek", "excerpt", "tags", "format", "cover",
      "author", "trip", "destinations", "project", "seo");

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

  private static JsonNode sourceArticle(String id) {
    for (JsonNode a : CONTENT.collections().get("articles").values()) {
      if (a.get("id").asString().equals(id)) {
        return a;
      }
    }
    throw new IllegalArgumentException(id);
  }

  /** El contenido de un artículo real tal como lo envía el CMS (sin readingMinutes). */
  private static ObjectNode contentOf(String id) {
    JsonNode article = sourceArticle(id);
    ObjectNode content = JsonNodeFactory.instance.objectNode();
    for (String key : CONTENT_FIELDS) {
      if (article.has(key)) {
        content.set(key, article.get(key).deepCopy());
      }
    }
    content.set("blocks", CONTENT.bodies().get(article.get("slug").asString()).get("blocks").deepCopy());
    return content;
  }

  private static String newArticle(String slug, String category, ObjectNode content) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("slug", slug).put("category", category).put("featured", false).put("demo", true);
    body.set("content", content);
    return body.toString();
  }

  private static String save(long base, ObjectNode content) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("baseRevisionId", base);
    body.set("content", content);
    return body.toString();
  }

  private static ObjectNode metadata(JsonNode article) {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("version", article.get("version").asInt());
    body.put("slug", article.get("slug").asString());
    body.put("category", article.get("category").asString());
    body.put("featured", article.get("featured").asBoolean());
    body.put("demo", article.get("demo").asBoolean());
    body.set("publishedAt", article.get("publishedAt"));
    body.set("updatedAt", article.get("updatedAt"));
    return body;
  }

  private JsonNode detail(String id) throws Exception {
    ApiClient.Response response = editor.client().get("/api/cms/articulos/" + id);
    assertThat(response.status()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private static List<String> paths(ApiClient.Response response) {
    List<String> out = new ArrayList<>();
    json(response).path("errors").values().forEach(e -> out.add(e.get("path").asString()));
    return out;
  }

  /** Huella del contenido editorial (artículos, versiones, punteros, revisiones) y de su auditoría. */
  private static String fingerprint() throws Exception {
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT concat_ws('|',"
            + " (SELECT string_agg(id || ':' || slug || ':' || lock_version || ':' || coalesce(head_revision_id, 0),"
            + "   ',' ORDER BY id) FROM article), (SELECT count(*) FROM article_revision))")) {
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

  /** El último evento editorial: tipo, usuario y detalles. */
  private static String[] lastAudit() throws Exception {
    try (Connection c = connectAs("cms_security_tool"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT event, user_id::text, details::text FROM audit_event"
            + " WHERE event LIKE 'content.%' ORDER BY occurred_at DESC LIMIT 1")) {
      return rs.next() ? new String[] {rs.getString(1), rs.getString(2), rs.getString(3)} : null;
    }
  }

  // --- Crear -----------------------------------------------------------------------------------

  @Test
  void crearUnBorradorConSuRevisionUnoYSuAuditoria() throws Exception {
    ObjectNode content = contentOf("alicante-desde-el-puerto");
    content.put("title", "Un artículo nuevo");
    ApiClient.Response created = editor.client().post("/api/cms/articulos", newArticle("articulo-nuevo", "motor", content));
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat(created.header("Location")).isEqualTo("/api/cms/articulos/articulo-nuevo");
    JsonNode detail = json(created);
    JsonNode article = detail.get("article");
    assertThat(article.get("id").asString()).isEqualTo("articulo-nuevo");
    assertThat(article.get("state").asString()).isEqualTo("borrador");
    assertThat(article.get("category").asString()).isEqualTo("motor");
    assertThat(article.get("order").isNull()).isTrue();
    assertThat(article.get("publishedAt").isNull()).isTrue();
    assertThat(article.get("version").asInt()).isZero();
    assertThat(article.get("headRevision").get("number").asInt()).isEqualTo(1);
    assertThat(article.get("headRevision").get("origin").asString()).isEqualTo("edit");
    assertThat(article.get("publishedRevision").isNull()).isTrue();
    // readingMinutes lo calcula el servidor.
    assertThat(detail.get("content").get("readingMinutes").asInt())
        .isEqualTo(sourceArticle("alicante-desde-el-puerto").get("readingMinutes").asInt());
    assertThat(detail.get("content").get("title").asString()).isEqualTo("Un artículo nuevo");
    assertThat(detail.get("validation").get("errors").size()).isZero();
    assertThat(detail.get("validation").get("warnings").size()).isZero();
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.article_created");
    assertThat(audit[1]).isEqualTo(userIdOf(editor.email()).toString());
    assertThat(JSON.readTree(audit[2]).get("article").asString()).isEqualTo("articulo-nuevo");
    assertThat(JSON.readTree(audit[2]).get("revision").asString()).isEqualTo("1");
    assertThat(audit[2]).doesNotContain("Un artículo nuevo"); // la auditoría nunca lleva contenido
    assertThat(json(editor.client().get("/api/cms/articulos?estado=borrador")).get("total").asLong()).isEqualTo(1);
  }

  @Test
  void crearConErroresDaUn422ConTodasLasIncidenciasYNoGuardaNada() throws Exception {
    String before = fingerprint();
    // Campos de primer nivel.
    ApiClient.Response top = editor.client().post("/api/cms/articulos",
        "{\"slug\": \"Mal Slug\", \"category\": \"deportes\", \"featured\": \"no\", \"otro\": 1}");
    assertThat(top.status()).isEqualTo(422);
    assertThat(json(top).get("code").asString()).isEqualTo("invalid_content");
    assertThat(paths(top)).containsExactlyInAnyOrder("otro", "slug", "category", "featured", "demo", "content");
    // El contenido, con el validador de la 6.4.1 y el catálogo real.
    ObjectNode content = contentOf("alicante-desde-el-puerto");
    content.remove("title");
    content.put("readingMinutes", 3);
    content.put("slug", "x");
    ((ArrayNode) content.get("blocks")).add(JSON.readTree("{\"type\": \"text\", \"html\": \"<script>x</script>\"}"));
    ApiClient.Response invalid = editor.client().post("/api/cms/articulos", newArticle("otro-articulo", "motor", content));
    assertThat(invalid.status()).as(invalid.body()).isEqualTo(422);
    List<String> paths = paths(invalid);
    assertThat(paths).contains("content.readingMinutes", "content.slug", "content.title");
    assertThat(paths.stream().anyMatch(p -> p.startsWith("content.blocks["))).isTrue();
    assertThat(invalid.body()).contains("etiqueta no permitida: <script>");
    // Integridad (solo con la forma correcta, como validateContent): referencias del catálogo real.
    ObjectNode references = contentOf("alicante-desde-el-puerto");
    references.set("tags", JSON.readTree("[\"no-existe\"]"));
    ((ArrayNode) references.get("blocks")).add(JSON.readTree("{\"type\": \"image\", \"media\": \"motor-moto-trail\", \"layout\": \"wide\"}"));
    ApiClient.Response integrity = editor.client().post("/api/cms/articulos", newArticle("otro-articulo", "motor", references));
    assertThat(integrity.status()).isEqualTo(422);
    assertThat(paths(integrity)).contains("content.tags[0]");
    assertThat(integrity.body()).contains("etiqueta inexistente «no-existe»").contains("imagen retirada «motor-moto-trail»");
    // Las divergencias de la 6.4.1 siguen activas: Java rechaza lo que TS dejaría pasar.
    ObjectNode prototype = contentOf("alicante-desde-el-puerto");
    prototype.put("constructor", 1);
    ((ObjectNode) prototype.get("blocks").get(0)).put("constructor", "x");
    ApiClient.Response strict = editor.client().post("/api/cms/articulos", newArticle("otro-mas", "motor", prototype));
    assertThat(strict.status()).isEqualTo(422);
    assertThat(paths(strict)).contains("content.constructor", "content.blocks[0].constructor");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void slugOcupadoEs409() throws Exception {
    String before = fingerprint();
    ApiClient.Response taken = editor.client().post("/api/cms/articulos",
        newArticle("calas-de-mallorca", "viajes", contentOf("calas-de-mallorca")));
    assertThat(taken.status()).isEqualTo(409);
    assertThat(json(taken).get("code").asString()).isEqualTo("slug_taken");
    assertThat(fingerprint()).isEqualTo(before);
    // Un slug que fue el id de otro artículo (renombrado) tampoco se reutiliza.
    editor.client().post("/api/cms/articulos", newArticle("borrador-uno", "motor", contentOf("anatomia-moto-de-turismo")));
    ObjectNode rename = metadata(detail("borrador-uno").get("article"));
    rename.put("slug", "borrador-renombrado");
    assertThat(editor.client().put("/api/cms/articulos/borrador-uno/metadatos", rename.toString()).status())
        .isEqualTo(200);
    ApiClient.Response reused = editor.client().post("/api/cms/articulos",
        newArticle("borrador-uno", "motor", contentOf("anatomia-moto-de-turismo")));
    assertThat(reused.status()).isEqualTo(409);
    assertThat(json(reused).get("code").asString()).isEqualTo("slug_taken");
  }

  @Test
  void dosAltasSimultaneasConElMismoSlugSoloCreanUna() throws Exception {
    Account other = fullAccount("EDITOR");
    String body = newArticle("simultaneo", "motor", contentOf("anatomia-moto-de-turismo"));
    List<Integer> statuses = concurrently(
        () -> editor.client().post("/api/cms/articulos", body).status(),
        () -> other.client().post("/api/cms/articulos", body).status());
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(json(editor.client().get("/api/cms/articulos?estado=borrador")).get("total").asLong()).isEqualTo(1);
  }

  // --- Guardar revisiones ------------------------------------------------------------------------

  @Test
  void guardarCreaLaRevisionSiguienteYSinCambiosNoCreaNada() throws Exception {
    JsonNode before = detail("calas-de-mallorca");
    long head = before.get("article").get("headRevision").get("id").asLong();
    ObjectNode content = contentOf("calas-de-mallorca");
    content.put("title", "Calas de Mallorca, otra vez");
    ApiClient.Response saved = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones", save(head, content));
    assertThat(saved.status()).as(saved.body()).isEqualTo(201);
    JsonNode after = json(saved);
    assertThat(after.get("article").get("state").asString()).isEqualTo("con-cambios");
    assertThat(after.get("article").get("headRevision").get("number").asInt()).isEqualTo(2);
    assertThat(after.get("article").get("publishedRevision").get("id").asLong()).isEqualTo(head);
    assertThat(after.get("content").get("title").asString()).isEqualTo("Calas de Mallorca, otra vez");
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.revision_created");
    assertThat(JSON.readTree(audit[2]).get("revision").asString()).isEqualTo("2");
    assertThat(JSON.readTree(audit[2]).get("base").asString()).isEqualTo("1");
    // El mismo contenido otra vez: 200, sin revisión nueva ni auditoría.
    long newHead = after.get("article").get("headRevision").get("id").asLong();
    String unchangedBefore = fingerprint();
    ApiClient.Response same = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones",
        save(newHead, content));
    assertThat(same.status()).isEqualTo(200);
    assertThat(fingerprint()).isEqualTo(unchangedBefore);
    // La revisión 1 no ha cambiado.
    assertThat(json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones/1")).get("content")
        .get("title").asString()).isEqualTo(sourceArticle("calas-de-mallorca").get("title").asString());
  }

  @Test
  void guardarSobreUnaRevisionQueNoEsLaDeTrabajoEs409() throws Exception {
    long head = detail("calas-de-mallorca").get("article").get("headRevision").get("id").asLong();
    ObjectNode first = contentOf("calas-de-mallorca");
    first.put("title", "Primera edición");
    assertThat(editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones", save(head, first)).status())
        .isEqualTo(201);
    String before = fingerprint();
    ObjectNode stale = contentOf("calas-de-mallorca");
    stale.put("title", "Edición sobre una base vieja");
    ApiClient.Response conflict = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones",
        save(head, stale));
    assertThat(conflict.status()).isEqualTo(409);
    JsonNode problem = json(conflict);
    assertThat(problem.get("code").asString()).isEqualTo("revision_conflict");
    assertThat(problem.get("currentRevisionId").asLong())
        .isEqualTo(detail("calas-de-mallorca").get("article").get("headRevision").get("id").asLong());
    assertThat(fingerprint()).isEqualTo(before);
    // Una base de otro artículo o inexistente, también.
    long otherHead = detail("alicante-desde-el-puerto").get("article").get("headRevision").get("id").asLong();
    assertThat(editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones", save(otherHead, stale)).status())
        .isEqualTo(409);
    assertThat(editor.client().post("/api/cms/articulos/no-existe/revisiones", save(head, stale)).status())
        .isEqualTo(404);
  }

  @Test
  void dosGuardadosSimultaneosDesdeLaMismaBaseSoloGuardanUno() throws Exception {
    Account other = fullAccount("EDITOR");
    long head = detail("calas-de-mallorca").get("article").get("headRevision").get("id").asLong();
    ObjectNode a = contentOf("calas-de-mallorca");
    a.put("title", "Versión A");
    ObjectNode b = contentOf("calas-de-mallorca");
    b.put("title", "Versión B");
    List<Integer> statuses = concurrently(
        () -> editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones", save(head, a)).status(),
        () -> other.client().post("/api/cms/articulos/calas-de-mallorca/revisiones", save(head, b)).status());
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    JsonNode history = json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones"));
    assertThat(history.get("total").asLong()).isEqualTo(2); // la importada y UNA de las dos
  }

  @Test
  void guardarContenidoNoValidoEs422YNoCreaRevision() throws Exception {
    long head = detail("calas-de-mallorca").get("article").get("headRevision").get("id").asLong();
    String before = fingerprint();
    ObjectNode content = contentOf("calas-de-mallorca");
    ((ArrayNode) content.get("blocks")).add(JSON.readTree(
        "{\"type\": \"text\", \"html\": \"<p><a href=\\\"javascript:alert(1)\\\">x</a></p>\"}"));
    ApiClient.Response invalid = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones",
        save(head, content));
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(invalid.body()).contains("atributos no permitidos en <a>");
    assertThat(fingerprint()).isEqualTo(before);
    ApiClient.Response noBase = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones",
        "{\"content\": {}}");
    assertThat(noBase.status()).isEqualTo(422);
    assertThat(paths(noBase)).contains("baseRevisionId");
  }

  // --- Metadatos ---------------------------------------------------------------------------------

  @Test
  void metadatosDeUnBorradorConVersionYConflictos() throws Exception {
    editor.client().post("/api/cms/articulos", newArticle("un-borrador", "motor", contentOf("anatomia-moto-de-turismo")));
    JsonNode article = detail("un-borrador").get("article");
    ObjectNode change = metadata(article);
    change.put("slug", "un-borrador-renombrado").put("category", "experiencias").put("featured", true);
    ApiClient.Response updated = editor.client().put("/api/cms/articulos/un-borrador/metadatos", change.toString());
    assertThat(updated.status()).as(updated.body()).isEqualTo(200);
    JsonNode after = json(updated).get("article");
    assertThat(after.get("id").asString()).isEqualTo("un-borrador"); // el id no cambia
    assertThat(after.get("slug").asString()).isEqualTo("un-borrador-renombrado");
    assertThat(after.get("category").asString()).isEqualTo("experiencias");
    assertThat(after.get("featured").asBoolean()).isTrue();
    assertThat(after.get("version").asInt()).isEqualTo(1);
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.article_metadata_updated");
    assertThat(JSON.readTree(audit[2]).get("fields").asString()).isEqualTo("slug,category,featured");
    // Versión vieja: 409 con la actual.
    ApiClient.Response stale = editor.client().put("/api/cms/articulos/un-borrador/metadatos", change.toString());
    assertThat(stale.status()).isEqualTo(409);
    assertThat(json(stale).get("code").asString()).isEqualTo("version_conflict");
    assertThat(json(stale).get("currentVersion").asInt()).isEqualTo(1);
    // Sin cambios: 200, misma versión y sin auditoría.
    long audits = auditCount();
    ApiClient.Response same = editor.client().put("/api/cms/articulos/un-borrador/metadatos",
        metadata(after).toString());
    assertThat(json(same).get("article").get("version").asInt()).isEqualTo(1);
    assertThat(auditCount()).isEqualTo(audits);
    // Un borrador no tiene fecha de publicación: la fija la publicación (6.4.5).
    ObjectNode dated = metadata(after);
    dated.put("publishedAt", "2026-01-01");
    ApiClient.Response withDate = editor.client().put("/api/cms/articulos/un-borrador/metadatos", dated.toString());
    assertThat(withDate.status()).isEqualTo(422);
    assertThat(paths(withDate)).containsExactly("publishedAt");
    // Slug ocupado.
    ObjectNode taken = metadata(after);
    taken.put("slug", "calas-de-mallorca");
    ApiClient.Response collision = editor.client().put("/api/cms/articulos/un-borrador/metadatos", taken.toString());
    assertThat(collision.status()).isEqualTo(409);
    assertThat(json(collision).get("code").asString()).isEqualTo("slug_taken");
    // Cuerpo incompleto o con fechas mal formadas: todas las incidencias.
    ApiClient.Response incomplete = editor.client().put("/api/cms/articulos/un-borrador/metadatos",
        "{\"version\": -1, \"slug\": \"un-borrador\", \"publishedAt\": \"2026-02-30\", \"extra\": true}");
    assertThat(incomplete.status()).isEqualTo(422);
    assertThat(paths(incomplete)).containsExactlyInAnyOrder("extra", "version", "category", "featured", "demo",
        "publishedAt", "updatedAt");
  }

  @Test
  void metadatosDeUnPublicadoSoloAdminYSinCambiarSlugNiSeccion() throws Exception {
    JsonNode article = detail("calas-de-mallorca").get("article");
    ObjectNode featured = metadata(article);
    featured.put("featured", !article.get("featured").asBoolean());
    String before = fingerprint();
    ApiClient.Response byEditor = editor.client().put("/api/cms/articulos/calas-de-mallorca/metadatos",
        featured.toString());
    assertThat(byEditor.status()).isEqualTo(403);
    assertThat(json(byEditor).get("code").asString()).isEqualTo("forbidden");
    assertThat(fingerprint()).isEqualTo(before);
    ApiClient.Response byAdmin = admin.client().put("/api/cms/articulos/calas-de-mallorca/metadatos",
        featured.toString());
    assertThat(byAdmin.status()).as(byAdmin.body()).isEqualTo(200);
    assertThat(json(byAdmin).get("article").get("featured").asBoolean()).isEqualTo(!article.get("featured").asBoolean());
    JsonNode now = json(byAdmin).get("article");
    for (String[] change : List.of(new String[] {"slug", "calas-renombradas"}, new String[] {"category", "motor"})) {
      ObjectNode locked = metadata(now);
      locked.put(change[0], change[1]);
      ApiClient.Response response = admin.client().put("/api/cms/articulos/calas-de-mallorca/metadatos",
          locked.toString());
      assertThat(response.status()).as(change[0]).isEqualTo(409);
      assertThat(json(response).get("code").asString()).isEqualTo("published_locked");
    }
    ObjectNode noDate = metadata(now);
    noDate.putNull("publishedAt");
    assertThat(admin.client().put("/api/cms/articulos/calas-de-mallorca/metadatos", noDate.toString()).status())
        .isEqualTo(422);
    ObjectNode earlier = metadata(now);
    earlier.put("updatedAt", "2000-01-01");
    ApiClient.Response early = admin.client().put("/api/cms/articulos/calas-de-mallorca/metadatos", earlier.toString());
    assertThat(early.status()).isEqualTo(422);
    assertThat(paths(early)).containsExactly("updatedAt");
  }

  // --- Validar sin guardar y avisos --------------------------------------------------------------

  @Test
  void validarSinGuardarYAvisosDeEnlacesASinPublicar() throws Exception {
    // Un borrador al que enlazar.
    editor.client().post("/api/cms/articulos", newArticle("motor-en-borrador", "motor",
        contentOf("anatomia-moto-de-turismo")));
    ObjectNode content = contentOf("alicante-desde-el-puerto");
    ((ArrayNode) content.get("blocks")).add(JSON.readTree(
        "{\"type\": \"text\", \"html\": \"<p><a href=\\\"/motor/motor-en-borrador\\\">x</a></p>\"}"));
    String before = fingerprint();
    ApiClient.Response checked = editor.client().post("/api/cms/articulos/validacion",
        JsonNodeFactory.instance.objectNode().put("article", "alicante-desde-el-puerto").set("content", content)
            .toString());
    assertThat(checked.status()).as(checked.body()).isEqualTo(200);
    JsonNode result = json(checked);
    assertThat(result.get("errors").size()).isZero();
    assertThat(result.get("warnings").size()).isEqualTo(1);
    assertThat(result.get("warnings").get(0).get("message").asString())
        .isEqualTo("enlace a un artículo que aún no está publicado «/motor/motor-en-borrador»");
    assertThat(result.get("readingMinutes").asInt()).isPositive();
    assertThat(fingerprint()).isEqualTo(before);
    // Para un artículo nuevo, con su slug y sección; y con errores.
    ObjectNode bad = contentOf("alicante-desde-el-puerto");
    bad.put("format", "cronica");
    JsonNode errors = json(editor.client().post("/api/cms/articulos/validacion",
        JsonNodeFactory.instance.objectNode().put("slug", "nuevo").put("category", "viajes").set("content", bad)
            .toString()));
    assertThat(errors.get("errors").get(0).get("path").asString()).isEqualTo("content.format");
    // El aviso se guarda (no bloquea) y el detalle lo devuelve.
    long head = detail("alicante-desde-el-puerto").get("article").get("headRevision").get("id").asLong();
    assertThat(editor.client().post("/api/cms/articulos/alicante-desde-el-puerto/revisiones", save(head, content))
        .status()).isEqualTo(201);
    JsonNode validation = detail("alicante-desde-el-puerto").get("validation");
    assertThat(validation.get("errors").size()).isZero();
    assertThat(validation.get("warnings").get(0).get("path").asString()).startsWith("content.blocks[");
    // Un enlace a un artículo que NO existe sí es un error y no se guarda.
    ObjectNode broken = contentOf("alicante-desde-el-puerto");
    ((ArrayNode) broken.get("blocks")).add(JSON.readTree(
        "{\"type\": \"text\", \"html\": \"<p><a href=\\\"/motor/no-existe\\\">x</a></p>\"}"));
    long newHead = detail("alicante-desde-el-puerto").get("article").get("headRevision").get("id").asLong();
    ApiClient.Response rejected = editor.client().post("/api/cms/articulos/alicante-desde-el-puerto/revisiones",
        save(newHead, broken));
    assertThat(rejected.status()).isEqualTo(422);
    assertThat(rejected.body()).contains("enlace a un artículo inexistente");
  }

  @Test
  void losDoceArticulosRealesNoTienenErroresNiAvisos() throws Exception {
    for (JsonNode a : CONTENT.collections().get("articles").values()) {
      JsonNode validation = detail(a.get("id").asString()).get("validation");
      assertThat(validation.get("errors").size()).as(a.get("id").asString()).isZero();
      assertThat(validation.get("warnings").size()).as(a.get("id").asString()).isZero();
    }
  }

  @Test
  void elDetalleDaErroresSiElCatalogoCambiaDespues() throws Exception {
    // Una imagen que usa el artículo se retira después de guardarlo: el detalle lo señala.
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement()) {
      s.execute("UPDATE media SET retired = true WHERE id = 'viajes-alicante-panorama'");
    }
    JsonNode validation = detail("alicante-desde-el-puerto").get("validation");
    assertThat(validation.get("errors").toString()).contains("imagen retirada «viajes-alicante-panorama»");
  }

  // --- Restaurar una revisión (6.4.4) -------------------------------------------------------------

  private static String restoreBody(long base) {
    return "{\"baseRevisionId\": " + base + "}";
  }

  private long head(String id) throws Exception {
    return detail(id).get("article").get("headRevision").get("id").asLong();
  }

  /** Guarda una revisión con el título cambiado y devuelve la nueva revisión de trabajo. */
  private long saveTitle(String id, String title) throws Exception {
    ObjectNode content = contentOf(id);
    content.put("title", title);
    ApiClient.Response saved = editor.client().post("/api/cms/articulos/" + id + "/revisiones", save(head(id), content));
    assertThat(saved.status()).as(saved.body()).isEqualTo(201);
    return json(saved).get("article").get("headRevision").get("id").asLong();
  }

  @Test
  void restaurarCreaLaRevisionSiguienteSinTocarElHistorial() throws Exception {
    long second = saveTitle("calas-de-mallorca", "Calas, segunda versión");
    ApiClient.Response restored = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        restoreBody(second));
    assertThat(restored.status()).as(restored.body()).isEqualTo(201);
    JsonNode detail = json(restored);
    JsonNode headRevision = detail.get("article").get("headRevision");
    assertThat(headRevision.get("number").asInt()).isEqualTo(3);
    assertThat(headRevision.get("origin").asString()).isEqualTo("restore");
    assertThat(headRevision.get("restoredFrom").asInt()).isEqualTo(1);
    assertThat(detail.get("content").get("title").asString())
        .isEqualTo(sourceArticle("calas-de-mallorca").get("title").asString());
    // La revisión publicada sigue siendo la 1; el estado se deriva de los punteros.
    assertThat(detail.get("article").get("publishedRevision").get("number").asInt()).isEqualTo(1);
    assertThat(detail.get("article").get("state").asString()).isEqualTo("con-cambios");
    // El historial crece y lo anterior no cambia.
    JsonNode history = json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones"));
    assertThat(history.get("total").asLong()).isEqualTo(3);
    assertThat(json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones/2")).get("content")
        .get("title").asString()).isEqualTo("Calas, segunda versión");
    assertThat(json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones/3")).get("content"))
        .isEqualTo(json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones/1")).get("content"));
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.revision_restored");
    assertThat(audit[1]).isEqualTo(userIdOf(editor.email()).toString());
    JsonNode details = JSON.readTree(audit[2]);
    assertThat(details.get("article").asString()).isEqualTo("calas-de-mallorca");
    assertThat(details.get("revision").asString()).isEqualTo("3");
    assertThat(details.get("restoredFrom").asString()).isEqualTo("1");
    assertThat(details.get("base").asString()).isEqualTo("2");
  }

  @Test
  void restaurarSobreUnaBaseQueNoEsLaDeTrabajoEs409() throws Exception {
    long first = head("calas-de-mallorca");
    long second = saveTitle("calas-de-mallorca", "Calas, segunda versión");
    String before = fingerprint();
    ApiClient.Response conflict = editor.client().post(
        "/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar", restoreBody(first));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(json(conflict).get("code").asString()).isEqualTo("revision_conflict");
    assertThat(json(conflict).get("currentRevisionId").asLong()).isEqualTo(second);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void restaurarLoQueNoExisteEs404() throws Exception {
    long base = head("calas-de-mallorca");
    String before = fingerprint();
    for (String path : List.of("/api/cms/articulos/no-existe/revisiones/1/restaurar",
        "/api/cms/articulos/calas-de-mallorca/revisiones/2/restaurar",
        "/api/cms/articulos/calas-de-mallorca/revisiones/0/restaurar",
        "/api/cms/articulos/calas-de-mallorca/revisiones/abc/restaurar",
        "/api/cms/articulos/Mal/revisiones/1/restaurar")) {
      ApiClient.Response response = editor.client().post(path, restoreBody(base));
      assertThat(response.status()).as(path).isEqualTo(404);
      assertThat(json(response).get("code").asString()).as(path).isEqualTo("not_found");
    }
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void restaurarElMismoContenidoQueLaDeTrabajoNoCreaNada() throws Exception {
    long base = head("calas-de-mallorca");
    String before = fingerprint();
    // La propia revisión de trabajo.
    ApiClient.Response same = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        restoreBody(base));
    assertThat(same.status()).isEqualTo(200);
    assertThat(fingerprint()).isEqualTo(before);
    // Una anterior cuyo contenido ya es el de trabajo (la 1 tras restaurarla en la 3).
    long second = saveTitle("calas-de-mallorca", "Calas, segunda versión");
    assertThat(editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        restoreBody(second)).status()).isEqualTo(201);
    String afterRestore = fingerprint();
    ApiClient.Response again = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        restoreBody(head("calas-de-mallorca")));
    assertThat(again.status()).isEqualTo(200);
    assertThat(json(again).get("article").get("headRevision").get("number").asInt()).isEqualTo(3);
    assertThat(fingerprint()).isEqualTo(afterRestore);
  }

  @Test
  void restaurarUnaRevisionQueUsaUnaImagenRetiradaDespuesEs422() throws Exception {
    // La 1 usa viajes-alicante-panorama como portada; la 2 cambia de portada; después se retira.
    ObjectNode content = contentOf("alicante-desde-el-puerto");
    content.put("cover", "viajes-calella-banyera");
    assertThat(editor.client().post("/api/cms/articulos/alicante-desde-el-puerto/revisiones",
        save(head("alicante-desde-el-puerto"), content)).status()).isEqualTo(201);
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement()) {
      s.execute("UPDATE media SET retired = true WHERE id = 'viajes-alicante-panorama'");
    }
    String before = fingerprint();
    ApiClient.Response rejected = editor.client().post(
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/1/restaurar",
        restoreBody(head("alicante-desde-el-puerto")));
    assertThat(rejected.status()).as(rejected.body()).isEqualTo(422);
    assertThat(json(rejected).get("code").asString()).isEqualTo("invalid_content");
    assertThat(paths(rejected)).contains("content.cover");
    assertThat(rejected.body()).contains("imagen retirada «viajes-alicante-panorama»");
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void dosRestauracionesSimultaneasDesdeLaMismaBaseSoloCreanUna() throws Exception {
    Account other = fullAccount("EDITOR");
    long second = saveTitle("calas-de-mallorca", "Calas, segunda versión");
    List<Integer> statuses = concurrently(
        () -> editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
            restoreBody(second)).status(),
        () -> other.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
            restoreBody(second)).status());
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(json(editor.client().get("/api/cms/articulos/calas-de-mallorca/revisiones")).get("total").asLong())
        .isEqualTo(3);
  }

  @Test
  void restaurarConUnCuerpoNoValido() throws Exception {
    long base = head("calas-de-mallorca");
    String before = fingerprint();
    ApiClient.Response missing = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        "{}");
    assertThat(missing.status()).isEqualTo(422);
    assertThat(paths(missing)).containsExactly("baseRevisionId");
    ApiClient.Response extra = editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar",
        "{\"baseRevisionId\": " + base + ", \"content\": {}}");
    assertThat(extra.status()).isEqualTo(422);
    assertThat(paths(extra)).containsExactly("content");
    ApiClient.Response query = editor.client().post(
        "/api/cms/articulos/calas-de-mallorca/revisiones/1/restaurar?x=1", restoreBody(base));
    assertThat(query.status()).isEqualTo(400);
    assertThat(fingerprint()).isEqualTo(before);
  }

  // --- Publicar, despublicar y reordenar (6.4.5) ----------------------------------------------------

  private static String publishBody(long revision, int version) {
    return "{\"revisionId\": " + revision + ", \"version\": " + version + "}";
  }

  /** Lo que leería la generación de instantáneas de la 6.3 (P2): ContentExporter.readPublished como la API. */
  private static List<String> publishedInSnapshot() {
    var content = com.cristianatienza.cms.content.io.ContentExporter.readPublished(
        new DriverManagerDataSource(TestDatabase.url(), "cms_api", TestDatabase.password("cms_api")), null);
    List<String> ids = new ArrayList<>();
    content.collections().get("articles").values().forEach(a -> ids.add(a.get("id").asString()));
    return ids;
  }

  private static String madridToday() {
    return java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid")).toString();
  }

  @Test
  void publicarUnBorradorLeDaFechaYOrdenYEntraEnLaSiguienteInstantanea() throws Exception {
    editor.client().post("/api/cms/articulos", newArticle("nuevo-en-viajes", "viajes", contentOf("calas-de-mallorca")));
    JsonNode draft = detail("nuevo-en-viajes").get("article");
    assertThat(publishedInSnapshot()).doesNotContain("nuevo-en-viajes");
    ApiClient.Response published = admin.client().post("/api/cms/articulos/nuevo-en-viajes/publicacion",
        publishBody(draft.get("headRevision").get("id").asLong(), 0));
    assertThat(published.status()).as(published.body()).isEqualTo(200);
    JsonNode article = json(published).get("article");
    assertThat(article.get("state").asString()).isEqualTo("publicado");
    assertThat(article.get("publishedAt").asString()).isEqualTo(madridToday());
    assertThat(article.get("order").asInt()).isEqualTo(5); // viajes tenía 1–4
    assertThat(article.get("version").asInt()).isEqualTo(1);
    assertThat(article.get("publishedRevision").get("id")).isEqualTo(article.get("headRevision").get("id"));
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.article_published");
    assertThat(audit[1]).isEqualTo(userIdOf(admin.email()).toString());
    JsonNode details = JSON.readTree(audit[2]);
    assertThat(details.get("article").asString()).isEqualTo("nuevo-en-viajes");
    assertThat(details.get("revision").asString()).isEqualTo("1");
    assertThat(details.get("publishedAt").asString()).isEqualTo(madridToday());
    assertThat(details.get("order").asString()).isEqualTo("5");
    // La 6.3 lo exporta en la siguiente instantánea: misma lectura que P2, sin duplicar nada.
    assertThat(publishedInSnapshot()).contains("nuevo-en-viajes");
    // Publicar lo ya publicado: 200, sin versión nueva ni auditoría.
    long audits = auditCount();
    ApiClient.Response again = admin.client().post("/api/cms/articulos/nuevo-en-viajes/publicacion",
        publishBody(article.get("headRevision").get("id").asLong(), 1));
    assertThat(json(again).get("article").get("version").asInt()).isEqualTo(1);
    assertThat(auditCount()).isEqualTo(audits);
  }

  @Test
  void despublicarYRepublicarConservanFechaYOrden() throws Exception {
    JsonNode before = detail("calas-de-mallorca").get("article");
    ApiClient.Response unpublished = admin.client().post("/api/cms/articulos/calas-de-mallorca/despublicacion",
        "{\"version\": 0}");
    assertThat(unpublished.status()).as(unpublished.body()).isEqualTo(200);
    JsonNode after = json(unpublished).get("article");
    assertThat(after.get("state").asString()).isEqualTo("borrador");
    assertThat(after.get("publishedRevision").isNull()).isTrue();
    assertThat(after.get("publishedAt")).isEqualTo(before.get("publishedAt"));
    assertThat(after.get("order")).isEqualTo(before.get("order"));
    assertThat(after.get("version").asInt()).isEqualTo(1);
    assertThat(lastAudit()[0]).isEqualTo("content.article_unpublished");
    assertThat(publishedInSnapshot()).doesNotContain("calas-de-mallorca");
    // Otra vez: no estaba publicado, nada cambia.
    long audits = auditCount();
    assertThat(json(admin.client().post("/api/cms/articulos/calas-de-mallorca/despublicacion", "{\"version\": 1}"))
        .get("article").get("version").asInt()).isEqualTo(1);
    assertThat(auditCount()).isEqualTo(audits);
    // Republicar: la fecha real de la primera publicación y su lugar en la sección se conservan.
    ApiClient.Response republished = admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(after.get("headRevision").get("id").asLong(), 1));
    JsonNode again = json(republished).get("article");
    assertThat(again.get("state").asString()).isEqualTo("publicado");
    assertThat(again.get("publishedAt")).isEqualTo(before.get("publishedAt"));
    assertThat(again.get("order")).isEqualTo(before.get("order"));
    assertThat(publishedInSnapshot()).contains("calas-de-mallorca");
  }

  @Test
  void publicarConEnlacesASinPublicarEs422HastaPublicarlos() throws Exception {
    editor.client().post("/api/cms/articulos", newArticle("destino-sin-publicar", "motor",
        contentOf("anatomia-moto-de-turismo")));
    ObjectNode content = contentOf("calas-de-mallorca");
    ((ArrayNode) content.get("blocks")).add(JSON.readTree(
        "{\"type\": \"text\", \"html\": \"<p><a href=\\\"/motor/destino-sin-publicar\\\">x</a></p>\"}"));
    JsonNode saved = json(editor.client().post("/api/cms/articulos/calas-de-mallorca/revisiones",
        save(head("calas-de-mallorca"), content))).get("article");
    String before = fingerprint();
    ApiClient.Response blocked = admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(saved.get("headRevision").get("id").asLong(), saved.get("version").asInt()));
    assertThat(blocked.status()).isEqualTo(422);
    assertThat(blocked.body()).contains("enlace a un artículo que aún no está publicado");
    assertThat(fingerprint()).isEqualTo(before);
    // Publicado el enlazado, ya se puede.
    JsonNode target = detail("destino-sin-publicar").get("article");
    assertThat(admin.client().post("/api/cms/articulos/destino-sin-publicar/publicacion",
        publishBody(target.get("headRevision").get("id").asLong(), 0)).status()).isEqualTo(200);
    assertThat(admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(saved.get("headRevision").get("id").asLong(), saved.get("version").asInt())).status())
        .isEqualTo(200);
    assertThat(detail("calas-de-mallorca").get("article").get("state").asString()).isEqualTo("publicado");
  }

  @Test
  void publicarConVersionORevisionQueNoCorrespondenEsConflictoONoValido() throws Exception {
    JsonNode article = detail("calas-de-mallorca").get("article");
    long first = article.get("headRevision").get("id").asLong();
    long second = saveTitle("calas-de-mallorca", "Calas, segunda versión");
    String before = fingerprint();
    ApiClient.Response stale = admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(second, 9));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(json(stale).get("code").asString()).isEqualTo("version_conflict");
    assertThat(json(stale).get("currentVersion").asInt()).isZero();
    ApiClient.Response notHead = admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(first, 0));
    assertThat(notHead.status()).isEqualTo(409);
    assertThat(json(notHead).get("code").asString()).isEqualTo("revision_conflict");
    assertThat(json(notHead).get("currentRevisionId").asLong()).isEqualTo(second);
    long other = head("alicante-desde-el-puerto");
    ApiClient.Response foreign = admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion",
        publishBody(other, 0));
    assertThat(foreign.status()).isEqualTo(422);
    assertThat(paths(foreign)).containsExactly("revisionId");
    assertThat(admin.client().post("/api/cms/articulos/no-existe/publicacion", publishBody(1, 0)).status())
        .isEqualTo(404);
    assertThat(admin.client().post("/api/cms/articulos/calas-de-mallorca/despublicacion", "{\"version\": 9}")
        .status()).isEqualTo(409);
    assertThat(admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion", "{\"version\": 0}").status())
        .isEqualTo(422);
    assertThat(fingerprint()).isEqualTo(before);
    // Con la de trabajo y la versión actuales, sí: la restaurada o editada pasa a ser la publicada.
    assertThat(admin.client().post("/api/cms/articulos/calas-de-mallorca/publicacion", publishBody(second, 0))
        .status()).isEqualTo(200);
    assertThat(detail("calas-de-mallorca").get("article").get("state").asString()).isEqualTo("publicado");
  }

  @Test
  void publicarDespublicarYReordenarSonSoloDeAdmin() throws Exception {
    String before = fingerprint();
    long base = head("calas-de-mallorca");
    for (ApiClient.Response response : List.of(
        editor.client().post("/api/cms/articulos/calas-de-mallorca/publicacion", publishBody(base, 0)),
        editor.client().post("/api/cms/articulos/calas-de-mallorca/despublicacion", "{\"version\": 0}"),
        editor.client().put("/api/cms/secciones/viajes/orden", "{\"articles\": []}"))) {
      assertThat(response.status()).isEqualTo(403);
    }
    assertThat(fingerprint()).isEqualTo(before);
  }

  private List<JsonNode> sectionArticles(String section) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    json(editor.client().get("/api/cms/articulos?seccion=" + section + "&orden=seccion")).get("items").values()
        .forEach(out::add);
    return out;
  }

  private static String order(List<JsonNode> items) {
    ArrayNode list = JsonNodeFactory.instance.arrayNode();
    for (JsonNode item : items) {
      list.addObject().put("id", item.get("id").asString()).put("version", item.get("version").asInt());
    }
    return JsonNodeFactory.instance.objectNode().set("articles", list).toString();
  }

  @Test
  void reordenarUnaSeccionRenumeraConVersionesYAuditoria() throws Exception {
    List<JsonNode> current = sectionArticles("viajes");
    assertThat(current).hasSize(4);
    List<JsonNode> reversed = new ArrayList<>(current);
    java.util.Collections.reverse(reversed);
    ApiClient.Response reordered = admin.client().put("/api/cms/secciones/viajes/orden", order(reversed));
    assertThat(reordered.status()).as(reordered.body()).isEqualTo(200);
    JsonNode items = json(reordered).get("items");
    for (int i = 0; i < reversed.size(); i++) {
      assertThat(items.get(i).get("id").asString()).isEqualTo(reversed.get(i).get("id").asString());
      assertThat(items.get(i).get("order").asInt()).isEqualTo(i + 1);
      assertThat(items.get(i).get("version").asInt()).isEqualTo(1);
    }
    String[] audit = lastAudit();
    assertThat(audit[0]).isEqualTo("content.section_reordered");
    assertThat(JSON.readTree(audit[2]).get("section").asString()).isEqualTo("viajes");
    // Con las versiones viejas: 409 con cada conflicto, y nada cambia.
    String before = fingerprint();
    ApiClient.Response stale = admin.client().put("/api/cms/secciones/viajes/orden", order(current));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(json(stale).get("code").asString()).isEqualTo("version_conflict");
    assertThat(json(stale).get("conflicts").size()).isEqualTo(4);
    // Sin cambios: 200 y sin auditoría.
    List<JsonNode> now = sectionArticles("viajes");
    long audits = auditCount();
    assertThat(admin.client().put("/api/cms/secciones/viajes/orden", order(now)).status()).isEqualTo(200);
    assertThat(auditCount()).isEqualTo(audits);
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  void reordenarExigeLaListaExactaDeLaSeccion() throws Exception {
    List<JsonNode> current = sectionArticles("viajes");
    String before = fingerprint();
    // Falta uno.
    ApiClient.Response missing = admin.client().put("/api/cms/secciones/viajes/orden", order(current.subList(0, 3)));
    assertThat(missing.status()).isEqualTo(422);
    assertThat(missing.body()).contains("falta");
    // Uno repetido y uno de otra sección.
    List<JsonNode> wrong = new ArrayList<>(current);
    wrong.set(1, current.get(0));
    wrong.add(detail("anatomia-moto-de-turismo").get("article"));
    ApiClient.Response invalid = admin.client().put("/api/cms/secciones/viajes/orden", order(wrong));
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(invalid.body()).contains("repetido").contains("no es un artículo con orden de esta sección");
    // Un borrador sin orden tampoco entra en la lista.
    editor.client().post("/api/cms/articulos", newArticle("borrador-viajes", "viajes", contentOf("calas-de-mallorca")));
    List<JsonNode> withDraft = new ArrayList<>(current);
    withDraft.add(detail("borrador-viajes").get("article"));
    assertThat(admin.client().put("/api/cms/secciones/viajes/orden", order(withDraft)).status()).isEqualTo(422);
    // Cuerpo mal formado, sección inexistente.
    assertThat(admin.client().put("/api/cms/secciones/viajes/orden", "{\"articles\": [{\"id\": \"x\"}]}").status())
        .isEqualTo(422);
    assertThat(admin.client().put("/api/cms/secciones/deportes/orden", order(current)).status()).isEqualTo(404);
    String afterDraft = fingerprint();
    assertThat(afterDraft.split("\\|")[0]).isNotEqualTo(before.split("\\|")[0]); // solo el borrador nuevo
    assertThat(json(editor.client().get("/api/cms/articulos?seccion=viajes&orden=seccion")).get("items").get(0)
        .get("id").asString()).isEqualTo(current.get(0).get("id").asString());
  }

  // --- Lectura estricta del cuerpo -----------------------------------------------------------------

  @Test
  void cuerpoEstrictoTipoTamanoDuplicadosYBasura() throws Exception {
    String before = fingerprint();
    var textPlain = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cms/articulos"))
        .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}"));
    ApiClient.Response plain = editor.client().send(textPlain, true);
    assertThat(plain.status()).isEqualTo(415);
    ApiClient.Response duplicate = editor.client().post("/api/cms/articulos", "{\"slug\": \"a\", \"slug\": \"b\"}");
    assertThat(duplicate.status()).isEqualTo(400);
    ApiClient.Response trailing = editor.client().post("/api/cms/articulos", "{} {}");
    assertThat(trailing.status()).isEqualTo(400);
    ApiClient.Response array = editor.client().post("/api/cms/articulos", "[]");
    assertThat(array.status()).isEqualTo(422);
    ApiClient.Response huge = editor.client().post("/api/cms/articulos",
        "{\"slug\": \"" + "a".repeat(1024 * 1024) + "\"}");
    assertThat(huge.status()).isEqualTo(413);
    assertThat(json(huge).get("code").asString()).isEqualTo("payload_too_large");
    ApiClient.Response query = editor.client().post("/api/cms/articulos?x=1", "{}");
    assertThat(query.status()).isEqualTo(400);
    assertThat(fingerprint()).isEqualTo(before);
  }

  // --- Concurrencia --------------------------------------------------------------------------------

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

  @Test
  void unIdQueNoEsSlugEs404EnLasEscrituras() throws Exception {
    assertThat(editor.client().put("/api/cms/articulos/Mal/metadatos", "{}").status()).isEqualTo(404);
    assertThat(editor.client().post("/api/cms/articulos/Mal/revisiones", "{}").status()).isEqualTo(404);
  }
}
