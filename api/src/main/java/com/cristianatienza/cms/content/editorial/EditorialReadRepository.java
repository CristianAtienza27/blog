package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleSummary;
import com.cristianatienza.cms.content.editorial.EditorialViews.AuthorItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.CategoryItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.DestinationItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.GalleryItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.MediaItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.Page;
import com.cristianatienza.cms.content.editorial.EditorialViews.ProjectItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.RevisionDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.RevisionRef;
import com.cristianatienza.cms.content.editorial.EditorialViews.RevisionSummary;
import com.cristianatienza.cms.content.editorial.EditorialViews.TagItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.TripItem;
import com.cristianatienza.cms.content.io.ContentContract;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lecturas de la API editorial (fase 6.4.2), con el login de la API (cms_app: SELECT en el catálogo
 * y las revisiones, EXECUTE en article_revision_content). Solo lee: ninguna consulta escribe.
 * Cada método es una transacción de solo lectura REPEATABLE READ: el total y la página de un
 * listado salen de la misma instantánea de la base de datos.
 */
@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EditorialReadRepository {

  /** Números exactos al leer el contenido (jsonb conserva el valor: 0.5, 1e-7…). */
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();

  /** Orden del contenido de una revisión: el del contrato (validate.ts), sin los campos del artículo. */
  private static final List<String> CONTENT_ORDER = List.of("title", "dek", "excerpt", "tags", "format", "cover",
      "author", "readingMinutes", "trip", "destinations", "project", "seo", "blocks");

  private static final String ARTICLE_COLUMNS = """
      a.id, a.slug, a.category_id, a.editorial_order, a.featured, a.demo, a.published_at, a.updated_at,
      a.lock_version,
      h.id AS head_id, h.number AS head_number, h.origin AS head_origin, h.created_at AS head_created_at,
      hf.number AS head_restored_from, h.title, h.format, h.author_id, h.cover_media_id,
      p.id AS published_id, p.number AS published_number, p.origin AS published_origin,
      p.created_at AS published_created_at, pf.number AS published_restored_from,
      ARRAY(SELECT t.tag_id::text FROM article_revision_tag t WHERE t.revision_id = h.id ORDER BY t.position) AS tags
      """;

  private static final String ARTICLE_FROM = """
      FROM article a
      JOIN category c ON c.id = a.category_id
      LEFT JOIN article_revision h ON h.id = a.head_revision_id
      LEFT JOIN article_revision hf ON hf.id = h.restored_from_revision_id
      LEFT JOIN article_revision p ON p.id = a.published_revision_id
      LEFT JOIN article_revision pf ON pf.id = p.restored_from_revision_id
      """;

  private final JdbcClient jdbc;

  public EditorialReadRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // --- Artículos ------------------------------------------------------------------------------

  public Page<ArticleSummary> articles(ArticleQuery query) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    if (query.state() != null) {
      where.add(switch (query.state()) {
        case "borrador" -> "a.published_revision_id IS NULL";
        case "publicado" -> "a.published_revision_id = a.head_revision_id";
        default -> "a.published_revision_id IS NOT NULL AND a.published_revision_id <> a.head_revision_id";
      });
    }
    if (query.section() != null) {
      where.add("a.category_id = :section");
      params.put("section", query.section());
    }
    if (query.tag() != null) {
      where.add("EXISTS (SELECT 1 FROM article_revision_tag t WHERE t.revision_id = a.head_revision_id"
          + " AND t.tag_id = :tag)");
      params.put("tag", query.tag());
    }
    if (query.text() != null) {
      where.add("(h.title ILIKE :text ESCAPE '\\' OR a.slug ILIKE :text ESCAPE '\\')");
      params.put("text", "%" + likeEscape(query.text()) + "%");
    }
    if (query.demo() != null) {
      where.add("a.demo = :demo");
      params.put("demo", query.demo());
    }
    if (query.featured() != null) {
      where.add("a.featured = :featured");
      params.put("featured", query.featured());
    }
    String filter = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
    String order = switch (query.order()) {
      case "publicado" -> "a.published_at DESC NULLS LAST, a.id";
      case "titulo" -> "h.title, a.id";
      case "seccion" -> "c.catalog_position, a.editorial_order NULLS LAST, a.id";
      default -> "h.created_at DESC NULLS LAST, a.id";
    };
    long total = statement("SELECT count(*) " + ARTICLE_FROM + filter, params).query(Long.class).single();
    params.put("limit", query.size());
    params.put("offset", (long) query.page() * query.size());
    List<ArticleSummary> items = statement("SELECT " + ARTICLE_COLUMNS + ARTICLE_FROM + filter + " ORDER BY " + order
        + " LIMIT :limit OFFSET :offset", params).query((rs, n) -> summary(rs)).list();
    return new Page<>(items, query.page(), query.size(), total);
  }

  public Optional<ArticleDetail> article(String id) {
    return jdbc.sql("SELECT " + ARTICLE_COLUMNS + ", article_revision_content(h.id)::text AS content " + ARTICLE_FROM
            + " WHERE a.id = :id")
        .param("id", id)
        .query((rs, n) -> new ArticleDetail(summary(rs), content(rs.getString("content")), null))
        .optional();
  }

  public Optional<Page<RevisionSummary>> revisions(String articleId, int page, int size) {
    Optional<long[]> pointers = pointers(articleId);
    if (pointers.isEmpty()) {
      return Optional.empty();
    }
    long total = jdbc.sql("SELECT count(*) FROM article_revision WHERE article_id = :id").param("id", articleId)
        .query(Long.class).single();
    List<RevisionSummary> items = jdbc.sql("""
            SELECT r.id, r.number, r.origin, f.number AS restored_from, r.created_at
            FROM article_revision r LEFT JOIN article_revision f ON f.id = r.restored_from_revision_id
            WHERE r.article_id = :id ORDER BY r.number DESC LIMIT :limit OFFSET :offset""")
        .param("id", articleId).param("limit", size).param("offset", (long) page * size)
        .query((rs, n) -> revisionSummary(rs, pointers.get()))
        .list();
    return Optional.of(new Page<>(items, page, size, total));
  }

  public Optional<RevisionDetail> revision(String articleId, int number) {
    Optional<long[]> pointers = pointers(articleId);
    if (pointers.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql("""
            SELECT r.id, r.number, r.origin, f.number AS restored_from, r.created_at,
                   article_revision_content(r.id)::text AS content
            FROM article_revision r LEFT JOIN article_revision f ON f.id = r.restored_from_revision_id
            WHERE r.article_id = :id AND r.number = :number""")
        .param("id", articleId).param("number", number)
        .query((rs, n) -> new RevisionDetail(revisionSummary(rs, pointers.get()), content(rs.getString("content"))))
        .optional();
  }

  /** {revisión de trabajo, publicada} (0 = ninguna) de un artículo, o vacío si no existe. */
  private Optional<long[]> pointers(String articleId) {
    return jdbc.sql("SELECT coalesce(head_revision_id, 0), coalesce(published_revision_id, 0) FROM article WHERE id = :id")
        .param("id", articleId)
        .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
        .optional();
  }

  // --- Catálogos ------------------------------------------------------------------------------

  public List<CategoryItem> categories() {
    return jdbc.sql("SELECT id, slug, name, description FROM category ORDER BY catalog_position")
        .query((rs, n) -> new CategoryItem(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("description")))
        .list();
  }

  public List<TagItem> tags() {
    return jdbc.sql("""
            SELECT g.id, g.slug, g.label, g.xmin::text AS version,
                   EXISTS (SELECT 1 FROM article_revision_tag t WHERE t.tag_id = g.id)
                   OR EXISTS (SELECT 1 FROM project_stack_item p WHERE p.tag_id = g.id) AS in_use
            FROM tag g ORDER BY g.catalog_position""")
        .query((rs, n) -> new TagItem(rs.getString("id"), rs.getString("slug"), rs.getString("label"),
            rs.getString("version"), rs.getBoolean("in_use")))
        .list();
  }

  public List<AuthorItem> authors() {
    return jdbc.sql("SELECT id, name, role, bio, avatar_media_id FROM author ORDER BY catalog_position")
        .query((rs, n) -> new AuthorItem(rs.getString("id"), rs.getString("name"), rs.getString("role"),
            rs.getString("bio"), rs.getString("avatar_media_id")))
        .list();
  }

  public List<DestinationItem> destinations() {
    return jdbc.sql("SELECT id, slug, name, country, region, cover_media_id, demo FROM destination"
            + " ORDER BY catalog_position")
        .query((rs, n) -> new DestinationItem(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("country"), rs.getString("region"), rs.getString("cover_media_id"), rs.getBoolean("demo")))
        .list();
  }

  public List<TripItem> trips() {
    return jdbc.sql("""
            SELECT t.id, t.slug, t.title, t.cover_media_id, t.demo,
                   ARRAY(SELECT s.destination_id::text FROM trip_stop s WHERE s.trip_id = t.id ORDER BY s.stop_order)
                     AS stops
            FROM trip t ORDER BY t.catalog_position""")
        .query((rs, n) -> new TripItem(rs.getString("id"), rs.getString("slug"), rs.getString("title"),
            strings(rs.getArray("stops")), rs.getString("cover_media_id"), rs.getBoolean("demo")))
        .list();
  }

  public List<ProjectItem> projects() {
    return jdbc.sql("SELECT id, slug, name, status, year, cover_media_id, demo FROM project ORDER BY catalog_position")
        .query((rs, n) -> new ProjectItem(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("status"), rs.getInt("year"), rs.getString("cover_media_id"), rs.getBoolean("demo")))
        .list();
  }

  /** Nunca toca media_original (datos privados del original). */
  public List<MediaItem> media() {
    return jdbc.sql("SELECT id, path, width, height, alt, retired, demo FROM media ORDER BY catalog_position")
        .query((rs, n) -> new MediaItem(rs.getString("id"), rs.getString("path"), rs.getInt("width"),
            rs.getInt("height"), rs.getString("alt"), rs.getBoolean("retired"), rs.getBoolean("demo")))
        .list();
  }

  public List<GalleryItem> galleries() {
    return jdbc.sql("""
            SELECT g.id, g.slug, g.title, g.layout, g.demo,
                   (SELECT count(*) FROM gallery_item i WHERE i.gallery_id = g.id) AS items
            FROM gallery g ORDER BY g.catalog_position""")
        .query((rs, n) -> new GalleryItem(rs.getString("id"), rs.getString("slug"), rs.getString("title"),
            rs.getString("layout"), rs.getInt("items"), rs.getBoolean("demo")))
        .list();
  }

  // --- Mapeo ----------------------------------------------------------------------------------

  private JdbcClient.StatementSpec statement(String sql, Map<String, Object> params) {
    JdbcClient.StatementSpec spec = jdbc.sql(sql);
    for (Map.Entry<String, Object> param : params.entrySet()) {
      spec = spec.param(param.getKey(), param.getValue());
    }
    return spec;
  }

  /** Escapa los comodines de LIKE (\, %, _): el texto se busca literal. */
  static String likeEscape(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static ArticleSummary summary(ResultSet rs) throws SQLException {
    // Los ids de revisión empiezan en 1: getLong da 0 cuando el puntero es NULL.
    long headId = rs.getLong("head_id");
    long publishedId = rs.getLong("published_id");
    RevisionRef head = headId == 0 ? null : ref(rs, "head");
    RevisionRef published = publishedId == 0 ? null : ref(rs, "published");
    String state = publishedId == 0 ? "borrador" : publishedId == headId ? "publicado" : "con-cambios";
    Object order = rs.getObject("editorial_order");
    return new ArticleSummary(rs.getString("id"), rs.getString("slug"), rs.getString("category_id"), state,
        rs.getString("title"), rs.getString("format"), rs.getString("author_id"), rs.getString("cover_media_id"),
        strings(rs.getArray("tags")), order == null ? null : ((Number) order).intValue(), rs.getBoolean("featured"),
        rs.getBoolean("demo"), date(rs, "published_at"), date(rs, "updated_at"), rs.getInt("lock_version"), head,
        published);
  }

  private static RevisionRef ref(ResultSet rs, String prefix) throws SQLException {
    Object restored = rs.getObject(prefix + "_restored_from");
    return new RevisionRef(rs.getLong(prefix + "_id"), rs.getInt(prefix + "_number"), rs.getString(prefix + "_origin"),
        restored == null ? null : ((Number) restored).intValue(), instant(rs, prefix + "_created_at"));
  }

  private static RevisionSummary revisionSummary(ResultSet rs, long[] pointers) throws SQLException {
    long id = rs.getLong("id");
    Object restored = rs.getObject("restored_from");
    return new RevisionSummary(id, rs.getInt("number"), rs.getString("origin"),
        restored == null ? null : ((Number) restored).intValue(), instant(rs, "created_at"), id == pointers[0],
        id == pointers[1]);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** Fechas del contrato: AAAA-MM-DD. */
  private static String date(ResultSet rs, String column) throws SQLException {
    LocalDate value = rs.getObject(column, LocalDate.class);
    return value == null ? null : value.toString();
  }

  private static List<String> strings(Array array) throws SQLException {
    return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
  }

  /** El contenido de una revisión en el orden del contrato, con los bloques en su orden canónico. */
  static JsonNode content(String json) {
    if (json == null) {
      return null;
    }
    JsonNode raw = JSON.readTree(json);
    ObjectNode ordered = JsonNodeFactory.instance.objectNode();
    for (String key : CONTENT_ORDER) {
      if (raw.has(key)) {
        ordered.set(key, raw.get(key));
      }
    }
    for (String key : raw.propertyNames()) {
      if (!ordered.has(key)) {
        ordered.set(key, raw.get(key));
      }
    }
    if (ordered.has("blocks")) {
      ObjectNode body = JsonNodeFactory.instance.objectNode();
      body.put("slug", "x");
      body.set("blocks", ordered.get("blocks"));
      try {
        ordered.set("blocks", ContentContract.canonical(body, ContentContract.BODY).get("blocks"));
      } catch (IllegalArgumentException unknownField) {
        // Un bloque con un campo fuera del contrato: se devuelve tal cual se guardó.
      }
    }
    return ordered;
  }
}
