package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentImporter;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lectura editorial (fase 6.4.2) con la API real, PostgreSQL 18.6 y el contenido real importado por
 * cms_tool: listado con filtros, orden y paginación, detalle, historial, revisiones, catálogos,
 * validación estricta de los parámetros, errores RFC 9457 y que ninguna lectura cambia nada.
 *
 * <p>Los estados «borrador» y «con-cambios» se preparan con las operaciones que la base de datos ya
 * concede a cms_api (INSERT de article y create_article_revision); los endpoints de escritura son de
 * la 6.4.3. Al terminar, el esquema se deja limpio para las demás clases.
 */
class EditorialReadEndpointTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));
  private static final List<String> RETIRED = CodeUsages.read(Path.of("../src/app/editorial/retired-media.ts"),
      Path.of("../src/app/layout/navigation.ts")).retired();
  private static final List<String> CONTENT_ORDER = List.of("title", "dek", "excerpt", "tags", "format", "cover",
      "author", "readingMinutes", "trip", "destinations", "project", "seo", "blocks");
  private static boolean prepared;

  private ApiClient editor;

  @BeforeEach
  void contentAndEditor() throws Exception {
    if (!prepared) {
      // Esquema limpio (como un volumen nuevo) y el contenido real, una vez por clase.
      freshSchema();
      new ContentImporter(new DriverManagerDataSource(TestDatabase.url(), "cms_tool", TestDatabase.password("cms_tool")))
          .importContent(CONTENT, CodeUsages.read(Path.of("../src/app/editorial/retired-media.ts"),
              Path.of("../src/app/layout/navigation.ts")));
      prepared = true;
    }
    editor = fullAccount("EDITOR").client();
  }

  @AfterAll
  static void cleanSchema() {
    TestDatabase.migrateFromScratch();
  }

  private JsonNode json(ApiClient.Response response) {
    return JSON.readTree(response.body());
  }

  private JsonNode ok(String path) throws Exception {
    ApiClient.Response response = editor.get(path);
    assertThat(response.status()).as(path + " " + response.body()).isEqualTo(200);
    assertThat(response.header("Cache-Control")).as(path).isEqualTo("no-store");
    return json(response);
  }

  private static List<String> ids(JsonNode items, String field) {
    List<String> out = new ArrayList<>();
    for (JsonNode item : items.values()) {
      out.add(item.get(field).asString());
    }
    return out;
  }

  private static List<String> sourceIds(String collection) {
    return ids(CONTENT.collections().get(collection), "id");
  }

  private static JsonNode sourceArticle(String id) {
    for (JsonNode a : CONTENT.collections().get("articles").values()) {
      if (a.get("id").asString().equals(id)) {
        return a;
      }
    }
    throw new IllegalArgumentException(id);
  }

  /** El contenido que debe devolver la API: los campos de la revisión del artículo y sus bloques. */
  private static ObjectNode expectedContent(String id) {
    JsonNode article = sourceArticle(id);
    ObjectNode expected = JsonNodeFactory.instance.objectNode();
    for (String key : CONTENT_ORDER) {
      if (article.has(key)) {
        expected.set(key, article.get(key));
      }
    }
    expected.set("blocks", CONTENT.bodies().get(article.get("slug").asString()).get("blocks"));
    return expected;
  }

  private static void asApi(String sql) throws Exception {
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  /** Huella de todo el contenido editorial: para comprobar que las lecturas no cambian nada. */
  private static String contentFingerprint() throws Exception {
    try (Connection c = connectAs("cms_api"); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT concat_ws('|',"
            + " (SELECT string_agg(id || ':' || lock_version || ':' || coalesce(head_revision_id, 0) || ':'"
            + "   || coalesce(published_revision_id, 0), ',' ORDER BY id) FROM article),"
            + " (SELECT count(*) FROM article_revision), (SELECT count(*) FROM tag), (SELECT count(*) FROM media))")) {
      rs.next();
      return rs.getString(1);
    }
  }

  // --- Listado ---------------------------------------------------------------------------------

  @Test
  void listadoPorDefectoConLosDoceArticulosPublicados() throws Exception {
    JsonNode page = ok("/api/cms/articulos");
    assertThat(page.get("total").asLong()).isEqualTo(12);
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(20);
    assertThat(ids(page.get("items"), "id")).containsExactlyInAnyOrderElementsOf(sourceIds("articles"));
    for (JsonNode item : page.get("items").values()) {
      JsonNode source = sourceArticle(item.get("id").asString());
      assertThat(item.get("state").asString()).isEqualTo("publicado");
      assertThat(item.get("slug").asString()).isEqualTo(source.get("slug").asString());
      assertThat(item.get("category").asString()).isEqualTo(source.get("category").asString());
      assertThat(item.get("title").asString()).isEqualTo(source.get("title").asString());
      assertThat(item.get("format").asString()).isEqualTo(source.get("format").asString());
      assertThat(item.get("author").asString()).isEqualTo(source.get("author").asString());
      assertThat(item.get("cover").isNull() ? null : item.get("cover").asString())
          .isEqualTo(source.has("cover") ? source.get("cover").asString() : null);
      assertThat(item.get("tags")).isEqualTo(source.get("tags"));
      assertThat(item.get("order").asInt()).isEqualTo(source.get("order").asInt());
      assertThat(item.get("featured").asBoolean()).isEqualTo(source.get("featured").asBoolean());
      assertThat(item.get("demo").asBoolean()).isEqualTo(source.get("demo").asBoolean());
      assertThat(item.get("publishedAt").asString()).isEqualTo(source.get("publishedAt").asString());
      assertThat(item.get("updatedAt").isNull() ? null : item.get("updatedAt").asString())
          .isEqualTo(source.has("updatedAt") ? source.get("updatedAt").asString() : null);
      assertThat(item.get("version").asInt()).isZero();
      assertThat(item.get("headRevision").get("number").asInt()).isEqualTo(1);
      assertThat(item.get("headRevision").get("origin").asString()).isEqualTo("import");
      assertThat(item.get("headRevision").get("restoredFrom").isNull()).isTrue();
      assertThat(item.get("publishedRevision").get("id")).isEqualTo(item.get("headRevision").get("id"));
    }
  }

  @Test
  void filtrosPorSeccionEtiquetaTextoDemoYDestacado() throws Exception {
    JsonNode articles = CONTENT.collections().get("articles");
    for (String section : List.of("viajes", "fotografia", "motor", "experiencias", "tecnologia")) {
      List<String> expected = new ArrayList<>();
      articles.values().forEach(a -> {
        if (a.get("category").asString().equals(section)) {
          expected.add(a.get("id").asString());
        }
      });
      assertThat(ids(ok("/api/cms/articulos?seccion=" + section).get("items"), "id")).as(section)
          .containsExactlyInAnyOrderElementsOf(expected);
    }
    for (String tag : List.of("costa", "angular", "paisaje")) {
      List<String> expected = new ArrayList<>();
      articles.values().forEach(a -> {
        for (JsonNode t : a.get("tags").values()) {
          if (t.asString().equals(tag)) {
            expected.add(a.get("id").asString());
          }
        }
      });
      assertThat(expected).as(tag).isNotEmpty();
      assertThat(ids(ok("/api/cms/articulos?etiqueta=" + tag).get("items"), "id")).as(tag)
          .containsExactlyInAnyOrderElementsOf(expected);
    }
    for (String flag : List.of("true", "false")) {
      List<String> demo = new ArrayList<>();
      List<String> featured = new ArrayList<>();
      articles.values().forEach(a -> {
        if (String.valueOf(a.get("demo").asBoolean()).equals(flag)) {
          demo.add(a.get("id").asString());
        }
        if (String.valueOf(a.get("featured").asBoolean()).equals(flag)) {
          featured.add(a.get("id").asString());
        }
      });
      assertThat(ids(ok("/api/cms/articulos?demo=" + flag + "&size=100").get("items"), "id"))
          .containsExactlyInAnyOrderElementsOf(demo);
      assertThat(ids(ok("/api/cms/articulos?destacado=" + flag + "&size=100").get("items"), "id"))
          .containsExactlyInAnyOrderElementsOf(featured);
    }
    // Texto: en el título o el slug, sin distinguir mayúsculas; los comodines de LIKE son literales.
    assertThat(ids(ok("/api/cms/articulos?q=MALLORCA").get("items"), "id")).containsExactly("calas-de-mallorca");
    assertThat(ids(ok("/api/cms/articulos?q=costa-brava").get("items"), "id")).containsExactly("itinerario-costa-brava");
    assertThat(ok("/api/cms/articulos?q=%25").get("total").asLong()).isZero();
    assertThat(ok("/api/cms/articulos?q=_").get("total").asLong()).isZero();
    // Combinados.
    assertThat(ids(ok("/api/cms/articulos?seccion=viajes&etiqueta=costa&q=costa").get("items"), "id"))
        .containsExactly("itinerario-costa-brava");
  }

  @Test
  void paginacionEstableSinHuecosNiRepeticiones() throws Exception {
    Set<String> seen = new HashSet<>();
    List<String> all = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      JsonNode result = ok("/api/cms/articulos?size=5&page=" + page);
      assertThat(result.get("total").asLong()).isEqualTo(12);
      assertThat(result.get("items").size()).isEqualTo(page < 2 ? 5 : 2);
      for (String id : ids(result.get("items"), "id")) {
        assertThat(seen.add(id)).as(id).isTrue();
        all.add(id);
      }
    }
    assertThat(all).containsExactlyInAnyOrderElementsOf(sourceIds("articles"));
    JsonNode beyond = ok("/api/cms/articulos?size=5&page=3");
    assertThat(beyond.get("items").size()).isZero();
    assertThat(beyond.get("total").asLong()).isEqualTo(12);
    assertThat(ok("/api/cms/articulos?size=100").get("items").size()).isEqualTo(12);
  }

  @Test
  void ordenPorSeccionYPorPublicacion() throws Exception {
    JsonNode bySection = ok("/api/cms/articulos?orden=seccion").get("items");
    List<String> sections = List.of("viajes", "fotografia", "motor", "experiencias", "tecnologia");
    for (int i = 1; i < bySection.size(); i++) {
      JsonNode previous = bySection.get(i - 1);
      JsonNode current = bySection.get(i);
      int a = sections.indexOf(previous.get("category").asString());
      int b = sections.indexOf(current.get("category").asString());
      assertThat(a < b || (a == b && previous.get("order").asInt() < current.get("order").asInt()))
          .as(previous.get("id") + " antes que " + current.get("id")).isTrue();
    }
    JsonNode byDate = ok("/api/cms/articulos?orden=publicado").get("items");
    for (int i = 1; i < byDate.size(); i++) {
      assertThat(byDate.get(i - 1).get("publishedAt").asString()).isGreaterThanOrEqualTo(
          byDate.get(i).get("publishedAt").asString());
    }
    assertThat(ok("/api/cms/articulos?orden=titulo").get("items").size()).isEqualTo(12);
  }

  @Test
  void estadosDerivadosBorradorYConCambios() throws Exception {
    // Una revisión nueva de un artículo publicado: «con-cambios».
    asApi("SELECT * FROM create_article_revision('calas-de-mallorca',"
        + " (SELECT head_revision_id FROM article WHERE id = 'calas-de-mallorca'), 'edit',"
        + " article_revision_content((SELECT head_revision_id FROM article WHERE id = 'calas-de-mallorca'))"
        + "   || jsonb_build_object('title', 'Calas de Mallorca (cambios)'))");
    // Un artículo nuevo sin publicar: «borrador».
    asApi("INSERT INTO article (id, slug, category_id, featured, demo, catalog_position)"
        + " VALUES ('borrador-de-prueba', 'borrador-de-prueba', 'motor', false, true, 13)");
    asApi("SELECT * FROM create_article_revision('borrador-de-prueba', NULL, 'edit',"
        + " article_revision_content((SELECT head_revision_id FROM article WHERE id = 'anatomia-moto-de-turismo'))"
        + "   || jsonb_build_object('title', 'Un borrador'))");
    try {
      assertThat(ids(ok("/api/cms/articulos?estado=con-cambios").get("items"), "id")).containsExactly("calas-de-mallorca");
      assertThat(ids(ok("/api/cms/articulos?estado=borrador").get("items"), "id")).containsExactly("borrador-de-prueba");
      assertThat(ok("/api/cms/articulos?estado=publicado").get("total").asLong()).isEqualTo(11);
      JsonNode changed = ok("/api/cms/articulos/calas-de-mallorca").get("article");
      assertThat(changed.get("state").asString()).isEqualTo("con-cambios");
      assertThat(changed.get("title").asString()).isEqualTo("Calas de Mallorca (cambios)");
      assertThat(changed.get("headRevision").get("number").asInt()).isEqualTo(2);
      assertThat(changed.get("headRevision").get("origin").asString()).isEqualTo("edit");
      assertThat(changed.get("publishedRevision").get("number").asInt()).isEqualTo(1);
      JsonNode draft = ok("/api/cms/articulos/borrador-de-prueba").get("article");
      assertThat(draft.get("state").asString()).isEqualTo("borrador");
      assertThat(draft.get("publishedRevision").isNull()).isTrue();
      assertThat(draft.get("order").isNull()).isTrue();
      assertThat(draft.get("publishedAt").isNull()).isTrue();
      assertThat(ok("/api/cms/articulos?q=un%20borrador").get("total").asLong()).isEqualTo(1);

      // Historial: de la más reciente a la primera; la de trabajo y la publicada marcadas.
      JsonNode history = ok("/api/cms/articulos/calas-de-mallorca/revisiones");
      assertThat(history.get("total").asLong()).isEqualTo(2);
      assertThat(history.get("items").get(0).get("number").asInt()).isEqualTo(2);
      assertThat(history.get("items").get(0).get("head").asBoolean()).isTrue();
      assertThat(history.get("items").get(0).get("published").asBoolean()).isFalse();
      assertThat(history.get("items").get(1).get("number").asInt()).isEqualTo(1);
      assertThat(history.get("items").get(1).get("head").asBoolean()).isFalse();
      assertThat(history.get("items").get(1).get("published").asBoolean()).isTrue();
      assertThat(ok("/api/cms/articulos/calas-de-mallorca/revisiones?size=1&page=1").get("items").get(0)
          .get("number").asInt()).isEqualTo(1);
      // Cada revisión, con su contenido: la 1 sigue siendo la original.
      assertThat(ok("/api/cms/articulos/calas-de-mallorca/revisiones/1").get("content"))
          .isEqualTo(expectedContent("calas-de-mallorca"));
      ObjectNode second = expectedContent("calas-de-mallorca");
      second.put("title", "Calas de Mallorca (cambios)");
      assertThat(ok("/api/cms/articulos/calas-de-mallorca/revisiones/2").get("content")).isEqualTo(second);
      assertThat(editor.get("/api/cms/articulos/calas-de-mallorca/revisiones/3").status()).isEqualTo(404);
    } finally {
      // Las revisiones no se borran: el esquema se limpia y se vuelve a importar el contenido.
      prepared = false;
    }
  }

  // --- Detalle ---------------------------------------------------------------------------------

  @Test
  void detalleDeLosDoceConSuContenidoEnElOrdenDelContrato() throws Exception {
    for (String id : sourceIds("articles")) {
      JsonNode detail = ok("/api/cms/articulos/" + id);
      assertThat(detail.get("article").get("id").asString()).isEqualTo(id);
      JsonNode content = detail.get("content");
      assertThat(content).as(id).isEqualTo(expectedContent(id));
      List<String> keys = new ArrayList<>(content.propertyNames());
      assertThat(keys).as(id).isEqualTo(CONTENT_ORDER.stream().filter(content::has).toList());
      assertThat(content.get("readingMinutes")).isEqualTo(sourceArticle(id).get("readingMinutes"));
    }
  }

  @Test
  void historialDeUnArticuloImportado() throws Exception {
    JsonNode history = ok("/api/cms/articulos/alicante-desde-el-puerto/revisiones");
    assertThat(history.get("total").asLong()).isEqualTo(1);
    JsonNode first = history.get("items").get(0);
    assertThat(first.get("number").asInt()).isEqualTo(1);
    assertThat(first.get("origin").asString()).isEqualTo("import");
    assertThat(first.get("head").asBoolean()).isTrue();
    assertThat(first.get("published").asBoolean()).isTrue();
    assertThat(first.get("createdAt").asString()).endsWith("Z");
    JsonNode revision = ok("/api/cms/articulos/alicante-desde-el-puerto/revisiones/1");
    assertThat(revision.get("revision").get("id")).isEqualTo(first.get("id"));
    assertThat(revision.get("content")).isEqualTo(expectedContent("alicante-desde-el-puerto"));
  }

  @Test
  void inexistenteOMalFormadoEs404SinDistinguirlos() throws Exception {
    for (String path : List.of("/api/cms/articulos/no-existe", "/api/cms/articulos/Mayusculas",
        "/api/cms/articulos/a--b", "/api/cms/articulos/no-existe/revisiones",
        "/api/cms/articulos/no-existe/revisiones/1", "/api/cms/articulos/alicante-desde-el-puerto/revisiones/0",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/2",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/abc",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/01",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/99999999999")) {
      ApiClient.Response response = editor.get(path);
      assertThat(response.status()).as(path).isEqualTo(404);
      JsonNode problem = json(response);
      assertThat(problem.get("code").asString()).as(path).isEqualTo("not_found");
      assertThat(response.header("Content-Type")).as(path).startsWith("application/problem+json");
    }
  }

  // --- Parámetros y errores ----------------------------------------------------------------------

  @Test
  void parametrosNoValidosSon400ConTodasLasIncidencias() throws Exception {
    ApiClient.Response response = editor.get("/api/cms/articulos?estado=publicada&seccion=deportes&etiqueta=Costa"
        + "&demo=si&destacado=1&orden=fecha&page=-1&size=101&q=%20%20&otro=1&page=2");
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.header("Content-Type")).startsWith("application/problem+json");
    JsonNode problem = json(response);
    assertThat(problem.get("status").asInt()).isEqualTo(400);
    assertThat(problem.get("code").asString()).isEqualTo("invalid_parameters");
    assertThat(problem.get("correlationId").asString()).isNotBlank();
    assertThat(problem.has("errors")).isTrue();
    Map<String, String> errors = new java.util.LinkedHashMap<>();
    for (JsonNode error : problem.get("errors").values()) {
      errors.put(error.get("path").asString(), error.get("message").asString());
    }
    assertThat(errors).containsOnlyKeys("estado", "seccion", "etiqueta", "demo", "destacado", "orden", "page", "size",
        "q", "otro");
    assertThat(errors.get("page")).isEqualTo("parámetro repetido");
    assertThat(errors.get("otro")).isEqualTo("parámetro desconocido");
    assertThat(errors.get("size")).isEqualTo("se esperaba un entero entre 1 y 100");
    assertThat(errors.get("q")).isEqualTo("entre 1 y 100 caracteres");

    for (String query : List.of("size=0", "size=abc", "page=-1", "page=10001", "page=1.5", "q=" + "a".repeat(101),
        "estado=", "demo=TRUE")) {
      ApiClient.Response bad = editor.get("/api/cms/articulos?" + query);
      assertThat(bad.status()).as(query).isEqualTo(400);
      assertThat(json(bad).get("code").asString()).as(query).isEqualTo("invalid_parameters");
    }
    // Las rutas sin parámetros tampoco admiten parámetros desconocidos.
    for (String path : List.of("/api/cms/articulos/alicante-desde-el-puerto?x=1", "/api/cms/etiquetas?page=1",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones?estado=borrador",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/1?x=1")) {
      assertThat(editor.get(path).status()).as(path).isEqualTo(400);
    }
  }

  // --- Catálogos -------------------------------------------------------------------------------

  @Test
  void catalogosCompletosEnElOrdenDelContenido() throws Exception {
    Map<String, String> catalogs = Map.of("categorias", "categories", "etiquetas", "tags", "autores", "authors",
        "lugares", "destinations", "itinerarios", "trips", "proyectos", "projects", "imagenes", "media",
        "galerias", "galleries");
    for (var entry : catalogs.entrySet()) {
      JsonNode list = ok("/api/cms/" + entry.getKey());
      assertThat(ids(list, "id")).as(entry.getKey()).containsExactlyElementsOf(sourceIds(entry.getValue()));
    }
    // Las paradas de cada itinerario, en orden.
    JsonNode trips = ok("/api/cms/itinerarios");
    for (int i = 0; i < trips.size(); i++) {
      List<String> stops = new ArrayList<>();
      CONTENT.collections().get("trips").get(i).get("stops").values()
          .forEach(s -> stops.add(s.get("destination").asString()));
      assertThat(TextList.of(trips.get(i).get("stops"))).isEqualTo(stops);
    }
    // Imágenes: retiradas marcadas y NUNCA datos del original.
    ApiClient.Response media = editor.get("/api/cms/imagenes");
    JsonNode images = json(media);
    for (JsonNode image : images.values()) {
      assertThat(image.get("retired").asBoolean()).as(image.get("id").asString())
          .isEqualTo(RETIRED.contains(image.get("id").asString()));
      assertThat(new ArrayList<>(image.propertyNames()))
          .containsExactly("id", "path", "width", "height", "alt", "retired", "demo");
    }
    for (JsonNode source : CONTENT.collections().get("media").values()) {
      JsonNode original = source.get("original");
      assertThat(media.body()).doesNotContain(original.get("sha256").asString()).doesNotContain(
          original.get("file").asString());
    }
    assertThat(media.body()).doesNotContain("sha256");
  }

  @Test
  void ningunaLecturaCambiaElContenido() throws Exception {
    String before = contentFingerprint();
    for (String path : List.of("/api/cms/articulos", "/api/cms/articulos?estado=borrador&q=x",
        "/api/cms/articulos/alicante-desde-el-puerto", "/api/cms/articulos/alicante-desde-el-puerto/revisiones",
        "/api/cms/articulos/alicante-desde-el-puerto/revisiones/1", "/api/cms/categorias", "/api/cms/etiquetas",
        "/api/cms/autores", "/api/cms/lugares", "/api/cms/itinerarios", "/api/cms/proyectos", "/api/cms/imagenes",
        "/api/cms/galerias")) {
      ok(path);
    }
    assertThat(contentFingerprint()).isEqualTo(before);
  }

  /** Lista de textos de un nodo JSON. */
  private static final class TextList {
    static List<String> of(JsonNode list) {
      List<String> out = new ArrayList<>();
      list.values().forEach(v -> out.add(v.asString()));
      return out;
    }
  }
}
