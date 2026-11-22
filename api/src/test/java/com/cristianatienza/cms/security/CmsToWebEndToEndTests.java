package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentImporter;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Del CMS a la web pública, de extremo a extremo (fase 6.4.8): todo por la API real (sesiones,
 * passkey, CSRF y roles de la 6.2) con PostgreSQL 18.6 y el contenido real, y la WEB REAL (el
 * servidor Node del build de instantáneas, como en PublicationWebEndToEndTests de la 6.3.4).
 *
 * <p>Crear un artículo, guardar una revisión, marcarla como publicada (6.4.5), publicar una
 * instantánea con el mecanismo de la 6.3 ({@code POST /api/cms/publicaciones}), comprobar la
 * instantánea y la web; despublicar y volver a publicar. Entre cada paso se comprueba que no hay
 * estados intermedios: lo marcado en el CMS no llega a la web hasta la siguiente instantánea, una
 * revisión sin marcar no entra en ella y la publicación sigue conciliada.
 *
 * <p>Si el build de instantáneas no existe, se omite (no cuenta como superado).
 */
class CmsToWebEndToEndTests extends SecurityTestSupport {

  private static final Path SERVER = Path.of("../dist/cristian-atienza-instantaneas/server/server.mjs");
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();
  private static final ContentSource.Content CONTENT = ContentSource.read(Path.of("../src/content"));
  private static final List<String> CONTENT_FIELDS = List.of("title", "dek", "excerpt", "tags", "format", "cover",
      "author", "trip", "destinations", "project", "seo");
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String SLUG = "cala-escrita-en-el-cms";
  private static final String FIRST_TITLE = "Primer titulo del borrador";
  private static final String PUBLISHED_TITLE = "Una cala escrita desde el CMS";
  private static final String DRAFT_TITLE = "Cambio que aun no se publica";

  private static Path root;
  private static Path snapshots;
  private static Path state;
  private static int webPort;
  private static int webInternal;
  private static Process node;
  private static final StringBuilder LOG = new StringBuilder();

  private Account editor;
  private Account admin;

  /**
   * La publicación configurada con carpetas temporales fuera del repositorio (SnapshotWriter no
   * escribe dentro de uno) y la web real arrancada ANTES que la aplicación, para que la
   * reconciliación del arranque la encuentre. Sin reintentos de reconciliación durante el test.
   */
  @DynamicPropertySource
  static void publication(DynamicPropertyRegistry registry) throws Exception {
    root = Files.createTempDirectory("cms-extremo-a-extremo");
    snapshots = Files.createDirectories(root.resolve("instantaneas"));
    state = Files.createDirectories(root.resolve("estado"));
    webPort = freePort();
    webInternal = freePort();
    if (Files.exists(SERVER)) {
      startWeb();
    }
    registry.add("cms.publication.snapshots-dir", snapshots::toString);
    registry.add("cms.publication.state-dir", state::toString);
    registry.add("cms.publication.web-internal-url", () -> "http://127.0.0.1:" + webInternal);
    registry.add("cms.publication.reconcile-retry", () -> "1h");
  }

