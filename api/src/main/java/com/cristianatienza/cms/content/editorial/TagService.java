package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.editorial.EditorialViews.TagItem;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Etiquetas del catálogo (fase 6.4.6): crear, editar y borrar, con el login de la API (cms_app:
 * INSERT, UPDATE y DELETE en {@code tag} desde V3; ninguna migración nueva).
 *
 * <ul>
 *   <li><strong>Identidad:</strong> el id es el slug inicial y no cambia (los artículos referencian la
 *       etiqueta por id). El slug y el texto sí se editan: ninguna URL pública usa el slug.
 *   <li><strong>Concurrencia (D8):</strong> la versión es {@code xmin} de PostgreSQL usado SOLO como
 *       token opaco de comparación ({@code xmin::text}): se lee, se compara como texto y se devuelve
 *       el nuevo con RETURNING. Nunca como número ni con aritmética. Fila bloqueada (FOR UPDATE) y la
 *       comparación repetida en el propio UPDATE o DELETE.
 *   <li><strong>En uso:</strong> una etiqueta que usa alguna revisión de un artículo (de cualquier
 *       momento del historial: las revisiones son inmutables) o algún proyecto no se puede borrar
 *       (409 {@code in_use}); la clave foránea RESTRICT es la última barrera.
 *   <li><strong>Altas y cambios de slug:</strong> ordenados por un bloqueo asesor de transacción (clase
 *       0x434D53, objeto 66); la restricción UNIQUE del slug es la última barrera.
 * </ul>
 *
 * Cada operación y su evento de auditoría van en la misma transacción.
 */
@Service
public class TagService {

  static final String TAG_CREATED = "content.tag_created";
  static final String TAG_UPDATED = "content.tag_updated";
  static final String TAG_DELETED = "content.tag_deleted";
  static final int LOCK_OBJECT = 66;

  /** Lo que se escribe de una etiqueta (el id lo da el slug al crearla). */
  public record TagInput(String slug, String label) {}

  private record TagRow(String id, String slug, String label, String version) {}

  private final JdbcClient jdbc;
  private final EditorialAudit audit;

