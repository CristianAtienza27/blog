package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escrituras editoriales de la fase 6.4.3: crear un artículo, editar sus metadatos y guardar una
 * revisión. Con el login de la API (cms_app): INSERT de article, UPDATE de sus columnas editoriales y
 * create_article_revision; nunca los punteros ni las tablas de revisión directamente (V3).
 *
 * <p>Cada operación es UNA transacción con su evento de auditoría dentro: se confirman o se deshacen
 * juntos. Concurrencia:
 * <ul>
 *   <li>Revisiones: la operación de la base de datos bloquea el artículo y exige que la revisión base
 *       sea la de trabajo (si no, CM409 → 409 {@code revision_conflict}): nunca hay ramas.
 *   <li>Metadatos: {@code lock_version} (409 {@code version_conflict} si no coincide).
 *   <li>Altas y cambios de slug: un bloqueo asesor de transacción las ordena (misma clase que el de la
 *       publicación, otro objeto), y la restricción UNIQUE del slug es la última barrera.
 * </ul>
 */
@Service
public class ArticleWriteService {

  static final String ARTICLE_CREATED = "content.article_created";
  static final String METADATA_UPDATED = "content.article_metadata_updated";
  static final String REVISION_CREATED = "content.revision_created";
  static final String REVISION_RESTORED = "content.revision_restored";
  static final String ARTICLE_PUBLISHED = "content.article_published";
  static final String ARTICLE_UNPUBLISHED = "content.article_unpublished";
  static final String SECTION_REORDERED = "content.section_reordered";

  /** Bloqueo asesor de las altas y los cambios de slug: clase 0x434D53 ('CMS', la de la publicación), objeto 64. */
  static final int LOCK_CLASS = 0x434D53;
  static final int LOCK_OBJECT = 64;
  /** Bloqueo asesor del orden editorial: el mismo que usa publish_article (V11) al asignar un orden. */
  static final int ORDER_LOCK_OBJECT = 65;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** Para leer el contenido guardado con sus números exactos. */
  private static final JsonMapper EXACT = JsonMapper.builder()
      .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .build();

  /** Alta: el slug será también el id (inmutable). */
  public record NewArticle(String slug, String category, boolean featured, boolean demo, JsonNode content) {}

  /** Metadatos completos (PUT). Fechas en AAAA-MM-DD o null. */
  public record Metadata(int version, String slug, String category, boolean featured, boolean demo,
      String publishedAt, String updatedAt) {}

  /** Resultado de guardar: si se creó una revisión nueva (o el contenido era el mismo) y su número. */
  public record Saved(boolean created, int number) {}

  private final JdbcClient jdbc;
  private final ArticleValidation validation;
  private final EditorialAudit audit;

  public ArticleWriteService(JdbcClient jdbc, ArticleValidation validation, EditorialAudit audit) {
    this.jdbc = jdbc;
    this.validation = validation;
    this.audit = audit;
  }

  // --- Crear ------------------------------------------------------------------------------------

