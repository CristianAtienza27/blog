package com.cristianatienza.cms.content.io;

import com.cristianatienza.cms.content.CoordinatePolicy;
import com.cristianatienza.cms.content.CoordinatePolicy.InvalidCoordinateException;
import com.cristianatienza.cms.content.CoordinatePolicy.Use;
import com.cristianatienza.cms.content.io.CodeUsages.Usages;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;

/**
 * Importa src/content en PostgreSQL:
 *
 * <ul>
 *   <li>Antes de escribir nada: la forma de cada registro (ContentContract, incluida la política
 *       de null del contrato), las coordenadas (CoordinatePolicy), la correspondencia de cuerpos y
 *       artículos y los usos del código.
 *   <li>Todo en una transacción y con el rol cms_importer: inserta el catálogo y crea cada
 *       artículo con import_published_article (revisión 1, de trabajo y publicada). Si algo
 *       falla, no queda nada.
 *   <li>Las imágenes de RETIRED_MEDIA se insertan ya retiradas: la base de datos rechaza
 *       cualquier referencia a ellas durante la importación.
 *   <li>Idempotente: con la base de datos ya cargada, compara su contenido con el origen; si es
 *       igual, no escribe nada; si es distinto, se detiene con las diferencias. No sincroniza.
 * </ul>
 */
public final class ContentImporter {

  /** Resultado de una importación. */
  public enum Outcome {
    IMPORTED,
    UNCHANGED
  }

  /**
   * @param tableCounts filas por tabla al terminar
   * @param wrote si la transacción llegó a escribir (pg_current_xact_id_if_assigned)
   */
  public record Result(Outcome outcome, Map<String, Long> tableCounts, boolean wrote) {}

  /** El contenido de la base de datos no coincide con el origen: no se sincroniza. */
  public static final class ContentDifferenceException extends ContentException {
    private static final long serialVersionUID = 1L;

    public ContentDifferenceException(List<String> differences) {
      super(differences);
    }
  }

  static final List<String> TABLES = List.of("category", "tag", "author", "media", "media_original",
      "media_reserved_use", "gallery", "gallery_item", "destination", "trip", "trip_stop", "project",
      "project_stack_item", "article", "article_revision", "article_revision_tag", "article_revision_destination",
      "article_revision_block_ref");

  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private final DataSource dataSource;

  public ContentImporter(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Result importContent(Content content, Usages usages) {
    List<String> issues = preflight(content, usages);
    if (!issues.isEmpty()) {
      throw new ContentException(issues);
    }
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        execute(c, "SET LOCAL ROLE cms_importer");
        if (!isEmpty(c)) {
          List<String> differences = new ArrayList<>(ContentComparator.compare(content, ContentExporter.read(c)));
          differences.addAll(compareUsages(c, usages));
          boolean wrote = wrote(c);
          Map<String, Long> counts = counts(c);
          c.rollback();
          if (!differences.isEmpty()) {
            throw new ContentDifferenceException(differences);
          }
          return new Result(Outcome.UNCHANGED, counts, wrote);
        }
        insertAll(c, content, usages);
        Map<String, Long> counts = counts(c);
        c.commit();
        return new Result(Outcome.IMPORTED, counts, true);
      } catch (SQLException e) {
        c.rollback();
        throw new ContentException("la importación se ha revertido por completo: " + e.getMessage());
      } catch (RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new ContentException("no se puede conectar con la base de datos: " + e.getMessage());
    }
  }

  // --- Comprobaciones previas (sin escribir) -------------------------------------------------------

  static List<String> preflight(Content content, Usages usages) {
    List<String> issues = new ArrayList<>(ContentSource.check(content));
    if (!issues.isEmpty()) {
      return issues;
    }
    JsonNode media = content.collections().get("media");
    for (int i = 0; i < media.size(); i++) {
      JsonNode point = media.get(i).path("location").path("point");
      if (point.isObject()) {
        coordinates(issues, "media[" + i + "].location.point", point, Use.PHOTO_LOCATION);
      }
      date(issues, "media[" + i + "].takenAt", media.get(i).path("takenAt"));
    }
    JsonNode destinations = content.collections().get("destinations");
    for (int i = 0; i < destinations.size(); i++) {
      coordinates(issues, "destinations[" + i + "].location", destinations.get(i).get("location"), Use.DESTINATION);
    }
    JsonNode articles = content.collections().get("articles");
    for (int i = 0; i < articles.size(); i++) {
      date(issues, "articles[" + i + "].publishedAt", articles.get(i).path("publishedAt"));
      date(issues, "articles[" + i + "].updatedAt", articles.get(i).path("updatedAt"));
    }
    List<String> ids = new ArrayList<>();
    media.values().forEach(m -> ids.add(m.get("id").stringValue()));
    for (String retired : usages.retired()) {
      if (!ids.contains(retired)) {
        issues.add("RETIRED_MEDIA: «" + retired + "» no está en media.json");
      }
    }
    usages.previews().forEach((section, preview) -> {
      if (!ids.contains(preview)) {
        issues.add("SECTIONS." + section + ".preview: «" + preview + "» no está en media.json");
      } else if (usages.retired().contains(preview)) {
        issues.add("SECTIONS." + section + ".preview: «" + preview + "» está retirada");
      }
    });
    return issues;
  }

