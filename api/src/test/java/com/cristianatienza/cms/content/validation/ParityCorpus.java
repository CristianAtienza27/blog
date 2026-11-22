package com.cristianatienza.cms.content.validation;

import com.cristianatienza.cms.content.io.CodeUsages;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.content.validation.ArticleValidator.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * El corpus compartido con TypeScript (api/src/test/resources/contract-cases) y el contenido real de
 * src/content: construye cada caso EXACTAMENTE como contract-parity.spec.ts (mismo artículo base, mismas
 * claves quitadas y puestas, mismo cuerpo, readingMinutes recalculado salvo que el caso lo fije) y
 * normaliza las rutas igual («articulo» y «cuerpo»).
 */
final class ParityCorpus {

  /** Números exactos (1e400, 1.0, enteros enormes), claves duplicadas rechazadas: como ContentSource. */
  static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  static final Path CONTENT = Path.of("../src/content");
  static final Path RETIRED_MEDIA_TS = Path.of("../src/app/editorial/retired-media.ts");
  static final Path NAVIGATION_TS = Path.of("../src/app/layout/navigation.ts");

  final ContentSource.Content content = ContentSource.read(CONTENT);
  final List<String> retired = CodeUsages.read(RETIRED_MEDIA_TS, NAVIGATION_TS).retired();

