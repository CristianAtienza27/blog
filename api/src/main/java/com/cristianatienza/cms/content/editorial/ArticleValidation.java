package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.validation.ArticleValidator;
import com.cristianatienza.cms.content.validation.ArticleValidator.Context;
import com.cristianatienza.cms.content.validation.Issue;
import com.cristianatienza.cms.content.validation.ReadingTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validación del contenido de un artículo contra el catálogo real de la base de datos (fase 6.4.3),
 * con el validador de la 6.4.1. Distingue:
 *
 * <ul>
 *   <li><strong>Errores</strong>: todo lo que el validador rechaza con el catálogo completo (forma,
 *       HTML, referencias, capítulos, anclas, enlaces a artículos que no existen…). Bloquean el
 *       guardado.
 *   <li><strong>Avisos</strong>: lo que solo falla si se mira lo PUBLICADO, hoy los enlaces a
 *       artículos que existen pero aún no están publicados. No bloquean el guardado; la publicación
 *       de un artículo (6.4.5) los tratará como errores.
 * </ul>
 *
 * Las reglas de conjunto (imágenes y galerías sin usar, orden repetido en una sección…) siguen en la
 * puerta de publicación (6.3). Las rutas de las incidencias son las del cuerpo de la petición:
 * {@code content.title}, {@code content.blocks[2].html}…
 */
@Component
public class ArticleValidation {

  /** Campos del contenido de una revisión que envía el cliente (readingMinutes lo calcula el servidor). */
  static final List<String> CONTENT_FIELDS = List.of("title", "dek", "excerpt", "tags", "format", "cover", "author",
      "trip", "destinations", "project", "seo", "blocks");
  private static final Set<String> METADATA_FIELDS =
      Set.of("id", "slug", "category", "order", "publishedAt", "updatedAt", "featured", "demo");
  /** Fecha de relleno para un borrador sin fecha (ArticleRecord la exige; no se guarda). */
  private static final String DRAFT_DATE = "2000-01-01";
  private static final String UNKNOWN_LINK = "enlace a un artículo inexistente";

  /** Los metadatos del artículo que el validador necesita junto al contenido. */
  public record Meta(String id, String slug, String category, Integer order, String publishedAt, String updatedAt,
      boolean featured, boolean demo) {}

  /**
   * Resultado: errores, avisos, los minutos de lectura calculados y el contenido listo para guardar
   * (los campos enviados más {@code readingMinutes}), o null si el contenido no es un objeto.
   */
  public record Result(List<FieldError> errors, List<FieldError> warnings, Integer readingMinutes,
      ObjectNode content) {}

  private final JdbcClient jdbc;