  private static void coordinates(List<String> issues, String path, JsonNode point, Use use) {
    try {
      CoordinatePolicy.requireValid(point.get("lat").decimalValue(), point.get("lng").decimalValue(), use);
    } catch (InvalidCoordinateException e) {
      issues.add(path + "." + e.getMessage());
    }
  }

  private static void date(List<String> issues, String path, JsonNode value) {
    if (!value.isMissingNode() && !ISO_DATE.matcher(value.stringValue()).matches()) {
      issues.add(path + ": se esperaba una fecha AAAA-MM-DD: «" + value.stringValue() + "»");
    }
  }

  // --- Escritura -------------------------------------------------------------------------------------

  private static void insertAll(Connection c, Content content, Usages usages) throws SQLException {
    Map<String, JsonNode> col = content.collections();
    each(col.get("categories"), (item, pos) -> insert(c,
        "INSERT INTO category (id, slug, name, description, catalog_position) VALUES (?, ?, ?, ?, ?)",
        text(item, "id"), text(item, "slug"), text(item, "name"), text(item, "description"), pos));
    each(col.get("tags"), (item, pos) -> insert(c,
        "INSERT INTO tag (id, slug, label, catalog_position) VALUES (?, ?, ?, ?)",
        text(item, "id"), text(item, "slug"), text(item, "label"), pos));
    each(col.get("media"), (item, pos) -> insertMedia(c, item, pos, usages.retired().contains(text(item, "id"))));
    each(col.get("authors"), (item, pos) -> insert(c,
        "INSERT INTO author (id, name, role, bio, avatar_media_id, catalog_position) VALUES (?, ?, ?, ?, ?, ?)",
        text(item, "id"), text(item, "name"), text(item, "role"), text(item, "bio"), text(item, "avatar"), pos));
    each(col.get("galleries"), (item, pos) -> {
      insert(c, "INSERT INTO gallery (id, slug, title, description, layout, demo, catalog_position)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?)",
          text(item, "id"), text(item, "slug"), text(item, "title"), text(item, "description"), text(item, "layout"),
          bool(item, "demo"), pos);
      each(item.get("items"), (galleryItem, position) -> insert(c,
          "INSERT INTO gallery_item (gallery_id, position, media_id, caption) VALUES (?, ?, ?, ?)",
          text(item, "id"), position, text(galleryItem, "media"), text(galleryItem, "caption")));
    });
    each(col.get("destinations"), (item, pos) -> insert(c,
        "INSERT INTO destination (id, slug, name, country, region, lat, lng, elevation, summary, cover_media_id, demo,"
            + " catalog_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        text(item, "id"), text(item, "slug"), text(item, "name"), text(item, "country"), text(item, "region"),
        decimal(item.get("location"), "lat"), decimal(item.get("location"), "lng"), integer(item, "elevation"),
        text(item, "summary"), text(item, "cover"), bool(item, "demo"), pos));
    each(col.get("trips"), (item, pos) -> {
      insert(c, "INSERT INTO trip (id, slug, title, summary, cover_media_id, demo, catalog_position)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?)",
          text(item, "id"), text(item, "slug"), text(item, "title"), text(item, "summary"), text(item, "cover"),
          bool(item, "demo"), pos);
      each(item.get("stops"), (stop, position) -> insert(c,
          "INSERT INTO trip_stop (trip_id, stop_order, destination_id, note, distance_km, mode)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          text(item, "id"), integer(stop, "order"), text(stop, "destination"), text(stop, "note"),
          decimal(stop, "distanceKm"), text(stop, "mode")));
    });
    each(col.get("projects"), (item, pos) -> {
      insert(c, "INSERT INTO project (id, slug, name, summary, status, year, repository_url, cover_media_id, demo,"
              + " catalog_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          text(item, "id"), text(item, "slug"), text(item, "name"), text(item, "summary"), text(item, "status"),
          integer(item, "year"), text(item, "repositoryUrl"), text(item, "cover"), bool(item, "demo"), pos);
      each(item.get("stack"), (tag, position) -> insert(c,
          "INSERT INTO project_stack_item (project_id, position, tag_id) VALUES (?, ?, ?)",
          text(item, "id"), position, tag.stringValue()));
    });
    // Cada artículo con su operación controlada: artículo + revisión 1 + punteros, en esta transacción.
    each(col.get("articles"), (item, pos) -> insert(c,
        "SELECT * FROM import_published_article(?::jsonb, ?::jsonb, ?)",
        ContentSource.JSON.writeValueAsString(item),
        ContentSource.JSON.writeValueAsString(content.bodies().get(text(item, "slug")).get("blocks")), pos));
    for (Map.Entry<String, String> preview : usages.previews().entrySet()) {
      insert(c, "INSERT INTO media_reserved_use (use_key, media_id, description) VALUES (?, ?, ?)",
          "navigation.preview." + preview.getKey(), preview.getValue(),
          "Vista previa de la sección «" + preview.getKey() + "» en el menú (src/app/layout/navigation.ts)");
    }
  }

