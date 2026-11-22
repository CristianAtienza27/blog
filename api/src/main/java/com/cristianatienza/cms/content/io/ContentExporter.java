package com.cristianatienza.cms.content.io;

import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reconstruye el contrato de src/content desde PostgreSQL (los artículos, desde su revisión
 * publicada) y lo escribe fuera del repositorio, en orden canónico y con un formato estable:
 * dos exportaciones seguidas del mismo estado son idénticas byte a byte.
 *
 * <p>Las instantáneas de la fase 6.3 ({@code content.snapshot}) reutilizan {@link #readPublished} y
 * {@link JsonText}: mismo contrato y mismos bytes, con {@code original} incluido (la proyección
 * pública que lo quita es de la web, fase 6.3.2).
 */
public final class ContentExporter {

  /** Marca de una carpeta de exportación: solo esas carpetas se pueden sobrescribir. */
  public static final String MARKER = ".cms-export";

  private static final String MARKER_TEXT =
      "Exportación de cms-api: el contrato de src/content en orden canónico. No editar.\n";
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ContentExporter() {}

  // --- Exportar ---------------------------------------------------------------------------------

  /** Lee la base de datos (transacción de solo lectura, como cms_importer) y escribe la exportación. */
  public static Content export(DataSource dataSource, Path target) {
    Content content = readPublished(dataSource);
    write(content, target);
    return content;
  }

  /** Lee el estado publicado en una transacción de solo lectura (como cms_importer), sin escribir nada. */
  public static Content readPublished(DataSource dataSource) {
    return readPublished(dataSource, "cms_importer");
  }

  /**
   * Igual, con el rol indicado; {@code null}, sin SET ROLE (la API, como cms_app, que desde V10
   * puede ejecutar article_revision_content: fase 6.3.4).
   */
  public static Content readPublished(DataSource dataSource, String role) {
    if (role != null && !role.matches("[a-z_]+")) {
      throw new IllegalArgumentException("rol no válido");
    }
    Content content;
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SET TRANSACTION READ ONLY");
        if (role != null) {
          s.execute("SET LOCAL ROLE " + role);
        }
      }
      content = read(c);
      c.rollback();
    } catch (SQLException e) {
      throw new ContentException("no se puede leer la base de datos: " + e.getMessage());
    }
    return content;
  }

  /** Escribe el contenido en una carpeta validada (fuera de cualquier repositorio Git). */
  public static void write(Content content, Path target) {
    Path dir = prepareTarget(target);
    try {
      Files.createDirectories(dir.resolve("articles"));
      for (Map.Entry<String, JsonNode> collection : content.collections().entrySet()) {
        writeFile(dir.resolve(collection.getKey() + ".json"), collection.getValue());
      }
      for (Map.Entry<String, JsonNode> body : content.bodies().entrySet()) {
        writeFile(dir.resolve("articles").resolve(body.getKey() + ".json"), body.getValue());
      }
      Files.writeString(dir.resolve(MARKER), MARKER_TEXT, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede escribir en " + dir, e);
    }
  }

  private static void writeFile(Path file, JsonNode value) throws IOException {
    Files.writeString(file, JsonText.write(value), StandardCharsets.UTF_8);
  }

  /**
   * Destino seguro: fuera de cualquier repositorio Git (nunca dentro del proyecto), y vacío o con
   * una exportación anterior marcada, que se sustituye. Cualquier otro contenido se rechaza.
   */
  static Path prepareTarget(Path target) {
    Path dir = target.toAbsolutePath().normalize();
    Path existing = dir;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    try {
      for (Path p = existing == null ? null : existing.toRealPath(); p != null; p = p.getParent()) {
        if (Files.exists(p.resolve(".git"))) {
          throw new ContentException("la exportación no puede estar dentro de un repositorio Git (" + p + "): " + dir);
        }
      }
      if (!Files.exists(dir)) {
        Files.createDirectories(dir);
        return dir;
      }
      if (!Files.isDirectory(dir)) {
        throw new ContentException("el destino de la exportación no es una carpeta: " + dir);
      }
      List<Path> entries;
      try (Stream<Path> list = Files.list(dir)) {
        entries = list.toList();
      }
      if (entries.isEmpty()) {
        return dir;
      }
      if (!Files.isRegularFile(dir.resolve(MARKER))) {
        throw new ContentException("la carpeta de exportación no está vacía y no es una exportación anterior ("
            + MARKER + "): " + dir);
      }
      removePreviousExport(dir, entries);
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede preparar " + dir, e);
    }
  }

  /** Borra una exportación anterior: solo los archivos que escribe el exportador. */
  private static void removePreviousExport(Path dir, List<Path> entries) throws IOException {
    Set<String> collections = ContentContract.COLLECTIONS.keySet();
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      boolean ours = name.equals(MARKER)
          || (Files.isRegularFile(entry) && name.endsWith(".json") && collections.contains(name.replace(".json", "")))
          || (Files.isDirectory(entry) && name.equals("articles"));
      if (!ours) {
        throw new ContentException("la exportación anterior contiene archivos ajenos («" + name + "»): " + dir);
      }
    }
    Path articles = dir.resolve("articles");
    if (Files.isDirectory(articles)) {
      try (Stream<Path> list = Files.list(articles)) {
        for (Path file : list.toList()) {
          if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".json")) {
            throw new ContentException("la exportación anterior contiene archivos ajenos en articles/: " + file);
          }
          Files.delete(file);
        }
      }
      Files.delete(articles);
    }
    for (Path entry : entries) {
      if (Files.isRegularFile(entry)) {
        Files.delete(entry);
      }
    }
  }

  // --- Leer la base de datos -------------------------------------------------------------------------

  /** Reconstruye el contenido con la conexión dada (dentro de su transacción). */
  public static Content read(Connection c) throws SQLException {
    Map<String, JsonNode> collections = new LinkedHashMap<>();
    collections.put("categories", rows(c, "SELECT id, slug, name, description FROM category ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("name", rs).text("description", rs).node()));
    collections.put("tags", rows(c, "SELECT id, slug, label FROM tag ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("label", rs).node()));
    collections.put("authors", rows(c, "SELECT id, name, role, bio, avatar_media_id FROM author ORDER BY catalog_position",
        rs -> object().text("id", rs).text("name", rs).text("role", rs).text("bio", rs)
            .text("avatar", rs.getString("avatar_media_id")).node()));
    collections.put("media", media(c));
    collections.put("galleries", galleries(c));
    collections.put("destinations", rows(c,
        "SELECT * FROM destination ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("name", rs).text("country", rs).text("region", rs)
            .put("location", object().decimal("lat", rs.getBigDecimal("lat")).decimal("lng", rs.getBigDecimal("lng")).node())
            .integer("elevation", rs).text("summary", rs).text("cover", rs.getString("cover_media_id"))
            .bool("demo", rs).node()));
    collections.put("trips", trips(c));
    collections.put("projects", projects(c));
    Map<String, JsonNode> bodies = new TreeMap<>();
    collections.put("articles", articles(c, bodies));

    // Orden canónico del contrato en todo el árbol.
    Map<String, JsonNode> canonical = new LinkedHashMap<>();
    collections.forEach((name, list) -> {
      ArrayNode out = NODES.arrayNode();
      for (JsonNode item : list.values()) {
        out.add(ContentContract.canonical(item, ContentContract.COLLECTIONS.get(name)));
      }
      canonical.put(name, out);
    });
    Map<String, JsonNode> canonicalBodies = new TreeMap<>();
    bodies.forEach((slug, body) -> canonicalBodies.put(slug, ContentContract.canonical(body, ContentContract.BODY)));
    return new Content(canonical, canonicalBodies);
  }

  private static JsonNode media(Connection c) throws SQLException {
    return rows(c, "SELECT m.*, o.file AS o_file, o.format AS o_format, o.width AS o_width, o.height AS o_height,"
            + " o.bytes AS o_bytes, o.sha256 AS o_sha256, o.url AS o_url, o.raw_file, o.raw_bytes, o.raw_sha256"
            + " FROM media m JOIN media_original o ON o.media_id = m.id ORDER BY m.catalog_position",
        rs -> {
          Obj media = object().text("id", rs).text("path", rs).integer("width", rs).integer("height", rs)
              .put("widths", intArray(rs.getArray("widths"))).text("alt", rs).text("title", rs).text("caption", rs)
              .text("description", rs).text("takenAt", rs.getString("taken_at"));
          if (rs.getString("location_name") != null) {
            Obj location = object().text("name", rs.getString("location_name"))
                .text("region", rs.getString("location_region")).text("country", rs.getString("location_country"));
            if (rs.getBigDecimal("location_lat") != null) {
              location.put("point", object().decimal("lat", rs.getBigDecimal("location_lat"))
                  .decimal("lng", rs.getBigDecimal("location_lng")).node());
            }
            media.put("location", location.node());
          }
          if (rs.getString("camera") != null) {
            media.put("camera", ContentSource.JSON.readTree(rs.getString("camera")));
          }
          media.put("focal", object().decimal("x", rs.getBigDecimal("focal_x")).decimal("y", rs.getBigDecimal("focal_y")).node())
              .text("placeholder", rs)
              .put("credit", object().text("author", rs.getString("credit_author")).text("source", rs.getString("credit_source"))
                  .text("sourceUrl", rs.getString("credit_source_url")).text("license", rs.getString("credit_license"))
                  .text("licenseUrl", rs.getString("credit_license_url")).node());
          Obj original = object().text("file", rs.getString("o_file")).text("format", rs.getString("o_format"))
              .integer("width", rs.getInt("o_width")).integer("height", rs.getInt("o_height"))
              .longValue("bytes", rs.getLong("o_bytes")).text("sha256", rs.getString("o_sha256"))
              .text("url", rs.getString("o_url"));
          if (rs.getString("raw_file") != null) {
            original.put("raw", object().text("file", rs.getString("raw_file")).longValue("bytes", rs.getLong("raw_bytes"))
                .text("sha256", rs.getString("raw_sha256")).node());
          }
          return media.put("original", original.node()).bool("demo", rs).node();
        });
  }

  private static JsonNode galleries(Connection c) throws SQLException {
    Map<String, ArrayNode> items = children(c, "SELECT gallery_id AS owner, media_id, caption FROM gallery_item"
        + " ORDER BY gallery_id, position", rs -> object().text("media", rs.getString("media_id")).text("caption", rs).node());
    return rows(c, "SELECT * FROM gallery ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("title", rs).text("description", rs).text("layout", rs)
            .put("items", items.getOrDefault(rs.getString("id"), NODES.arrayNode())).bool("demo", rs).node());
  }

  private static JsonNode trips(Connection c) throws SQLException {
    Map<String, ArrayNode> stops = children(c, "SELECT trip_id AS owner, stop_order, destination_id, note, distance_km, mode"
            + " FROM trip_stop ORDER BY trip_id, stop_order",
        rs -> object().integer("order", rs.getInt("stop_order")).text("destination", rs.getString("destination_id"))
            .text("note", rs).decimal("distanceKm", rs.getBigDecimal("distance_km")).text("mode", rs).node());
    return rows(c, "SELECT * FROM trip ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("title", rs).text("summary", rs)
            .put("stops", stops.getOrDefault(rs.getString("id"), NODES.arrayNode()))
            .text("cover", rs.getString("cover_media_id")).bool("demo", rs).node());
  }

  private static JsonNode projects(Connection c) throws SQLException {
    Map<String, ArrayNode> stack = children(c, "SELECT project_id AS owner, tag_id FROM project_stack_item"
        + " ORDER BY project_id, position", rs -> NODES.stringNode(rs.getString("tag_id")));
    return rows(c, "SELECT * FROM project ORDER BY catalog_position",
        rs -> object().text("id", rs).text("slug", rs).text("name", rs).text("summary", rs).text("status", rs)
            .integer("year", rs).put("stack", stack.getOrDefault(rs.getString("id"), NODES.arrayNode()))
            .text("repositoryUrl", rs.getString("repository_url")).text("cover", rs.getString("cover_media_id"))
            .bool("demo", rs).node());
  }

  /** Artículos publicados: la identidad de article más el contenido de su revisión publicada. */
  private static JsonNode articles(Connection c, Map<String, JsonNode> bodies) throws SQLException {
    return rows(c, "SELECT a.*, article_revision_content(a.published_revision_id)::text AS content FROM article a"
            + " WHERE a.published_revision_id IS NOT NULL ORDER BY a.catalog_position",
        rs -> {
          ObjectNode content = (ObjectNode) ContentSource.JSON.readTree(rs.getString("content"));
          JsonNode blocks = content.remove("blocks");
          String slug = rs.getString("slug");
          bodies.put(slug, object().text("slug", slug).put("blocks", blocks).node());
          Obj record = object().text("id", rs).text("slug", rs).text("category", rs.getString("category_id"))
              .integer("order", rs.getInt("editorial_order")).text("publishedAt", rs.getString("published_at"))
              .text("updatedAt", rs.getString("updated_at")).bool("featured", rs).bool("demo", rs);
          content.properties().forEach(e -> record.put(e.getKey(), e.getValue()));
          return record.node();
        });
  }

  // --- Utilidades de lectura --------------------------------------------------------------------------

  @FunctionalInterface
  private interface RowMapper {
    JsonNode map(ResultSet rs) throws SQLException;
  }

  private static ArrayNode rows(Connection c, String sql, RowMapper mapper) throws SQLException {
    ArrayNode out = NODES.arrayNode();
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(mapper.map(rs));
      }
    }
    return out;
  }

  /** Filas hijas agrupadas por la columna «owner», en el orden de la consulta. */
  private static Map<String, ArrayNode> children(Connection c, String sql, RowMapper mapper) throws SQLException {
    Map<String, ArrayNode> out = new LinkedHashMap<>();
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.computeIfAbsent(rs.getString("owner"), k -> NODES.arrayNode()).add(mapper.map(rs));
      }
    }
    return out;
  }

  private static ArrayNode intArray(Array array) throws SQLException {
    ArrayNode out = NODES.arrayNode();
    for (Object value : (Object[]) array.getArray()) {
      out.add(((Number) value).intValue());
    }
    return out;
  }

  private static Obj object() {
    return new Obj();
  }

  /** Objeto JSON que solo recibe las claves con valor (NULL en la base de datos = ausente). */
  private static final class Obj {
    private final ObjectNode node = NODES.objectNode();

    Obj text(String column, ResultSet rs) throws SQLException {
      return text(toKey(column), rs.getString(column));
    }

    Obj text(String key, String value) {
      if (value != null) node.put(key, value);
      return this;
    }

    Obj integer(String column, ResultSet rs) throws SQLException {
      int value = rs.getInt(column);
      return rs.wasNull() ? this : integer(toKey(column), value);
    }

    Obj integer(String key, int value) {
      node.put(key, value);
      return this;
    }

    Obj longValue(String key, long value) {
      node.put(key, value);
      return this;
    }

    Obj decimal(String key, BigDecimal value) {
      if (value != null) node.set(key, NODES.numberNode(value.stripTrailingZeros()));
      return this;
    }

    Obj bool(String column, ResultSet rs) throws SQLException {
      node.put(column, rs.getBoolean(column));
      return this;
    }

    Obj put(String key, JsonNode value) {
      if (value != null) node.set(key, value);
      return this;
    }

    JsonNode node() {
      return node;
    }

    private static String toKey(String column) {
      StringBuilder key = new StringBuilder();
      boolean upper = false;
      for (char ch : column.toCharArray()) {
        if (ch == '_') {
          upper = true;
        } else {
          key.append(upper ? Character.toUpperCase(ch) : ch);
          upper = false;
        }
      }
      return key.toString();
    }
  }

  // --- Escritura JSON estable ------------------------------------------------------------------------

  /**
   * JSON con 2 espacios, «clave: valor», LF y salto final; números en notación decimal sin
   * exponente ni ceros finales. Mismo árbol, mismos bytes.
   */
  public static final class JsonText {
    private JsonText() {}

    public static String write(JsonNode value) {
      StringBuilder out = new StringBuilder();
      value(out, value, 0);
      return out.append('\n').toString();
    }

    private static void value(StringBuilder out, JsonNode value, int depth) {
      if (value.isObject()) {
        if (value.isEmpty()) {
          out.append("{}");
          return;
        }
        out.append("{\n");
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(value.properties());
        for (int i = 0; i < entries.size(); i++) {
          indent(out, depth + 1);
          out.append(ContentSource.JSON.writeValueAsString(entries.get(i).getKey())).append(": ");
          value(out, entries.get(i).getValue(), depth + 1);
          out.append(i < entries.size() - 1 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append('}');
      } else if (value.isArray()) {
        if (value.isEmpty()) {
          out.append("[]");
          return;
        }
        out.append("[\n");
        for (int i = 0; i < value.size(); i++) {
          indent(out, depth + 1);
          value(out, value.get(i), depth + 1);
          out.append(i < value.size() - 1 ? ",\n" : "\n");
        }
        indent(out, depth);
        out.append(']');
      } else if (value.isNumber()) {
        out.append(value.isIntegralNumber()
            ? value.bigIntegerValue().toString()
            : value.decimalValue().stripTrailingZeros().toPlainString());
      } else {
        out.append(ContentSource.JSON.writeValueAsString(value));
      }
    }

    private static void indent(StringBuilder out, int depth) {
      out.append("  ".repeat(depth));
    }
  }
}
