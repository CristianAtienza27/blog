package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentComparator;
import com.cristianatienza.cms.content.io.ContentExporter;
import com.cristianatienza.cms.content.io.ContentImporter;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ida y vuelta de la exportación tras cambios del CMS (fase 6.4.8), con el exportador real
 * ({@code ContentExporter.export}, el de la herramienta y la 6.3) y la API real: exportar el
 * contenido real, hacer cambios editoriales controlados por la API (una revisión publicada de un
 * artículo, un lugar editado y una revisión sin publicar) y volver a exportar. La segunda
 * exportación difiere SOLO en lo cambiado; el resto de archivos es idéntico byte a byte; y en la base
 * de datos no cambia ninguna fila ajena (artículos, revisiones, relaciones y catálogos).
 */
class EditorialExportRoundTripTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));
  private static final List<String> CONTENT_FIELDS = List.of("title", "dek", "excerpt", "tags", "format", "cover",
      "author", "trip", "destinations", "project", "seo");
  /** Todas las tablas del contenido (V1 y V2). */
  private static final List<String> TABLES = List.of("category", "tag", "media", "media_original",
      "media_reserved_use", "author", "gallery", "gallery_item", "destination", "trip", "trip_stop", "project",
      "project_stack_item", "article", "article_revision", "article_revision_tag", "article_revision_destination",
      "article_revision_block_ref");
  private static final List<String> REVISION_CHILDREN = List.of("article_revision_tag",
      "article_revision_destination", "article_revision_block_ref");
  private static final String ARTICLE = "calas-de-mallorca";
  private static final String NEW_TITLE = "Calas de Mallorca, revisadas desde el CMS";
  private static final String NEW_SUMMARY = "Resumen editado desde el CMS.";

  @TempDir Path temp;
  private Account editor;
  private Account admin;

  @BeforeEach
  void freshContent() throws Exception {
    freshSchema();
    new ContentImporter(exporter()).importContent(CONTENT, CodeUsages.read(
        Path.of("../src/app/editorial/retired-media.ts"), Path.of("../src/app/layout/navigation.ts")));
    editor = fullAccount("EDITOR");
    admin = fullAccount("ADMIN");
  }

  @AfterAll
  static void cleanSchema() {
    TestDatabase.migrateFromScratch();
  }

  /** El login de la herramienta (cms_tool): el exportador entra con SET ROLE cms_importer. */
  private static DriverManagerDataSource exporter() {
    return new DriverManagerDataSource(TestDatabase.url(), "cms_tool", TestDatabase.password("cms_tool"));
  }

  private static JsonNode json(ApiClient.Response response) {
    return JSON.readTree(response.body());
  }

  private JsonNode article() throws Exception {
    return json(editor.client().get("/api/cms/articulos/" + ARTICLE)).get("article");
  }

  /** El contenido de la revisión de trabajo como lo envía el CMS, con otro título. */
  private static ObjectNode contentWithTitle(String title) {
    JsonNode source = null;
    for (JsonNode a : CONTENT.collections().get("articles").values()) {
      if (a.get("id").asString().equals(ARTICLE)) {
        source = a;
      }
    }
    ObjectNode content = JsonNodeFactory.instance.objectNode();
    for (String key : CONTENT_FIELDS) {
      if (source.has(key)) {
        content.set(key, source.get(key).deepCopy());
      }
    }
    content.put("title", title);
    content.set("blocks", CONTENT.bodies().get(ARTICLE).get("blocks").deepCopy());
    return content;
  }

  private long saveRevision(String title) throws Exception {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("baseRevisionId", article().get("headRevision").get("id").asLong());
    body.set("content", contentWithTitle(title));
    ApiClient.Response saved = editor.client().post("/api/cms/articulos/" + ARTICLE + "/revisiones", body.toString());
    assertThat(saved.status()).as(saved.body()).isEqualTo(201);
    return article().get("headRevision").get("id").asLong();
  }

  /** Cada fila de cada tabla del contenido, como texto (sin columnas de sistema como xmin). */
  private static Map<String, TreeSet<String>> rows(String where) throws Exception {
    Map<String, TreeSet<String>> out = new LinkedHashMap<>();
    try (Connection c = connectAs("cms_tool"); Statement s = c.createStatement()) {
      s.execute("SET ROLE cms_importer");
      for (String table : TABLES) {
        TreeSet<String> set = new TreeSet<>();
        String filter = where != null && (table.equals("article_revision") || REVISION_CHILDREN.contains(table))
            ? " WHERE " + (table.equals("article_revision") ? "id" : "revision_id") + " " + where : "";
        try (ResultSet rs = s.executeQuery("SELECT x::text FROM " + table + " x" + filter)) {
          while (rs.next()) {
            set.add(rs.getString(1));
          }
        }
        out.put(table, set);
      }
    }
    return out;
  }

  private static long maxRevision() throws Exception {
    try (Connection c = connectAs("cms_tool"); Statement s = c.createStatement()) {
      s.execute("SET ROLE cms_importer");
      try (ResultSet rs = s.executeQuery("SELECT max(id) FROM article_revision")) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static int indexOf(ContentSource.Content content, String collection, String id) {
    int i = 0;
    for (JsonNode r : content.collections().get(collection).values()) {
      if (r.get("id").asString().equals(id)) {
        return i;
      }
      i++;
    }
    throw new IllegalArgumentException(id);
  }

  @Test
  void laSegundaExportacionSoloCambiaLoEditadoYNadaMasCambiaEnLaBaseDeDatos() throws Exception {
    // 1. Exportar lo existente: idéntico al contenido real.
    Path first = temp.resolve("primera");
    ContentExporter.export(exporter(), first);
    ContentSource.Content before = ContentSource.read(first);
    assertThat(ContentComparator.compare(CONTENT, before)).isEmpty();
    Map<String, String> filesBefore = ContentSource.fingerprint(first);
    Map<String, TreeSet<String>> rowsBefore = rows(null);
    long lastRevision = maxRevision();
    String oldTitle = before.collections().get("articles").get(indexOf(before, "articles", ARTICLE))
        .get("title").asString();
    String oldSummary = before.collections().get("destinations").get(indexOf(before, "destinations", "alicante"))
        .get("summary").asString();

    // 2. Cambios editoriales controlados, por la API: una revisión nueva publicada...
    long published = saveRevision(NEW_TITLE);
    JsonNode draft = article();
    ApiClient.Response marked = admin.client().post("/api/cms/articulos/" + ARTICLE + "/publicacion",
        "{\"revisionId\": " + published + ", \"version\": " + draft.get("version").asInt() + "}");
    assertThat(marked.status()).as(marked.body()).isEqualTo(200);
    // ...un lugar editado...
    JsonNode place = json(editor.client().get("/api/cms/lugares/alicante"));
    ObjectNode change = ((ObjectNode) place.get("record")).deepCopy();
    change.remove("id");
    change.put("version", place.get("version").asString());
    change.put("summary", NEW_SUMMARY);
    assertThat(editor.client().put("/api/cms/lugares/alicante", change.toString()).status()).isEqualTo(200);
    // ...y una revisión de trabajo sin publicar, que no debe exportarse.
    long unpublished = saveRevision("Borrador que no se exporta");

    // 3. Volver a exportar.
    Path second = temp.resolve("segunda");
    ContentExporter.export(exporter(), second);
    ContentSource.Content after = ContentSource.read(second);

    // 4. Refleja los cambios, y SOLO ellos.
    int a = indexOf(before, "articles", ARTICLE);
    int d = indexOf(before, "destinations", "alicante");
    assertThat(ContentComparator.compare(before, after)).containsExactlyInAnyOrder(
        "articles[" + a + "].title: \"" + oldTitle + "\" ≠ \"" + NEW_TITLE + "\"",
        "destinations[" + d + "].summary: \"" + oldSummary + "\" ≠ \"" + NEW_SUMMARY + "\"");
    assertThat(after.bodies()).hasSize(12);
    assertThat(after.bodies().get(ARTICLE)).isEqualTo(before.bodies().get(ARTICLE));

    // 5. El resto de archivos, idéntico byte a byte (22: 9 colecciones, 12 cuerpos y el marcador).
    Map<String, String> filesAfter = ContentSource.fingerprint(second);
    assertThat(filesAfter.keySet()).isEqualTo(filesBefore.keySet());
    List<String> changedFiles = new ArrayList<>();
    filesBefore.forEach((file, hash) -> {
      if (!hash.equals(filesAfter.get(file))) {
        changedFiles.add(file);
      }
    });
    assertThat(changedFiles).containsExactlyInAnyOrder("articles.json", "destinations.json");

    // 6. En la base de datos, ninguna fila ajena cambia.
    Map<String, TreeSet<String>> rowsAfter = rows(null);
    Map<String, TreeSet<String>> oldRevisionRows = rows("<= " + lastRevision);
    for (String table : TABLES) {
      TreeSet<String> removed = new TreeSet<>(rowsBefore.get(table));
      removed.removeAll(rowsAfter.get(table));
      TreeSet<String> added = new TreeSet<>(rowsAfter.get(table));
      added.removeAll(rowsBefore.get(table));
      switch (table) {
        // La fila del artículo (punteros y versión) y la del lugar: una sustituida, ninguna más.
        case "article" -> {
          assertThat(removed).as(table).hasSize(1).allMatch(r -> r.startsWith("(" + ARTICLE + ","));
          assertThat(added).as(table).hasSize(1).allMatch(r -> r.startsWith("(" + ARTICLE + ","));
        }
        case "destination" -> {
          assertThat(removed).as(table).hasSize(1).allMatch(r -> r.startsWith("(alicante,"));
          assertThat(added).as(table).hasSize(1).allMatch(r -> r.startsWith("(alicante,"));
        }
        // Revisiones: solo se añaden (las dos nuevas); las anteriores, intactas.
        case "article_revision" -> {
          assertThat(removed).as(table).isEmpty();
          assertThat(added).as(table).hasSize(2)
              .allMatch(r -> r.startsWith("(" + published + ",") || r.startsWith("(" + unpublished + ","));
          assertThat(oldRevisionRows.get(table)).as(table).isEqualTo(rowsBefore.get(table));
        }
        case "article_revision_tag", "article_revision_destination", "article_revision_block_ref" -> {
          assertThat(removed).as(table).isEmpty();
          assertThat(oldRevisionRows.get(table)).as(table).isEqualTo(rowsBefore.get(table));
        }
        default -> {
          assertThat(removed).as(table).isEmpty();
          assertThat(added).as(table).isEmpty();
        }
      }
    }
  }
}
