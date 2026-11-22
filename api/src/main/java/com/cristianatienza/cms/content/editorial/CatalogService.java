package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.CoordinatePolicy;
import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.validation.CatalogIntegrity;
import com.cristianatienza.cms.content.validation.CatalogRules;
import com.cristianatienza.cms.content.validation.Issue;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Autores, lugares, itinerarios y proyectos del catálogo (fase 6.4.7, D10): leer uno, crear, editar y
 * borrar, con el login de la API (cms_app: INSERT, UPDATE y DELETE en esas tablas y sus elementos
 * desde V3; ninguna migración nueva).
 *
 * <ul>
 *   <li><strong>Forma e integridad:</strong> exactamente las de validate.ts e integrity.ts
 *       ({@link CatalogRules}, {@link CatalogIntegrity}; paridad con TypeScript en el corpus
 *       compartido). Además, las coordenadas de un lugar con más de 6 decimales se rechazan
 *       ({@link CoordinatePolicy}): PostgreSQL las redondearía en silencio.
 *   <li><strong>Identidad:</strong> el id no cambia (los artículos referencian por id). En lugares,
 *       itinerarios y proyectos es el slug inicial; el slug se puede editar (ninguna URL pública lo
 *       usa). Un autor no tiene slug: su id se elige al crearlo.
 *   <li><strong>Concurrencia (D8):</strong> la versión es {@code xmin} del registro, solo como token
 *       opaco de comparación (texto, sin aritmética). Fila bloqueada (FOR UPDATE) y la comparación
 *       repetida en el propio UPDATE o DELETE; toda edición reescribe la fila principal, así que
 *       cambiar solo las paradas o la pila también da una versión nueva.
 *   <li><strong>Lo que una edición podría romper</strong> (se comprueba antes y se rechaza):
 *       renombrar un autor que firma fotos propias (integrity.ts exige que el autor de una foto propia
 *       sea de la revista) y cambiar las paradas de un itinerario si deja inválido un artículo que lo
 *       usa en su revisión de trabajo o en la publicada (capítulos y destinos, 6.4.1).
 *   <li><strong>Borrar:</strong> solo si nada lo usa (cualquier revisión de un artículo, también las
 *       antiguas; paradas; fotos propias firmadas); si no, 409 {@code in_use} con el uso. La clave
 *       foránea RESTRICT es la última barrera.
 * </ul>
 */
@Service
public class CatalogService {

  static final int LOCK_OBJECT = 67;

  /** Una colección gestionada: su nombre en el contrato, su tabla y su nombre en los eventos. */
  public enum Kind {
    AUTHORS("authors", "author", "author", false),
    DESTINATIONS("destinations", "destination", "destination", true),
    TRIPS("trips", "trip", "trip", true),
    PROJECTS("projects", "project", "project", true);

    final String collection;
    final String table;
    final String event;
    final boolean hasSlug;

    Kind(String collection, String table, String event, boolean hasSlug) {
      this.collection = collection;
      this.table = table;
      this.event = event;
      this.hasSlug = hasSlug;
    }
  }

  /** Un registro con su versión (token opaco) y su uso. */
  public record Detail(JsonNode record, String version, Map<String, Long> usage) {}

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();

  private final JdbcClient jdbc;
  private final EditorialAudit audit;
  private final ArticleValidation articles;