  public ArticleValidation(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Contenido enviado por el cliente: solo los campos de la revisión; {@code readingMinutes} lo
   * calcula el servidor (enviarlo es un error).
   */
  public Result checkSubmitted(Meta meta, JsonNode content) {
    List<FieldError> errors = new ArrayList<>();
    if (content == null || !content.isObject()) {
      errors.add(new FieldError("content", "se esperaba un objeto con el contenido de la revisión"));
      return new Result(errors, List.of(), null, null);
    }
    ObjectNode accepted = JsonNodeFactory.instance.objectNode();
    for (String key : content.propertyNames()) {
      if (CONTENT_FIELDS.contains(key)) {
        accepted.set(key, content.get(key));
      } else if (key.equals("readingMinutes")) {
        errors.add(new FieldError("content.readingMinutes", "lo calcula el servidor: no se envía"));
      } else if (METADATA_FIELDS.contains(key)) {
        errors.add(new FieldError("content." + key, "es un metadato del artículo, no del contenido"));
      } else {
        errors.add(new FieldError("content." + key, "campo desconocido"));
      }
    }
    Integer minutes = null;
    JsonNode blocks = accepted.get("blocks");
    if (blocks != null && blocks.isArray()) {
      minutes = ReadingTime.minutes(blocks);
    }
    ObjectNode toSave = accepted.deepCopy();
    if (minutes != null) {
      toSave.put("readingMinutes", minutes);
    }
    Result checked = check(meta, toSave, Map.of());
    // Sin bloques válidos no hay minutos que calcular: ese «campo obligatorio» no es del cliente.
    checked.errors().stream()
        .filter(e -> !(e.path().equals("content.readingMinutes") && e.message().equals("campo obligatorio")))
        .forEach(errors::add);
    return new Result(errors, checked.warnings(), minutes, toSave);
  }

  /** Contenido ya guardado (con su readingMinutes): las mismas reglas, para los avisos del detalle. */
  public Result checkStored(Meta meta, JsonNode content) {
    return check(meta, content == null ? JsonNodeFactory.instance.objectNode() : content, Map.of());
  }

  /**
   * Contenido guardado comprobado con OTRAS paradas para algunos itinerarios (6.4.7): lo que pasaría
   * con el artículo si se guardara ese cambio del itinerario.
   */
  public Result checkStored(Meta meta, JsonNode content, Map<String, List<String>> tripStops) {
    return check(meta, content == null ? JsonNodeFactory.instance.objectNode() : content, tripStops);
  }

  private Result check(Meta meta, JsonNode content, Map<String, List<String>> tripStops) {
    ObjectNode article = JsonNodeFactory.instance.objectNode();
    article.put("id", meta.id());
    article.put("slug", meta.slug());
    for (String key : List.of("title", "dek", "excerpt")) {
      copy(content, article, key);
    }
    article.put("category", meta.category());
    copy(content, article, "tags");
    copy(content, article, "format");
    copy(content, article, "cover");
    copy(content, article, "author");
    article.put("order", meta.order() == null ? 1 : meta.order());
    article.put("publishedAt", meta.publishedAt() == null ? DRAFT_DATE : meta.publishedAt());
    if (meta.updatedAt() != null) {
      article.put("updatedAt", meta.updatedAt());
    }
    copy(content, article, "readingMinutes");
    article.put("featured", meta.featured());
    article.put("demo", meta.demo());
    for (String key : List.of("trip", "destinations", "project", "seo")) {
      copy(content, article, key);
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.put("slug", meta.slug());
    copy(content, body, "blocks");

    Catalog catalog = catalog();
    if (!tripStops.isEmpty()) {
      Map<String, List<String>> stops = new LinkedHashMap<>(catalog.stops());
      stops.putAll(tripStops);
      catalog = new Catalog(catalog.tags(), catalog.authors(), catalog.media(), catalog.retired(), catalog.galleries(),
          catalog.destinations(), stops, catalog.projects(), catalog.articles());
    }
    List<Issue> all = ArticleValidator.validate(article, body, catalog.context(meta, false), "content", "content");
    List<Issue> published = ArticleValidator.validate(article, body, catalog.context(meta, true), "content", "content");
    Set<String> errorLines = new LinkedHashSet<>();
    all.forEach(issue -> errorLines.add(issue.toString()));
    List<FieldError> errors = all.stream().map(i -> new FieldError(i.path(), i.message())).toList();
    List<FieldError> warnings = published.stream()
        .filter(i -> !errorLines.contains(i.toString()))
        .map(i -> new FieldError(i.path(), i.message().startsWith(UNKNOWN_LINK)
            ? "enlace a un artículo que aún no está publicado" + i.message().substring(UNKNOWN_LINK.length())
            : i.message()))
        .toList();
    return new Result(errors, warnings, null, null);
  }

  private static void copy(JsonNode from, ObjectNode to, String key) {
    if (from.has(key)) {
      to.set(key, from.get(key));
    }
  }

  // --- El catálogo real (login de la API, solo lectura) -----------------------------------------

  private record ArticleRow(String id, String slug, String category, boolean published) {}

  private record Catalog(Set<String> tags, Set<String> authors, Set<String> media, Set<String> retired,
      Set<String> galleries, Set<String> destinations, Map<String, List<String>> stops, Set<String> projects,
      List<ArticleRow> articles) {

    /** Con todos los artículos, o solo con los publicados (más el propio, con su slug y sección). */
    Context context(Meta meta, boolean onlyPublished) {
      List<Context.ArticleRef> refs = new ArrayList<>();
      boolean self = false;
      for (ArticleRow row : articles) {
        if (row.id().equals(meta.id())) {
          refs.add(new Context.ArticleRef(meta.slug(), meta.category()));
          self = true;
        } else if (!onlyPublished || row.published()) {
          refs.add(new Context.ArticleRef(row.slug(), row.category()));
        }
      }
      if (!self) {
        refs.add(new Context.ArticleRef(meta.slug(), meta.category()));
      }
      return new Context(tags, authors, media, retired, galleries, destinations, stops, projects, refs);
    }
  }

  private Catalog catalog() {
    Map<String, List<String>> stops = new LinkedHashMap<>();
    jdbc.sql("SELECT t.id, s.destination_id FROM trip t LEFT JOIN trip_stop s ON s.trip_id = t.id"
            + " ORDER BY t.catalog_position, s.stop_order")
        .query(rs -> {
          List<String> list = stops.computeIfAbsent(rs.getString(1), k -> new ArrayList<>());
          if (rs.getString(2) != null) {
            list.add(rs.getString(2));
          }
        });
    List<ArticleRow> articles = jdbc.sql("SELECT id, slug, category_id, published_revision_id IS NOT NULL"
            + " FROM article ORDER BY catalog_position")
        .query((rs, n) -> new ArticleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
        .list();
    return new Catalog(ids("tag"), ids("author"), ids("media"), ids("media WHERE retired"), ids("gallery"),
        ids("destination"), stops, ids("project"), articles);
  }

  private Set<String> ids(String from) {
    return new LinkedHashSet<>(jdbc.sql("SELECT id FROM " + from).query(String.class).list());
  }
}