  static JsonNode read(String name) {
    try (InputStream in = ParityCorpus.class.getResourceAsStream("/contract-cases/" + name)) {
      if (in == null) {
        throw new IllegalStateException("no existe contract-cases/" + name);
      }
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  JsonNode articles() {
    return content.collections().get("articles");
  }

  /** Construye el caso y devuelve las incidencias de su artículo, normalizadas. */
  List<String> run(JsonNode c) {
    ArrayNode articles = (ArrayNode) articles().deepCopy();
    String base = c.get("base").stringValue();
    int i = -1;
    for (int k = 0; k < articles.size(); k++) {
      if (articles.get(k).get("id").stringValue().equals(base)) {
        i = k;
      }
    }
    if (i < 0) {
      throw new IllegalArgumentException(c.get("nombre").stringValue() + ": no existe el artículo base " + base);
    }
    ObjectNode original = (ObjectNode) articles.get(i);
    ObjectNode article = original.deepCopy();
    List<String> removed = new ArrayList<>();
    for (JsonNode key : c.path("quitar").values()) {
      removed.add(key.stringValue());
      article.remove(key.stringValue());
    }
    JsonNode put = c.path("poner");
    for (String key : put.propertyNames()) {
      article.set(key, put.get(key).deepCopy()); // una clave existente conserva su posición, como en JS
    }
    JsonNode baseBody = content.bodies().get(original.get("slug").stringValue());
    JsonNode body;
    if (c.has("cuerpo")) {
      body = c.get("cuerpo").deepCopy();
    } else {
      ObjectNode built = JsonNodeFactory.instance.objectNode();
      if (article.has("slug")) {
        built.set("slug", article.get("slug"));
      }
      built.set("blocks", c.has("bloques") ? c.get("bloques").deepCopy() : baseBody.get("blocks").deepCopy());
      body = built;
    }
    boolean fixedMinutes = put.has("readingMinutes") || removed.contains("readingMinutes");
    JsonNode blocks = body.isObject() ? body.get("blocks") : null;
    if (!fixedMinutes && blocks != null && blocks.isArray()) {
      try {
        article.put("readingMinutes", ReadingTime.minutes(blocks));
      } catch (RuntimeException e) {
        // bloques sin la forma del contrato: se deja el valor del artículo base
      }
    }
    articles.set(i, article);

    String articlePrefix = "articles[" + i + "]";
    String bodyPrefix = "articles/" + Js.string(article.get("slug")) + ".json";
    List<String> out = new ArrayList<>();
    for (Issue issue : ArticleValidator.validate(article, body, context(articles), articlePrefix)) {
      String line = issue.toString();
      if (withPrefix(line, articlePrefix)) {
        out.add("articulo" + line.substring(articlePrefix.length()));
      } else if (withPrefix(line, bodyPrefix)) {
        out.add("cuerpo" + line.substring(bodyPrefix.length()));
      } else {
        throw new IllegalStateException("incidencia con una ruta inesperada: " + line);
      }
    }
    return out;
  }

  /** Un registro del catálogo cambiado (6.4.7), como runCatalog de la spec: sus incidencias, normalizadas. */
  List<String> runCatalog(JsonNode c) {
    String collection = c.get("coleccion").stringValue();
    JsonNode items = content.collections().get(collection);
    String base = c.get("base").stringValue();
    int i = -1;
    for (int k = 0; k < items.size(); k++) {
      if (items.get(k).get("id").stringValue().equals(base)) {
        i = k;
      }
    }
    if (i < 0) {
      throw new IllegalArgumentException(c.get("nombre").stringValue() + ": no existe " + base);
    }
    ObjectNode record = ((ObjectNode) items.get(i)).deepCopy();
    for (JsonNode key : c.path("quitar").values()) {
      record.remove(key.stringValue());
    }
    JsonNode put = c.path("poner");
    for (String key : put.propertyNames()) {
      record.set(key, put.get(key).deepCopy());
    }
    String prefix = collection + "[" + i + "]";
    List<Issue> issues = CatalogRules.record(collection, record, prefix);
    if (issues.isEmpty()) {
      issues = CatalogIntegrity.record(collection, record, refs(), prefix);
    }
    List<String> out = new ArrayList<>();
    for (Issue issue : issues) {
      out.add("registro" + issue.toString().substring(prefix.length()));
    }
    return out;
  }

  CatalogIntegrity.Refs refs() {
    Map<String, JsonNode> collections = content.collections();
    return new CatalogIntegrity.Refs(ids(collections.get("media")), Set.copyOf(retired), ids(collections.get("tags")),
        ids(collections.get("destinations")));
  }

  private static boolean withPrefix(String issue, String prefix) {
    return issue.startsWith(prefix) && issue.length() > prefix.length() && ".[:".indexOf(issue.charAt(prefix.length())) >= 0;
  }

  /** El contexto del contenido real, con la lista de artículos del caso (el propio incluido). */
  Context context(JsonNode articles) {
    Map<String, JsonNode> collections = content.collections();
    Map<String, List<String>> stops = new LinkedHashMap<>();
    for (JsonNode trip : collections.get("trips").values()) {
      List<String> destinations = new ArrayList<>();
      for (JsonNode stop : trip.get("stops").values()) {
        destinations.add(stop.get("destination").stringValue());
      }
      stops.putIfAbsent(trip.get("id").stringValue(), destinations);
    }
    List<Context.ArticleRef> refs = new ArrayList<>();
    for (JsonNode a : articles.values()) {
      refs.add(new Context.ArticleRef(Js.string(a.get("slug")), Js.string(a.get("category"))));
    }
    return new Context(ids(collections.get("tags")), ids(collections.get("authors")), ids(collections.get("media")),
        Set.copyOf(retired), ids(collections.get("galleries")), ids(collections.get("destinations")), stops,
        ids(collections.get("projects")), refs);
  }

  private static Set<String> ids(JsonNode collection) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode item : collection.values()) {
      ids.add(item.get("id").stringValue());
    }
    return ids;
  }

  static List<String> strings(JsonNode list) {
    List<String> out = new ArrayList<>();
    for (JsonNode item : list.values()) {
      out.add(item.stringValue());
    }
    return out;
  }

  /** Bloques del caso de tiempo de lectura: los suyos o N palabras en un párrafo. */
  static JsonNode readingBlocks(JsonNode c) {
    if (c.has("bloques")) {
      return c.get("bloques");
    }
    String words = String.join(" ", java.util.Collections.nCopies(c.get("palabras").intValue(), "palabra"));
    ArrayNode blocks = JsonNodeFactory.instance.arrayNode();
    blocks.addObject().put("type", "text").put("html", "<p>" + words + "</p>");
    return blocks;
  }
}