  public CatalogService(JdbcClient jdbc, EditorialAudit audit, ArticleValidation articles) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.articles = articles;
  }

  // --- Leer -------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Optional<Detail> find(Kind kind, String id) {
    return read(kind, id, false).map(row -> new Detail(row.record(), row.version(), usage(kind, id)));
  }

  // --- Validar (lo mismo al crear y al editar) ----------------------------------------------------

  /**
   * Comprueba el registro completo (con su id): forma (validate.ts), integridad del registro
   * (integrity.ts) y, en los lugares, los decimales de las coordenadas. Las rutas son las del cuerpo
   * de la petición ({@code name}, {@code location.lat}, {@code stops[1]}…).
   */
  public List<FieldError> validate(Kind kind, ObjectNode record) {
    List<FieldError> errors = new ArrayList<>();
    List<Issue> issues = CatalogRules.record(kind.collection, record, "");
    if (issues.isEmpty()) {
      issues = CatalogIntegrity.record(kind.collection, record, refs(), "");
    }
    for (Issue issue : issues) {
      if (!issue.path().equals(".id")) { // el id lo valida quien lo da (ruta o slug)
        errors.add(new FieldError(issue.path().startsWith(".") ? issue.path().substring(1) : issue.path(),
            issue.message()));
      }
    }
    if (kind == Kind.DESTINATIONS && errors.isEmpty()) {
      JsonNode location = record.get("location");
      for (String axis : List.of("lat", "lng")) {
        BigDecimal value = location.get(axis).decimalValue();
        if (Math.max(0, value.stripTrailingZeros().scale()) > CoordinatePolicy.Use.DESTINATION.maxDecimals()) {
          errors.add(new FieldError("location." + axis, "como mucho " + CoordinatePolicy.Use.DESTINATION.maxDecimals()
              + " decimales (la base de datos redondearía el valor)"));
        }
      }
    }
    return errors;
  }

  // --- Crear ------------------------------------------------------------------------------------

  @Transactional
  public String create(Kind kind, ObjectNode record, UUID userId) {
    lock();
    String id = record.get("id").stringValue();
    String slug = kind.hasSlug ? record.get("slug").stringValue() : id;
    if (taken(kind, slug, null)) {
      throw kind.hasSlug ? conflict("slug_taken", "ya hay un registro con ese slug")
          : conflict("id_taken", "ya hay un autor con ese id");
    }
    List<FieldError> errors = validate(kind, record);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    int position = jdbc.sql("SELECT coalesce(max(catalog_position), 0) + 1 FROM " + kind.table)
        .query(Integer.class).single();
    try {
      insert(kind, record, position);
      writeChildren(kind, id, record);
    } catch (DataAccessException e) {
      throw translate(e);
    }
    audit.record("content." + kind.event + "_created", userId, Map.of(kind.event, id));
    return id;
  }

  // --- Editar -----------------------------------------------------------------------------------

  /** Reemplaza el registro (sin su id) si {@code version} es la actual. False si nada cambia. */
  @Transactional
  public boolean update(Kind kind, String id, String version, ObjectNode record, UUID userId) {
    lock();
    Row row = read(kind, id, true).orElseThrow(EditorialException::notFound);
    if (!row.version().equals(version)) {
      throw versionConflict(row.version());
    }
    List<FieldError> errors = validate(kind, record);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    if (row.record().equals(record)) {
      return false;
    }
    if (kind.hasSlug && !record.get("slug").equals(row.record().get("slug"))
        && taken(kind, record.get("slug").stringValue(), id)) {
      throw conflict("slug_taken", "ya hay un registro con ese slug");
    }
    if (kind == Kind.AUTHORS && !record.get("name").equals(row.record().get("name"))) {
      long photos = ownPhotos(row.record().get("name").stringValue());
      if (photos > 0) {
        throw EditorialException.conflict("in_use", "hay fotos propias firmadas con este nombre: renombrar al autor"
            + " las dejaría sin autor de la revista", Map.of("usage", Map.of("media", photos)));
      }
    }
    if (kind == Kind.TRIPS && !record.get("stops").equals(row.record().get("stops"))) {
      checkArticlesWithStops(id, record.get("stops"));
    }
    List<String> changed = new ArrayList<>();
    for (String key : union(row.record(), record)) {
      if (!java.util.Objects.equals(row.record().get(key), record.get(key))) {
        changed.add(key);
      }
    }
    try {
      int updated = update(kind, id, version, record);
      if (updated != 1) {
        throw versionConflict(null);
      }
      writeChildren(kind, id, record);
    } catch (DataAccessException e) {
      throw translate(e);
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put(kind.event, id);
    details.put("fields", String.join(",", changed));
    audit.record("content." + kind.event + "_updated", userId, details);
    return true;
  }

  // --- Borrar -----------------------------------------------------------------------------------

  @Transactional
  public void delete(Kind kind, String id, String version, UUID userId) {
    Row row = read(kind, id, true).orElseThrow(EditorialException::notFound);
    if (!row.version().equals(version)) {
      throw versionConflict(row.version());
    }
    Map<String, Long> usage = usage(kind, id);
    if (usage.values().stream().anyMatch(n -> n > 0)) {
      throw EditorialException.conflict("in_use", "está en uso: no se puede borrar", Map.of("usage", usage));
    }
    int deleted;
    try {
      deleted = jdbc.sql("DELETE FROM " + kind.table + " WHERE id = :id AND xmin::text = :version")
          .param("id", id).param("version", version).update();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    if (deleted != 1) {
      throw versionConflict(null);
    }
    audit.record("content." + kind.event + "_deleted", userId, Map.of(kind.event, id));
  }

  // --- Lectura de un registro en la forma del contrato ------------------------------------------------

  private record Row(JsonNode record, String version) {}

  private Optional<Row> read(Kind kind, String id, boolean forUpdate) {
    String lock = forUpdate ? " FOR UPDATE" : "";
    return switch (kind) {
      case AUTHORS -> jdbc.sql("SELECT id, name, role, bio, avatar_media_id, xmin::text AS version FROM author"
              + " WHERE id = :id" + lock)
          .param("id", id).query((rs, n) -> {
            ObjectNode r = object(rs, "id", "id", "name", "name", "role", "role");
            optional(r, "bio", rs.getString("bio"));
            optional(r, "avatar", rs.getString("avatar_media_id"));
            return new Row(r, rs.getString("version"));
          }).optional();
      case DESTINATIONS -> jdbc.sql("SELECT id, slug, name, country, region, lat, lng, elevation, summary,"
              + " cover_media_id, demo, xmin::text AS version FROM destination WHERE id = :id" + lock)
          .param("id", id).query((rs, n) -> {
            ObjectNode r = object(rs, "id", "id", "slug", "slug", "name", "name", "country", "country");
            optional(r, "region", rs.getString("region"));
            ObjectNode location = r.putObject("location");
            location.set("lat", number(rs.getBigDecimal("lat")));
            location.set("lng", number(rs.getBigDecimal("lng")));
            if (rs.getObject("elevation") != null) {
              r.put("elevation", rs.getInt("elevation"));
            }
            r.put("summary", rs.getString("summary"));
            optional(r, "cover", rs.getString("cover_media_id"));
            r.put("demo", rs.getBoolean("demo"));
            return new Row(r, rs.getString("version"));
          }).optional();
      case TRIPS -> jdbc.sql("SELECT id, slug, title, summary, cover_media_id, demo, xmin::text AS version FROM trip"
              + " WHERE id = :id" + lock)
          .param("id", id).query((rs, n) -> {
            ObjectNode r = object(rs, "id", "id", "slug", "slug", "title", "title", "summary", "summary");
            r.set("stops", JsonNodeFactory.instance.arrayNode());
            optional(r, "cover", rs.getString("cover_media_id"));
            r.put("demo", rs.getBoolean("demo"));
            return new Row(r, rs.getString("version"));
          }).optional().map(row -> {
            ArrayNode stops = (ArrayNode) row.record().get("stops");
            jdbc.sql("SELECT stop_order, destination_id, note, distance_km, mode FROM trip_stop WHERE trip_id = :id"
                    + " ORDER BY stop_order")
                .param("id", id).query(rs -> {
                  ObjectNode stop = stops.addObject();
                  stop.put("order", rs.getInt("stop_order"));
                  stop.put("destination", rs.getString("destination_id"));
                  optional(stop, "note", rs.getString("note"));
                  if (rs.getBigDecimal("distance_km") != null) {
                    stop.set("distanceKm", number(rs.getBigDecimal("distance_km")));
                  }
                  optional(stop, "mode", rs.getString("mode"));
                });
            return row;
          });
      case PROJECTS -> jdbc.sql("SELECT id, slug, name, summary, status, year, repository_url, cover_media_id, demo,"
              + " xmin::text AS version FROM project WHERE id = :id" + lock)
          .param("id", id).query((rs, n) -> {
            ObjectNode r = object(rs, "id", "id", "slug", "slug", "name", "name", "summary", "summary",
                "status", "status");
            r.put("year", rs.getInt("year"));
            r.set("stack", JsonNodeFactory.instance.arrayNode());
            optional(r, "repositoryUrl", rs.getString("repository_url"));
            optional(r, "cover", rs.getString("cover_media_id"));
            r.put("demo", rs.getBoolean("demo"));
            return new Row(r, rs.getString("version"));
          }).optional().map(row -> {
            ArrayNode stack = (ArrayNode) row.record().get("stack");
            jdbc.sql("SELECT tag_id FROM project_stack_item WHERE project_id = :id ORDER BY position")
                .param("id", id).query(String.class).list().forEach(stack::add);
            return row;
          });
    };
  }

  private static ObjectNode object(ResultSet rs, String... pairs) throws SQLException {
    ObjectNode r = JsonNodeFactory.instance.objectNode();
    for (int i = 0; i < pairs.length; i += 2) {
      r.put(pairs[i], rs.getString(pairs[i + 1]));
    }
    return r;
  }

  private static void optional(ObjectNode r, String key, String value) {
    if (value != null) {
      r.put(key, value);
    }
  }

  /** Un NUMERIC como lo escribe el contrato: sin ceros finales (38.345210 → 38.34521; 3.000000 → 3). */
  private static JsonNode number(BigDecimal value) {
    return JSON.readTree(value.stripTrailingZeros().toPlainString());
  }

  // --- Escritura ---------------------------------------------------------------------------------

  private void insert(Kind kind, JsonNode r, int position) {
    switch (kind) {
      case AUTHORS -> jdbc.sql("INSERT INTO author (id, name, role, bio, avatar_media_id, catalog_position)"
              + " VALUES (:id, :name, :role, :bio, :avatar, :position)")
          .param("id", text(r, "id")).param("name", text(r, "name")).param("role", text(r, "role"))
          .param("bio", text(r, "bio")).param("avatar", text(r, "avatar")).param("position", position).update();
      case DESTINATIONS -> jdbc.sql("INSERT INTO destination (id, slug, name, country, region, lat, lng, elevation,"
              + " summary, cover_media_id, demo, catalog_position) VALUES (:id, :slug, :name, :country, :region,"
              + " :lat, :lng, :elevation, :summary, :cover, :demo, :position)")
          .params(destinationParams(r)).param("id", text(r, "id")).param("position", position).update();
      case TRIPS -> jdbc.sql("INSERT INTO trip (id, slug, title, summary, cover_media_id, demo, catalog_position)"
              + " VALUES (:id, :slug, :title, :summary, :cover, :demo, :position)")
          .params(tripParams(r)).param("id", text(r, "id")).param("position", position).update();
      case PROJECTS -> jdbc.sql("INSERT INTO project (id, slug, name, summary, status, year, repository_url,"
              + " cover_media_id, demo, catalog_position) VALUES (:id, :slug, :name, :summary, :status, :year,"
              + " :repository, :cover, :demo, :position)")
          .params(projectParams(r)).param("id", text(r, "id")).param("position", position).update();
    }
  }

  /** UPDATE de la fila principal (siempre: así cambia su xmin aunque solo cambien las paradas o la pila). */
  private int update(Kind kind, String id, String version, JsonNode r) {
    String where = " WHERE id = :id AND xmin::text = :version";
    return switch (kind) {
      case AUTHORS -> jdbc.sql("UPDATE author SET name = :name, role = :role, bio = :bio, avatar_media_id = :avatar"
              + where)
          .param("name", text(r, "name")).param("role", text(r, "role")).param("bio", text(r, "bio"))
          .param("avatar", text(r, "avatar")).param("id", id).param("version", version).update();
      case DESTINATIONS -> jdbc.sql("UPDATE destination SET slug = :slug, name = :name, country = :country,"
              + " region = :region, lat = :lat, lng = :lng, elevation = :elevation, summary = :summary,"
              + " cover_media_id = :cover, demo = :demo" + where)
          .params(destinationParams(r)).param("id", id).param("version", version).update();
      case TRIPS -> jdbc.sql("UPDATE trip SET slug = :slug, title = :title, summary = :summary,"
              + " cover_media_id = :cover, demo = :demo" + where)
          .params(tripParams(r)).param("id", id).param("version", version).update();
      case PROJECTS -> jdbc.sql("UPDATE project SET slug = :slug, name = :name, summary = :summary, status = :status,"
              + " year = :year, repository_url = :repository, cover_media_id = :cover, demo = :demo" + where)
          .params(projectParams(r)).param("id", id).param("version", version).update();
    };
  }

  /** Paradas o pila: se sustituyen enteras (las claves de orden son diferibles). */
  private void writeChildren(Kind kind, String id, JsonNode r) {
    if (kind == Kind.TRIPS) {
      jdbc.sql("DELETE FROM trip_stop WHERE trip_id = :id").param("id", id).update();
      for (JsonNode stop : r.get("stops").values()) {
        jdbc.sql("INSERT INTO trip_stop (trip_id, stop_order, destination_id, note, distance_km, mode)"
                + " VALUES (:trip, :order, :destination, :note, :distance, :mode)")
            .param("trip", id).param("order", stop.get("order").intValue())
            .param("destination", text(stop, "destination")).param("note", text(stop, "note"))
            .param("distance", stop.has("distanceKm") ? stop.get("distanceKm").decimalValue() : null)
            .param("mode", text(stop, "mode")).update();
      }
    } else if (kind == Kind.PROJECTS) {
      jdbc.sql("DELETE FROM project_stack_item WHERE project_id = :id").param("id", id).update();
      JsonNode stack = r.get("stack");
      for (int i = 0; i < stack.size(); i++) {
        jdbc.sql("INSERT INTO project_stack_item (project_id, position, tag_id) VALUES (:project, :position, :tag)")
            .param("project", id).param("position", i + 1).param("tag", stack.get(i).stringValue()).update();
      }
    }
  }

  private static Map<String, Object> destinationParams(JsonNode r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("slug", text(r, "slug"));
    p.put("name", text(r, "name"));
    p.put("country", text(r, "country"));
    p.put("region", text(r, "region"));
    p.put("lat", r.get("location").get("lat").decimalValue());
    p.put("lng", r.get("location").get("lng").decimalValue());
    p.put("elevation", r.has("elevation") ? r.get("elevation").intValue() : null);
    p.put("summary", text(r, "summary"));
    p.put("cover", text(r, "cover"));
    p.put("demo", r.get("demo").booleanValue());
    return p;
  }

  private static Map<String, Object> tripParams(JsonNode r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("slug", text(r, "slug"));
    p.put("title", text(r, "title"));
    p.put("summary", text(r, "summary"));
    p.put("cover", text(r, "cover"));
    p.put("demo", r.get("demo").booleanValue());
    return p;
  }

  private static Map<String, Object> projectParams(JsonNode r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("slug", text(r, "slug"));
    p.put("name", text(r, "name"));
    p.put("summary", text(r, "summary"));
    p.put("status", text(r, "status"));
    p.put("year", r.get("year").intValue());
    p.put("repository", text(r, "repositoryUrl"));
    p.put("cover", text(r, "cover"));
    p.put("demo", r.get("demo").booleanValue());
    return p;
  }

  private static String text(JsonNode r, String key) {
    JsonNode value = r.get(key);
    return value == null || value.isNull() ? null : value.stringValue();
  }

  // --- Lo que podría romper una edición ----------------------------------------------------------------

  /** Fotos propias firmadas con ese nombre (integrity.ts: su autor debe ser uno de la revista). */
  private long ownPhotos(String name) {
    return jdbc.sql("SELECT count(*) FROM media WHERE credit_source = 'propia' AND credit_author = :name")
        .param("name", name).query(Long.class).single();
  }

  /**
   * Los artículos cuya revisión de trabajo o publicada usa el itinerario, validados con las paradas
   * nuevas: si alguno queda con errores (un capítulo cuyo destino deja de ser parada, un destino que
   * pasa a repetir una parada…), 409 {@code breaks_articles} con cada artículo y sus incidencias.
   */
  private void checkArticlesWithStops(String tripId, JsonNode stops) {
    List<String> destinations = new ArrayList<>();
    stops.values().forEach(s -> destinations.add(s.get("destination").stringValue()));
    record Using(String article, String slug, String category, Integer order, String publishedAt, String updatedAt,
        boolean featured, boolean demo, long revision, int number, String content) {}
    List<Using> using = jdbc.sql("""
            SELECT a.id, a.slug, a.category_id, a.editorial_order, a.published_at::text, a.updated_at::text,
                   a.featured, a.demo, r.id, r.number, article_revision_content(r.id)::text
            FROM article a JOIN article_revision r ON r.id IN (a.head_revision_id, a.published_revision_id)
            WHERE r.trip_id = :trip ORDER BY a.id, r.number""")
        .param("trip", tripId)
        .query((rs, n) -> new Using(rs.getString(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
            rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8), rs.getLong(9), rs.getInt(10),
            rs.getString(11)))
        .list();
    List<Map<String, Object>> broken = new ArrayList<>();
    for (Using u : using) {
      ArticleValidation.Meta meta = new ArticleValidation.Meta(u.article(), u.slug(), u.category(), u.order(),
          u.publishedAt(), u.updatedAt(), u.featured(), u.demo());
      ArticleValidation.Result result = articles.checkStored(meta, JSON.readTree(u.content()),
          Map.of(tripId, destinations));
      if (!result.errors().isEmpty()) {
        broken.add(Map.of("article", u.article(), "revision", u.number(), "errors", result.errors()));
      }
    }
    if (!broken.isEmpty()) {
      throw EditorialException.conflict("breaks_articles",
          "el cambio de paradas dejaría inválidos artículos que usan el itinerario", Map.of("articles", broken));
    }
  }

  // --- Uso ---------------------------------------------------------------------------------------

  /** Quién usa el registro (lo que impide borrarlo). */
  private Map<String, Long> usage(Kind kind, String id) {
    Map<String, Long> usage = new LinkedHashMap<>();
    switch (kind) {
      case AUTHORS -> {
        usage.put("articles", count("SELECT count(DISTINCT article_id) FROM article_revision WHERE author_id = :id", id));
        String name = jdbc.sql("SELECT name FROM author WHERE id = :id").param("id", id).query(String.class).optional()
            .orElse(null);
        usage.put("media", name == null ? 0 : ownPhotos(name));
      }
      case DESTINATIONS -> {
        usage.put("articles", count("""
            SELECT count(DISTINCT r.article_id) FROM article_revision r
            WHERE EXISTS (SELECT 1 FROM article_revision_destination d WHERE d.revision_id = r.id AND d.destination_id = :id)
               OR EXISTS (SELECT 1 FROM article_revision_block_ref b WHERE b.revision_id = r.id AND b.destination_id = :id)
            """, id));
        usage.put("trips", count("SELECT count(DISTINCT trip_id) FROM trip_stop WHERE destination_id = :id", id));
      }
      case TRIPS -> usage.put("articles", count("""
          SELECT count(DISTINCT r.article_id) FROM article_revision r
          WHERE r.trip_id = :id
             OR EXISTS (SELECT 1 FROM article_revision_block_ref b WHERE b.revision_id = r.id AND b.trip_id = :id)
          """, id));
      case PROJECTS -> usage.put("articles",
          count("SELECT count(DISTINCT article_id) FROM article_revision WHERE project_id = :id", id));
    }
    return usage;
  }

  private long count(String sql, String id) {
    return jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  // --- Apoyo ------------------------------------------------------------------------------------

  private void lock() {
    jdbc.sql("SELECT pg_advisory_xact_lock(:class, :object)").param("class", ArticleWriteService.LOCK_CLASS)
        .param("object", LOCK_OBJECT).query((rs, n) -> 1).single();
  }

  /** Ocupado si es el slug o el id de otro registro de la colección (los ids no se reutilizan). */
  private boolean taken(Kind kind, String slug, String exceptId) {
    String condition = kind.hasSlug ? "(slug = :slug OR id = :slug)" : "id = :slug";
    return jdbc.sql("SELECT count(*) FROM " + kind.table + " WHERE " + condition + " AND id IS DISTINCT FROM :except")
        .param("slug", slug).param("except", exceptId).query(Long.class).single() > 0;
  }

  private CatalogIntegrity.Refs refs() {
    return new CatalogIntegrity.Refs(ids("SELECT id FROM media"), ids("SELECT id FROM media WHERE retired"),
        ids("SELECT id FROM tag"), ids("SELECT id FROM destination"));
  }

  private Set<String> ids(String sql) {
    return new LinkedHashSet<>(jdbc.sql(sql).query(String.class).list());
  }

  private static List<String> union(JsonNode a, JsonNode b) {
    Set<String> keys = new LinkedHashSet<>(a.propertyNames());
    keys.addAll(b.propertyNames());
    keys.remove("id");
    return new ArrayList<>(keys);
  }

  private static EditorialException conflict(String code, String detail) {
    return EditorialException.conflict(code, detail, Map.of());
  }

  private static EditorialException versionConflict(String current) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("currentVersion", current);
    return EditorialException.conflict("version_conflict", "el registro ha cambiado desde que se leyó", properties);
  }

  /** Carreras con otro cambio: duplicado, referencia que impide borrar o una referencia que ya no vale. */
  private RuntimeException translate(DataAccessException e) {
    Throwable cause = e;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    String state = cause instanceof SQLException sql ? sql.getSQLState() : null;
    if ("23505".equals(state)) {
      return conflict("slug_taken", "ya hay un registro con ese slug");
    }
    if ("23001".equals(state)) {
      return conflict("in_use", "está en uso: no se puede borrar");
    }
    if ("23503".equals(state) || "23514".equals(state)) {
      return EditorialException.invalidContent(List.of(new FieldError("",
          "una referencia o un valor ya no es válido (otro cambio simultáneo): vuelve a validar")));
    }
    return e;
  }
}