  private static void insertMedia(Connection c, JsonNode item, int position, boolean retired) throws SQLException {
    JsonNode location = item.path("location");
    JsonNode point = location.path("point");
    JsonNode credit = item.get("credit");
    Integer[] widths = item.get("widths").values().stream().map(w -> w.decimalValue().intValueExact()).toArray(Integer[]::new);
    insert(c, "INSERT INTO media (id, path, width, height, widths, alt, title, caption, description, taken_at,"
            + " location_name, location_region, location_country, location_lat, location_lng, camera, focal_x, focal_y,"
            + " placeholder, credit_author, credit_source, credit_source_url, credit_license, credit_license_url, demo,"
            + " retired, catalog_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?, ?, ?)",
        text(item, "id"), text(item, "path"), integer(item, "width"), integer(item, "height"),
        c.createArrayOf("integer", widths), text(item, "alt"), text(item, "title"), text(item, "caption"),
        text(item, "description"), text(item, "takenAt"), text(location, "name"), text(location, "region"),
        text(location, "country"), decimal(point, "lat"), decimal(point, "lng"),
        item.has("camera") ? ContentSource.JSON.writeValueAsString(item.get("camera")) : null,
        decimal(item.get("focal"), "x"), decimal(item.get("focal"), "y"), text(item, "placeholder"),
        text(credit, "author"), text(credit, "source"), text(credit, "sourceUrl"), text(credit, "license"),
        text(credit, "licenseUrl"), bool(item, "demo"), retired, position);
    JsonNode original = item.get("original");
    JsonNode raw = original.path("raw");
    insert(c, "INSERT INTO media_original (media_id, file, format, width, height, bytes, sha256, url, raw_file,"
            + " raw_bytes, raw_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        text(item, "id"), text(original, "file"), text(original, "format"), integer(original, "width"),
        integer(original, "height"), longValue(original, "bytes"), text(original, "sha256"), text(original, "url"),
        text(raw, "file"), longValue(raw, "bytes"), text(raw, "sha256"));
  }

  // --- Idempotencia --------------------------------------------------------------------------------

  private static boolean isEmpty(Connection c) throws SQLException {
    for (long count : counts(c).values()) {
      if (count > 0) return false;
    }
    return true;
  }

  private static List<String> compareUsages(Connection c, Usages usages) throws SQLException {
    List<String> differences = new ArrayList<>();
    List<String> retired = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM media WHERE retired ORDER BY id")) {
      while (rs.next()) retired.add(rs.getString(1));
    }
    if (!retired.equals(usages.retired().stream().sorted().toList())) {
      differences.add("media.retired: " + retired + " ≠ RETIRED_MEDIA " + usages.retired());
    }
    Map<String, String> reserved = new LinkedHashMap<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT use_key, media_id FROM media_reserved_use ORDER BY use_key")) {
      while (rs.next()) reserved.put(rs.getString(1), rs.getString(2));
    }
    Map<String, String> expected = new LinkedHashMap<>();
    usages.previews().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> expected.put("navigation.preview." + e.getKey(), e.getValue()));
    if (!reserved.equals(expected)) {
      differences.add("media_reserved_use: " + reserved + " ≠ SECTIONS " + expected);
    }
    return differences;
  }

  private static boolean wrote(Connection c) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT pg_current_xact_id_if_assigned()::text")) {
      rs.next();
      return rs.getString(1) != null;
    }
  }

  static Map<String, Long> counts(Connection c) throws SQLException {
    Map<String, Long> counts = new LinkedHashMap<>();
    try (Statement s = c.createStatement()) {
      for (String table : TABLES) {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
          rs.next();
          counts.put(table, rs.getLong(1));
        }
      }
    }
    return counts;
  }

  // --- Utilidades ----------------------------------------------------------------------------------

  @FunctionalInterface
  private interface Item {
    void accept(JsonNode item, int position) throws SQLException;
  }

  /** Recorre una lista con la posición de cada elemento (desde 1). */
  private static void each(JsonNode list, Item action) throws SQLException {
    for (int i = 0; i < list.size(); i++) {
      action.accept(list.get(i), i + 1);
    }
  }

  private static void insert(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        if (params[i] == null) {
          ps.setNull(i + 1, Types.NULL);
        } else {
          ps.setObject(i + 1, params[i]);
        }
      }
      ps.execute();
    }
  }

  private static void execute(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isString() ? value.stringValue() : null;
  }

  private static BigDecimal decimal(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isNumber() ? value.decimalValue() : null;
  }

  private static Integer integer(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isNumber() ? value.decimalValue().intValueExact() : null;
  }

  private static Long longValue(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isNumber() ? value.decimalValue().longValueExact() : null;
  }

  private static Boolean bool(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isBoolean() ? value.booleanValue() : null;
  }
}