  @AfterAll
  static void stopWebAndClean() throws Exception {
    if (node != null && node.isAlive()) {
      node.destroy();
      node.waitFor(10, TimeUnit.SECONDS);
    }
    if (root != null) {
      try (Stream<Path> files = Files.walk(root)) {
        files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
    TestDatabase.migrateFromScratch();
  }

  @BeforeEach
  void freshContent() throws Exception {
    assumeTrue(Files.exists(SERVER), "sin el build de instantáneas (npm run build:snapshot)");
    waitForStartupReconciliation();
    freshSchema();
    new ContentImporter(new DriverManagerDataSource(TestDatabase.url(), "cms_tool", TestDatabase.password("cms_tool")))
        .importContent(CONTENT, CodeUsages.read(Path.of("../src/app/editorial/retired-media.ts"),
            Path.of("../src/app/layout/navigation.ts")));
    editor = fullAccount("EDITOR");
    admin = fullAccount("ADMIN");
  }

  // --- La web real ------------------------------------------------------------------------------

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void startWeb() throws Exception {
    ProcessBuilder builder = new ProcessBuilder("node", SERVER.toAbsolutePath().toString()).redirectErrorStream(true);
    builder.environment().putAll(Map.of("PORT", Integer.toString(webPort), "CONTENT_SNAPSHOTS_DIR",
        snapshots.toString(), "CONTENT_STATE_DIR", state.toString(), "WEB_INTERNAL_BIND", "127.0.0.1:" + webInternal));
    node = builder.start();
    BufferedReader reader = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
      synchronized (LOG) {
        LOG.append(line).append('\n');
      }
    }));
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      synchronized (LOG) {
        if (LOG.toString().contains("listening")) {
          return;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("la web no arranca:\n" + LOG);
  }

  /**
   * La reconciliación del arranque (PublicationStartup) corre en su propio hilo: se espera a que
   * termine antes de recrear el esquema, para que no lea ni escriba a medias.
   */
  private static void waitForStartupReconciliation() throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      boolean busy = Thread.getAllStackTraces().keySet().stream()
          .anyMatch(t -> t.getName().equals("publicacion-reconciliacion") && t.getState() == Thread.State.RUNNABLE);
      if (!busy) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("la reconciliación del arranque no termina");
  }

  private static HttpResponse<String> web(String path) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + webPort + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String webVersion() throws Exception {
    HttpResponse<String> response = web("/content/version.json");
    return response.statusCode() == 200 ? JSON.readTree(response.body()).path("version").asString()
        : "HTTP " + response.statusCode();
  }

  /** Los slugs de articles.json de una versión servida por la web. */
  private static List<String> servedSlugs(String version) throws Exception {
    HttpResponse<String> response = web("/content/" + version + "/articles.json");
    assertThat(response.statusCode()).as(version).isEqualTo(200);
    List<String> slugs = new ArrayList<>();
    JSON.readTree(response.body()).values().forEach(a -> slugs.add(a.get("slug").asString()));
    return slugs;
  }

  // --- La API -----------------------------------------------------------------------------------

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

  /** El contenido de un artículo real como lo envía el CMS (sin readingMinutes), con otro título. */
  private static ObjectNode contentWithTitle(String title) {
    JsonNode article = sourceArticle("calas-de-mallorca");
    ObjectNode content = JsonNodeFactory.instance.objectNode();
    for (String key : CONTENT_FIELDS) {
      if (article.has(key)) {
        content.set(key, article.get(key).deepCopy());
      }
    }
    content.put("title", title);
    content.set("blocks", CONTENT.bodies().get(article.get("slug").asString()).get("blocks").deepCopy());
    return content;
  }

  private JsonNode article() throws Exception {
    ApiClient.Response response = editor.client().get("/api/cms/articulos/" + SLUG);
    assertThat(response.status()).as(response.body()).isEqualTo(200);
    return json(response).get("article");
  }

  private ApiClient.Response saveRevision(String title) throws Exception {
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("baseRevisionId", article().get("headRevision").get("id").asLong());
    body.set("content", contentWithTitle(title));
    return editor.client().post("/api/cms/articulos/" + SLUG + "/revisiones", body.toString());
  }

  /**
   * Publica una instantánea con la 6.3, como ADMIN con passkey reciente: la de su inicio de sesión
   * (el test entero dura segundos, muy por debajo de los 5 minutos).
   */
  private JsonNode publishSnapshot(int expectedStatus) throws Exception {
    ApiClient.Response response = admin.client().post("/api/cms/publicaciones", "{}");
    assertThat(response.status()).as(response.body()).isEqualTo(expectedStatus);
    return json(response);
  }

  private String publications() throws Exception {
    JsonNode overview = json(admin.client().get("/api/cms/publicaciones"));
    List<String> out = new ArrayList<>();
    overview.get("publicaciones").values().forEach(p -> out.add(p.get("version").asString() + "="
        + p.get("estado").asString()));
    out.sort(Comparator.naturalOrder());
    return String.join(" ", out) + " | " + overview.get("conciliacion").get("estado").asString() + " "
        + overview.get("conciliacion").get("punteroVersion").asString();
  }