  /** Crea el artículo y su revisión 1 (borrador). Devuelve el id. */
  @Transactional
  public String create(NewArticle input, UUID userId) {
    lockSlugs();
    if (slugTaken(input.slug(), null)) {
      throw slugTakenConflict();
    }
    ArticleValidation.Meta meta = new ArticleValidation.Meta(input.slug(), input.slug(), input.category(), null, null,
        null, input.featured(), input.demo());
    ArticleValidation.Result checked = validation.checkSubmitted(meta, input.content());
    if (!checked.errors().isEmpty()) {
      throw EditorialException.invalidContent(checked.errors());
    }
    int position = jdbc.sql("SELECT coalesce(max(catalog_position), 0) + 1 FROM article").query(Integer.class).single();
    int number;
    try {
      jdbc.sql("INSERT INTO article (id, slug, category_id, featured, demo, catalog_position)"
              + " VALUES (:id, :id, :category, :featured, :demo, :position)")
          .param("id", input.slug()).param("category", input.category()).param("featured", input.featured())
          .param("demo", input.demo()).param("position", position)
          .update();
      number = jdbc.sql("SELECT new_number FROM create_article_revision(:id, NULL, 'edit', :content::jsonb)")
          .param("id", input.slug()).param("content", JSON.writeValueAsString(checked.content()))
          .query(Integer.class).single();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    audit(ARTICLE_CREATED, userId, Map.of("article", input.slug(), "revision", Integer.toString(number)));
    return input.slug();
  }

  // --- Guardar una revisión ---------------------------------------------------------------------

  /** Guarda el contenido como revisión nueva a partir de {@code baseRevisionId} (la de trabajo). */
  @Transactional
  public Saved saveRevision(String id, long baseRevisionId, JsonNode content, UUID userId) {
    ArticleRow row = lockArticle(id).orElseThrow(EditorialException::notFound);
    if (row.head() == null || row.head() != baseRevisionId) {
      throw revisionConflict(row.head());
    }
    ArticleValidation.Result checked = validation.checkSubmitted(row.meta(), content);
    if (!checked.errors().isEmpty()) {
      throw EditorialException.invalidContent(checked.errors());
    }
    String json = JSON.writeValueAsString(checked.content());
    // Igualdad de jsonb: mismas claves y valores, sin importar el orden ni la forma de los números.
    boolean unchanged = jdbc.sql("SELECT article_revision_content(:head) = :content::jsonb")
        .param("head", baseRevisionId).param("content", json).query(Boolean.class).single();
    if (unchanged) {
      return new Saved(false, row.headNumber());
    }
    int number;
    try {
      number = jdbc.sql("SELECT new_number FROM create_article_revision(:id, :base, 'edit', :content::jsonb)")
          .param("id", id).param("base", baseRevisionId).param("content", json)
          .query(Integer.class).single();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    audit(REVISION_CREATED, userId, Map.of("article", id, "revision", Integer.toString(number),
        "base", Integer.toString(row.headNumber())));
    return new Saved(true, number);
  }

  // --- Restaurar una revisión (6.4.4) -------------------------------------------------------------

  /**
   * Recupera el contenido de la revisión {@code number} como una revisión NUEVA (N+1, origin
   * {@code restore}) a partir de {@code baseRevisionId}, que debe ser la de trabajo. La revisión
   * recuperada y el resto del historial no cambian (son inmutables). El contenido recuperado se valida
   * con el catálogo ACTUAL: si usa algo que hoy no vale (una imagen retirada después, una etiqueta…),
   * 422 y no se crea nada. Si es igual al de la revisión de trabajo, no se crea ninguna revisión.
   */
  @Transactional
  public Saved restore(String id, int number, long baseRevisionId, UUID userId) {
    ArticleRow row = lockArticle(id).orElseThrow(EditorialException::notFound);
    Long revision = jdbc.sql("SELECT id FROM article_revision WHERE article_id = :id AND number = :number")
        .param("id", id).param("number", number).query(Long.class).optional()
        .orElseThrow(EditorialException::notFound);
    if (row.head() == null || row.head() != baseRevisionId) {
      throw revisionConflict(row.head());
    }
    boolean unchanged = jdbc.sql("SELECT article_revision_content(:head) = article_revision_content(:revision)")
        .param("head", baseRevisionId).param("revision", revision).query(Boolean.class).single();
    if (unchanged) {
      return new Saved(false, row.headNumber());
    }
    String stored = jdbc.sql("SELECT article_revision_content(:revision)::text").param("revision", revision)
        .query(String.class).single();
    ArticleValidation.Result checked = validation.checkStored(row.meta(), EXACT.readTree(stored));
    if (!checked.errors().isEmpty()) {
      throw EditorialException.invalidContent(checked.errors());
    }
    int created;
    try {
      created = jdbc.sql("SELECT new_number FROM restore_article_revision(:revision, :base)")
          .param("revision", revision).param("base", baseRevisionId).query(Integer.class).single();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("article", id);
    details.put("revision", Integer.toString(created));
    details.put("restoredFrom", Integer.toString(number));
    details.put("base", Integer.toString(row.headNumber()));
    audit(REVISION_RESTORED, userId, details);
    return new Saved(true, created);
  }

  // --- Metadatos ----------------------------------------------------------------------------------

  /** Reemplaza los metadatos editables. Devuelve false si no había nada que cambiar. */
  @Transactional
  public boolean updateMetadata(String id, Metadata input, UUID userId, boolean admin) {
    lockSlugs();
    ArticleRow row = lockArticle(id).orElseThrow(EditorialException::notFound);
    if (row.published() && !admin) {
      throw EditorialException.forbidden("los metadatos de un artículo publicado solo los cambia ADMIN");
    }
    if (row.version() != input.version()) {
      throw EditorialException.conflict("version_conflict", "el artículo ha cambiado desde que se leyó",
          Map.of("currentVersion", row.version()));
    }
    boolean everPublished = row.publishedAt() != null;
    if (everPublished && (!input.slug().equals(row.slug()) || !input.category().equals(row.category()))) {
      // D4: cambiarían la URL pública y romperían enlaces hasta que exista slug_redirect.
      throw EditorialException.conflict("published_locked",
          "el slug y la sección no se cambian en un artículo publicado alguna vez", Map.of());
    }
    List<FieldError> errors = new ArrayList<>();
    if (!everPublished && input.publishedAt() != null) {
      errors.add(new FieldError("publishedAt", "se fija al publicar el artículo"));
    }
    if (everPublished && input.publishedAt() == null) {
      errors.add(new FieldError("publishedAt", "un artículo publicado alguna vez conserva su fecha de publicación"));
    }
    if (input.updatedAt() != null) {
      if (input.publishedAt() == null) {
        errors.add(new FieldError("updatedAt", "solo con fecha de publicación"));
      } else if (input.updatedAt().compareTo(input.publishedAt()) < 0) {
        errors.add(new FieldError("updatedAt", "anterior a la publicación"));
      }
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    if (!input.slug().equals(row.slug()) && slugTaken(input.slug(), id)) {
      throw slugTakenConflict();
    }
    List<String> changed = new ArrayList<>();
    compare(changed, "slug", row.slug(), input.slug());
    compare(changed, "category", row.category(), input.category());
    compare(changed, "featured", row.featured(), input.featured());
    compare(changed, "demo", row.demo(), input.demo());
    compare(changed, "publishedAt", row.publishedAt(), input.publishedAt());
    compare(changed, "updatedAt", row.updatedAt(), input.updatedAt());
    if (changed.isEmpty()) {
      return false;
    }
    int updated;
    try {
      updated = jdbc.sql("""
              UPDATE article SET slug = :slug, category_id = :category, featured = :featured, demo = :demo,
                published_at = :publishedAt, updated_at = :updatedAt, lock_version = lock_version + 1
              WHERE id = :id AND lock_version = :version""")
          .param("slug", input.slug()).param("category", input.category()).param("featured", input.featured())
          .param("demo", input.demo()).param("publishedAt", date(input.publishedAt()))
          .param("updatedAt", date(input.updatedAt())).param("id", id).param("version", input.version())
          .update();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    if (updated != 1) {
      throw EditorialException.conflict("version_conflict", "el artículo ha cambiado desde que se leyó", Map.of());
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("article", id);
    details.put("version", Integer.toString(input.version() + 1));
    details.put("fields", String.join(",", changed));
    audit(METADATA_UPDATED, userId, details);
    return true;
  }

  // --- Publicar, despublicar y reordenar (6.4.5) ---------------------------------------------------

  /** Una entrada de la reordenación: el artículo y la versión que el cliente leyó. */
  public record Position(String id, int version) {}

  /**
   * Marca la revisión de TRABAJO como la publicada (publish_article, V11). Antes, en Java y con el
   * artículo bloqueado: la versión, que la revisión es del artículo y que es la de trabajo, y que su
   * contenido pasa la validación con lo PUBLICADO (los avisos de guardar aquí son errores: un enlace a
   * un artículo sin publicar no puede llegar a la web). La base de datos lo vuelve a comprobar todo.
   * Devuelve false si esa revisión ya era la publicada (nada cambia, sin auditoría).
   */
  @Transactional
  public boolean publish(String id, long revisionId, int version, UUID userId) {
    ArticleRow row = lockArticle(id).orElseThrow(EditorialException::notFound);
    if (row.version() != version) {
      throw versionConflict(row.version());
    }
    Integer number = jdbc.sql("SELECT number FROM article_revision WHERE id = :revision AND article_id = :id")
        .param("revision", revisionId).param("id", id).query(Integer.class).optional().orElse(null);
    if (number == null) {
      throw EditorialException.invalidContent(List.of(new FieldError("revisionId", "no es una revisión de este artículo")));
    }
    if (row.head() == null || row.head() != revisionId) {
      throw revisionConflict(row.head());
    }
    Long published = jdbc.sql("SELECT published_revision_id FROM article WHERE id = :id").param("id", id)
        .query(Long.class).optional().orElse(null);
    if (published != null && published == revisionId) {
      return false;
    }
    String stored = jdbc.sql("SELECT article_revision_content(:revision)::text").param("revision", revisionId)
        .query(String.class).single();
    ArticleValidation.Result checked = validation.checkStored(row.meta(), EXACT.readTree(stored));
    List<FieldError> issues = new ArrayList<>(checked.errors());
    issues.addAll(checked.warnings());
    if (!issues.isEmpty()) {
      throw EditorialException.invalidContent(issues);
    }
    Map<String, Object> result;
    try {
      result = jdbc.sql("SELECT changed, new_published_at, new_order, new_version"
              + " FROM publish_article(:id, :revision, :version)")
          .param("id", id).param("revision", revisionId).param("version", version)
          .query().singleRow();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("article", id);
    details.put("revision", Integer.toString(number));
    details.put("version", String.valueOf(result.get("new_version")));
    details.put("publishedAt", String.valueOf(result.get("new_published_at")));
    details.put("order", String.valueOf(result.get("new_order")));
    audit(ARTICLE_PUBLISHED, userId, details);
    return true;
  }

  /** Deja el artículo sin revisión publicada (unpublish_article, V11). False si no estaba publicado. */
  @Transactional
  public boolean unpublish(String id, int version, UUID userId) {
    ArticleRow row = lockArticle(id).orElseThrow(EditorialException::notFound);
    if (row.version() != version) {
      throw versionConflict(row.version());
    }
    Integer publishedNumber = jdbc.sql("SELECT r.number FROM article a JOIN article_revision r"
            + " ON r.id = a.published_revision_id WHERE a.id = :id")
        .param("id", id).query(Integer.class).optional().orElse(null);
    if (publishedNumber == null) {
      return false;
    }
    Map<String, Object> result;
    try {
      result = jdbc.sql("SELECT changed, new_version FROM unpublish_article(:id, :version)")
          .param("id", id).param("version", version).query().singleRow();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("article", id);
    details.put("revision", Integer.toString(publishedNumber));
    details.put("version", String.valueOf(result.get("new_version")));
    audit(ARTICLE_UNPUBLISHED, userId, details);
    return true;
  }

  /**
   * Renumera 1…n el orden editorial de una sección (procedimiento de modelo-de-datos.md): la lista
   * debe ser EXACTAMENTE la de los artículos de la sección que tienen orden (publicados o publicados
   * alguna vez; los borradores no tienen), cada uno con la versión que el cliente leyó. Con el mismo
   * bloqueo asesor que la asignación de orden de publish_article. Las restricciones de orden son
   * diferibles: un intercambio no falla a medio camino. False si el orden ya era ese.
   */
  @Transactional
  public boolean reorder(String section, List<Position> positions, UUID userId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:class, :object)").param("class", LOCK_CLASS)
        .param("object", ORDER_LOCK_OBJECT).query((rs, n) -> 1).single();
    List<Position> current = jdbc.sql("SELECT id, lock_version FROM article WHERE category_id = :section"
            + " AND editorial_order IS NOT NULL ORDER BY editorial_order FOR UPDATE")
        .param("section", section)
        .query((rs, n) -> new Position(rs.getString(1), rs.getInt(2))).list();
    Map<String, Integer> versions = new LinkedHashMap<>();
    current.forEach(p -> versions.put(p.id(), p.version()));
    List<FieldError> errors = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < positions.size(); i++) {
      String id = positions.get(i).id();
      if (!seen.add(id)) {
        errors.add(new FieldError("articles[" + i + "].id", "repetido: «" + id + "»"));
      } else if (!versions.containsKey(id)) {
        errors.add(new FieldError("articles[" + i + "].id", "no es un artículo con orden de esta sección: «" + id + "»"));
      }
    }
    for (String id : versions.keySet()) {
      if (!seen.contains(id)) {
        errors.add(new FieldError("articles", "falta «" + id + "»"));
      }
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    List<Map<String, Object>> conflicts = new ArrayList<>();
    for (Position p : positions) {
      if (versions.get(p.id()) != p.version()) {
        conflicts.add(Map.of("id", p.id(), "currentVersion", versions.get(p.id())));
      }
    }
    if (!conflicts.isEmpty()) {
      throw EditorialException.conflict("version_conflict", "algún artículo ha cambiado desde que se leyó",
          Map.of("conflicts", conflicts));
    }
    List<String> order = positions.stream().map(Position::id).toList();
    if (order.equals(current.stream().map(Position::id).toList())) {
      return false;
    }
    for (int i = 0; i < positions.size(); i++) {
      Position p = positions.get(i);
      jdbc.sql("UPDATE article SET editorial_order = :position, lock_version = lock_version + 1"
              + " WHERE id = :id AND lock_version = :version AND editorial_order IS DISTINCT FROM :position")
          .param("position", i + 1).param("id", p.id()).param("version", p.version()).update();
    }
    audit(SECTION_REORDERED, userId, Map.of("section", section, "articles", String.join(",", order)));
    return true;
  }

  private static EditorialException versionConflict(int current) {
    return EditorialException.conflict("version_conflict", "el artículo ha cambiado desde que se leyó",
        Map.of("currentVersion", current));
  }

  // --- Apoyo ------------------------------------------------------------------------------------

  private record ArticleRow(String slug, String category, Integer order, String publishedAt, String updatedAt,
      boolean featured, boolean demo, int version, Long head, int headNumber, boolean published, String id) {

    ArticleValidation.Meta meta() {
      return new ArticleValidation.Meta(id, slug, category, order, publishedAt, updatedAt, featured, demo);
    }
  }

  /** La fila del artículo, bloqueada hasta el final de la transacción (FOR UPDATE). */
  private Optional<ArticleRow> lockArticle(String id) {
    return jdbc.sql("""
            SELECT a.id, a.slug, a.category_id, a.editorial_order, a.published_at, a.updated_at, a.featured, a.demo,
                   a.lock_version, a.head_revision_id, coalesce(h.number, 0) AS head_number,
                   a.published_revision_id IS NOT NULL AS published
            FROM article a LEFT JOIN article_revision h ON h.id = a.head_revision_id
            WHERE a.id = :id FOR UPDATE OF a""")
        .param("id", id)
        .query((rs, n) -> new ArticleRow(rs.getString("slug"), rs.getString("category_id"),
            (Integer) rs.getObject("editorial_order"), text(rs.getObject("published_at", LocalDate.class)),
            text(rs.getObject("updated_at", LocalDate.class)), rs.getBoolean("featured"), rs.getBoolean("demo"),
            rs.getInt("lock_version"), (Long) rs.getObject("head_revision_id"), rs.getInt("head_number"),
            rs.getBoolean("published"), rs.getString("id")))
        .optional();
  }

  private void lockSlugs() {
    jdbc.sql("SELECT pg_advisory_xact_lock(:class, :object)").param("class", LOCK_CLASS).param("object", LOCK_OBJECT)
        .query((rs, n) -> 1).single();
  }

  /** Un slug está ocupado si es el slug o el id de otro artículo (los ids no se reutilizan). */
  private boolean slugTaken(String slug, String exceptId) {
    return jdbc.sql("SELECT count(*) FROM article WHERE (slug = :slug OR id = :slug) AND id IS DISTINCT FROM :except")
        .param("slug", slug).param("except", exceptId).query(Long.class).single() > 0;
  }

  private static EditorialException slugTakenConflict() {
    return EditorialException.conflict("slug_taken", "ya hay un artículo con ese slug", Map.of());
  }

  private static EditorialException revisionConflict(Long head) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("currentRevisionId", head);
    return EditorialException.conflict("revision_conflict",
        "la revisión de trabajo ha cambiado: vuelve a cargar el artículo", properties);
  }

  /** Errores de PostgreSQL esperables en una carrera: conflicto, duplicado o referencia que ya no vale. */
  private RuntimeException translate(DataAccessException e) {
    Throwable cause = e;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    String state = cause instanceof SQLException sql ? sql.getSQLState() : null;
    if ("CM412".equals(state)) {
      return EditorialException.conflict("version_conflict", "el artículo ha cambiado desde que se leyó", Map.of());
    }
    if ("CM409".equals(state)) {
      return revisionConflict(null);
    }
    if ("23505".equals(state)) {
      return slugTakenConflict();
    }
    if ("23503".equals(state) || "23514".equals(state) || "22023".equals(state)) {
      return EditorialException.invalidContent(List.of(new FieldError("content",
          "una referencia o un valor ya no es válido (otro cambio simultáneo): vuelve a validar")));
    }
    return e;
  }

  private void audit(String event, UUID userId, Map<String, String> details) {
    audit.record(event, userId, details);
  }

  private static void compare(List<String> changed, String name, Object before, Object after) {
    if (!Objects.equals(before, after)) {
      changed.add(name);
    }
  }

  private static LocalDate date(String value) {
    return value == null ? null : LocalDate.parse(value);
  }

  private static String text(LocalDate value) {
    return value == null ? null : value.toString();
  }
}