  public TagService(JdbcClient jdbc, EditorialAudit audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  public String create(TagInput input, UUID userId) {
    lockTags();
    if (slugTaken(input.slug(), null)) {
      throw slugTaken();
    }
    int position = jdbc.sql("SELECT coalesce(max(catalog_position), 0) + 1 FROM tag").query(Integer.class).single();
    try {
      jdbc.sql("INSERT INTO tag (id, slug, label, catalog_position) VALUES (:id, :id, :label, :position)")
          .param("id", input.slug()).param("label", input.label()).param("position", position).update();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    audit.record(TAG_CREATED, userId, Map.of("tag", input.slug()));
    return input.slug();
  }

  /** Reemplaza slug y texto si {@code version} es la actual. False si no había nada que cambiar. */
  @Transactional
  public boolean update(String id, String version, TagInput input, UUID userId) {
    lockTags();
    TagRow row = lockTag(id).orElseThrow(EditorialException::notFound);
    if (!row.version().equals(version)) {
      throw versionConflict(row.version());
    }
    if (!input.slug().equals(row.slug()) && slugTaken(input.slug(), id)) {
      throw slugTaken();
    }
    List<String> changed = new ArrayList<>();
    if (!input.slug().equals(row.slug())) {
      changed.add("slug");
    }
    if (!input.label().equals(row.label())) {
      changed.add("label");
    }
    if (changed.isEmpty()) {
      return false;
    }
    int updated;
    try {
      updated = jdbc.sql("UPDATE tag SET slug = :slug, label = :label WHERE id = :id AND xmin::text = :version")
          .param("slug", input.slug()).param("label", input.label()).param("id", id).param("version", version)
          .update();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    if (updated != 1) {
      throw versionConflict(null);
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("tag", id);
    details.put("fields", String.join(",", changed));
    audit.record(TAG_UPDATED, userId, details);
    return true;
  }

  /** Borra la etiqueta si {@code version} es la actual y nada la usa. */
  @Transactional
  public void delete(String id, String version, UUID userId) {
    TagRow row = lockTag(id).orElseThrow(EditorialException::notFound);
    if (!row.version().equals(version)) {
      throw versionConflict(row.version());
    }
    Map<String, Object> usage = usage(id);
    if ((long) usage.get("articles") > 0 || (long) usage.get("projects") > 0) {
      throw inUse(usage);
    }
    int deleted;
    try {
      deleted = jdbc.sql("DELETE FROM tag WHERE id = :id AND xmin::text = :version")
          .param("id", id).param("version", version).update();
    } catch (DataAccessException e) {
      throw translate(e);
    }
    if (deleted != 1) {
      throw versionConflict(null);
    }
    audit.record(TAG_DELETED, userId, Map.of("tag", id));
  }

  /** La etiqueta tal como la devuelve el catálogo (con su versión y si está en uso). */
  @Transactional(readOnly = true)
  public Optional<TagItem> find(String id) {
    return jdbc.sql("SELECT id, slug, label, xmin::text AS version FROM tag WHERE id = :id").param("id", id)
        .query((rs, n) -> new TagItem(rs.getString("id"), rs.getString("slug"), rs.getString("label"),
            rs.getString("version"), false))
        .optional()
        .map(tag -> {
          Map<String, Object> usage = usage(id);
          boolean inUse = (long) usage.get("articles") > 0 || (long) usage.get("projects") > 0;
          return new TagItem(tag.id(), tag.slug(), tag.label(), tag.version(), inUse);
        });
  }

  // --- Apoyo ------------------------------------------------------------------------------------

  private void lockTags() {
    jdbc.sql("SELECT pg_advisory_xact_lock(:class, :object)").param("class", ArticleWriteService.LOCK_CLASS)
        .param("object", LOCK_OBJECT).query((rs, n) -> 1).single();
  }

  private Optional<TagRow> lockTag(String id) {
    return jdbc.sql("SELECT id, slug, label, xmin::text AS version FROM tag WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query((rs, n) -> new TagRow(rs.getString("id"), rs.getString("slug"), rs.getString("label"),
            rs.getString("version")))
        .optional();
  }

  /** Ocupado si es el slug o el id de otra etiqueta (los ids no se reutilizan). */
  private boolean slugTaken(String slug, String exceptId) {
    return jdbc.sql("SELECT count(*) FROM tag WHERE (slug = :slug OR id = :slug) AND id IS DISTINCT FROM :except")
        .param("slug", slug).param("except", exceptId).query(Long.class).single() > 0;
  }

  /** Artículos (en cualquier revisión) y proyectos que usan la etiqueta. */
  private Map<String, Object> usage(String id) {
    long articles = jdbc.sql("SELECT count(DISTINCT r.article_id) FROM article_revision_tag t"
            + " JOIN article_revision r ON r.id = t.revision_id WHERE t.tag_id = :id")
        .param("id", id).query(Long.class).single();
    long projects = jdbc.sql("SELECT count(*) FROM project_stack_item WHERE tag_id = :id")
        .param("id", id).query(Long.class).single();
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("articles", articles);
    usage.put("projects", projects);
    return usage;
  }

  private static EditorialException slugTaken() {
    return EditorialException.conflict("slug_taken", "ya hay una etiqueta con ese slug", Map.of());
  }

  private static EditorialException versionConflict(String current) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("currentVersion", current);
    return EditorialException.conflict("version_conflict", "la etiqueta ha cambiado desde que se leyó", properties);
  }

  private static EditorialException inUse(Map<String, Object> usage) {
    return EditorialException.conflict("in_use", "la etiqueta está en uso: no se puede borrar", Map.of("usage", usage));
  }

  /** Carreras con otro cambio: slug duplicado o una referencia nueva que impide borrar. */
  private RuntimeException translate(DataAccessException e) {
    Throwable cause = e;
    while (cause != null && !(cause instanceof SQLException)) {
      cause = cause.getCause();
    }
    String state = cause instanceof SQLException sql ? sql.getSQLState() : null;
    if ("23505".equals(state)) {
      return slugTaken();
    }
    if ("23001".equals(state) || "23503".equals(state)) {
      return EditorialException.conflict("in_use", "la etiqueta está en uso: no se puede borrar", Map.of());
    }
    if ("23514".equals(state)) {
      return EditorialException.invalidContent(List.of(new FieldError("", "valor no válido para la base de datos")));
    }
    return e;
  }
}