  private static long audits(String event) throws Exception {
    try (var c = connectAs("cms_security_tool");
        var ps = c.prepareStatement("SELECT count(*) FROM audit_event WHERE event = ?")) {
      ps.setString(1, event);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  // --- El ciclo completo ------------------------------------------------------------------------

  @Test
  void crearEditarPublicarServirYDespublicarHastaLaWebReal() throws Exception {
    String page = "/viajes/" + SLUG;

    // 0. Punto de partida: la instantánea v1 con el contenido real; el artículo no existe.
    assertThat(publishSnapshot(201).get("version").asString()).isEqualTo("v1");
    assertThat(webVersion()).isEqualTo("v1");
    assertThat(web(page).statusCode()).isEqualTo(404);

    // 1. Crear (EDITOR): revisión 1, borrador.
    ApiClient.Response created = editor.client().post("/api/cms/articulos", JsonNodeFactory.instance.objectNode()
        .put("slug", SLUG).put("category", "viajes").put("featured", false)
        .put("demo", sourceArticle("calas-de-mallorca").get("demo").asBoolean())
        .set("content", contentWithTitle(FIRST_TITLE)).toString());
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    assertThat(article().get("state").asString()).isEqualTo("borrador");

    // 2. Editar (EDITOR): revisión 2.
    ApiClient.Response saved = saveRevision(PUBLISHED_TITLE);
    assertThat(saved.status()).as(saved.body()).isEqualTo(201);
    JsonNode draft = article();
    assertThat(draft.get("headRevision").get("number").asInt()).isEqualTo(2);

    // 3. Marcarla como publicada (ADMIN, 6.4.5).
    ApiClient.Response marked = admin.client().post("/api/cms/articulos/" + SLUG + "/publicacion",
        "{\"revisionId\": " + draft.get("headRevision").get("id").asLong() + ", \"version\": "
            + draft.get("version").asInt() + "}");
    assertThat(marked.status()).as(marked.body()).isEqualTo(200);
    assertThat(json(marked).get("article").get("state").asString()).isEqualTo("publicado");
    // Sin estados intermedios: marcar NO publica la web; la web sigue en v1 y sin el artículo.
    assertThat(webVersion()).isEqualTo("v1");
    assertThat(web(page).statusCode()).isEqualTo(404);
    assertThat(servedSlugs("v1")).doesNotContain(SLUG);

    // 4. Publicar la instantánea (6.3): v2 la contiene con la revisión publicada.
    JsonNode v2 = publishSnapshot(201);
    assertThat(v2.get("resultado").asString()).isEqualTo("activada");
    assertThat(v2.get("version").asString()).isEqualTo("v2");
    ContentSource.Content snapshot = ContentSource.read(snapshots.resolve("v2"));
    JsonNode inSnapshot = null;
    for (JsonNode a : snapshot.collections().get("articles").values()) {
      if (a.get("slug").asString().equals(SLUG)) {
        inSnapshot = a;
      }
    }
    assertThat(inSnapshot).as("el artículo en articles.json de v2").isNotNull();
    assertThat(inSnapshot.get("title").asString()).isEqualTo(PUBLISHED_TITLE);
    assertThat(inSnapshot.get("category").asString()).isEqualTo("viajes");
    assertThat(snapshot.bodies().get(SLUG).get("blocks"))
        .isEqualTo(CONTENT.bodies().get("calas-de-mallorca").get("blocks"));
    assertThat(snapshot.collections().get("articles").size()).isEqualTo(13);

    // 5. La web real lo sirve: versión, contenido y la página renderizada.
    assertThat(webVersion()).isEqualTo("v2");
    assertThat(servedSlugs("v2")).contains(SLUG);
    assertThat(web("/content/v2/articles/" + SLUG + ".json").statusCode()).isEqualTo(200);
    HttpResponse<String> html = web(page);
    assertThat(html.statusCode()).isEqualTo(200);
    assertThat(html.body()).contains(PUBLISHED_TITLE).doesNotContain(FIRST_TITLE);
    assertThat(publications()).isEqualTo("v1=retirada v2=activa | CONCILIADO v2");

    // Una revisión sin marcar no entra: la siguiente instantánea no cambia nada.
    assertThat(saveRevision(DRAFT_TITLE).status()).isEqualTo(201);
    assertThat(article().get("state").asString()).isEqualTo("con-cambios");
    assertThat(publishSnapshot(200).get("resultado").asString()).isEqualTo("sin cambios");
    assertThat(webVersion()).isEqualTo("v2");
    assertThat(web(page).body()).contains(PUBLISHED_TITLE).doesNotContain(DRAFT_TITLE);

    // 6. Despublicar (ADMIN). Hasta la siguiente instantánea, la web sigue sirviendo v2 entera.
    ApiClient.Response unpublished = admin.client().post("/api/cms/articulos/" + SLUG + "/despublicacion",
        "{\"version\": " + article().get("version").asInt() + "}");
    assertThat(unpublished.status()).as(unpublished.body()).isEqualTo(200);
    assertThat(json(unpublished).get("article").get("state").asString()).isEqualTo("borrador");
    assertThat(webVersion()).isEqualTo("v2");
    assertThat(web(page).statusCode()).isEqualTo(200);

    // 7. Publicar v3: el artículo deja de estar publicado en la web.
    assertThat(publishSnapshot(201).get("version").asString()).isEqualTo("v3");
    assertThat(webVersion()).isEqualTo("v3");
    assertThat(web(page).statusCode()).isEqualTo(404);
    assertThat(servedSlugs("v3")).doesNotContain(SLUG).hasSize(12);
    assertThat(web("/content/v3/articles/" + SLUG + ".json").statusCode()).isEqualTo(404);
    assertThat(ContentSource.read(snapshots.resolve("v3")).bodies()).doesNotContainKey(SLUG);
    assertThat(publications()).isEqualTo("v1=retirada v2=retirada v3=activa | CONCILIADO v3");
    // La anterior (v2) se sigue sirviendo por su ruta (6.3, Q1), aunque la página ya dé 404.
    assertThat(web("/content/v2/articles/" + SLUG + ".json").statusCode()).isEqualTo(200);

    // 8. Limitación conocida (puerta de la 6.3): despublicar el ÚNICO artículo que usa una imagen la
    // deja «sin usar» y la puerta rechaza la instantánea entera. Es atómico: la web no cambia.
    String exclusive = "hora-azul-notas-de-exposicion";
    JsonNode other = json(editor.client().get("/api/cms/articulos/" + exclusive)).get("article");
    assertThat(admin.client().post("/api/cms/articulos/" + exclusive + "/despublicacion",
        "{\"version\": " + other.get("version").asInt() + "}").status()).isEqualTo(200);
    JsonNode rejected = publishSnapshot(422);
    assertThat(rejected.get("resultado").asString()).isEqualTo("rechazada");
    assertThat(rejected.get("incidencias").toString()).contains("imagen sin usar «paisaje-hora-azul-dique»");
    assertThat(webVersion()).isEqualTo("v3");
    assertThat(web("/fotografia/" + exclusive).statusCode()).isEqualTo(200);
    assertThat(publications()).contains("v3=activa").endsWith("| CONCILIADO v3");
    // Volver a marcarlo deja lo publicado como está en v3: «sin cambios».
    JsonNode back = json(editor.client().get("/api/cms/articulos/" + exclusive)).get("article");
    assertThat(admin.client().post("/api/cms/articulos/" + exclusive + "/publicacion", "{\"revisionId\": "
        + back.get("headRevision").get("id").asLong() + ", \"version\": " + back.get("version").asInt() + "}")
        .status()).isEqualTo(200);
    assertThat(publishSnapshot(200).get("resultado").asString()).isEqualTo("sin cambios");
    assertThat(webVersion()).isEqualTo("v3");

    // La auditoría registra cada paso, una vez.
    assertThat(audits("content.article_created")).isEqualTo(1);
    assertThat(audits("content.revision_created")).isEqualTo(2);
    assertThat(audits("content.article_published")).isEqualTo(2);
    assertThat(audits("content.article_unpublished")).isEqualTo(2);
    assertThat(audits("content.publish_rejected")).isEqualTo(1);
    assertThat(audits("content.published")).isEqualTo(3);
  }
}
