package com.cristianatienza.cms.content.editorial;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Respuestas de lectura de la API editorial (fase 6.4.2), el contrato que consumirá el CMS de la 6.6.
 * Los campos del contenido usan los nombres del contrato de la web (D7); un campo opcional ausente
 * es {@code null} en los metadatos y no aparece en {@code content} (como en el contrato).
 */
public final class EditorialViews {

  private EditorialViews() {}

  /** Una página de resultados. */
  public record Page<T>(List<T> items, int page, int size, long total) {}

  /** Una revisión, sin contenido. {@code restoredFrom}: número de la revisión recuperada. */
  public record RevisionRef(long id, int number, String origin, Integer restoredFrom, Instant createdAt) {}

  /**
   * Un artículo: sus metadatos (fila {@code article}), lo principal de su revisión de trabajo y el
   * estado derivado ({@code borrador}, {@code publicado}, {@code con-cambios}). {@code version} es el
   * {@code lock_version} que exigirán las escrituras (6.4.3).
   */
  public record ArticleSummary(String id, String slug, String category, String state, String title, String format,
      String author, String cover, List<String> tags, Integer order, boolean featured, boolean demo,
      String publishedAt, String updatedAt, int version, RevisionRef headRevision, RevisionRef publishedRevision) {}

  /**
   * Un artículo con el contenido de su revisión de trabajo (el contrato de la revisión, con sus bloques)
   * y su validación contra el catálogo actual (6.4.3): {@code errors} (lo que hoy impediría guardarlo:
   * por ejemplo, una imagen retirada después) y {@code warnings} (enlaces a artículos sin publicar).
   */
  public record ArticleDetail(ArticleSummary article, JsonNode content, Validation validation) {}

  /** Errores y avisos de un contenido, con las rutas del cuerpo de la petición ({@code content.blocks[2].html}). */
  public record Validation(List<EditorialException.FieldError> errors, List<EditorialException.FieldError> warnings) {}

  /** Resultado de validar sin guardar: errores, avisos y los minutos de lectura que calcula el servidor. */
  public record ValidationResult(List<EditorialException.FieldError> errors,
      List<EditorialException.FieldError> warnings, Integer readingMinutes) {}

  /** Una entrada del historial: si es la de trabajo y si es la publicada. */
  public record RevisionSummary(long id, int number, String origin, Integer restoredFrom, Instant createdAt,
      boolean head, boolean published) {}

  /** Una revisión concreta, de solo lectura, con su contenido. */
  public record RevisionDetail(RevisionSummary revision, JsonNode content) {}

  // --- Catálogos de solo lectura (selectores del CMS) ------------------------------------------

  public record CategoryItem(String id, String slug, String name, String description) {}

  /**
   * Una etiqueta. {@code version} (6.4.6) es un token OPACO de concurrencia (xmin de PostgreSQL): se
   * devuelve tal cual en PUT y DELETE; no es un número ni un contador. {@code inUse}: la usa algún
   * artículo (en cualquier revisión) o proyecto, así que no se puede borrar.
   */
  public record TagItem(String id, String slug, String label, String version, boolean inUse) {}

  public record AuthorItem(String id, String name, String role, String bio, String avatar) {}

  public record DestinationItem(String id, String slug, String name, String country, String region, String cover,
      boolean demo) {}

  public record TripItem(String id, String slug, String title, List<String> stops, String cover, boolean demo) {}

  public record ProjectItem(String id, String slug, String name, String status, int year, String cover,
      boolean demo) {}

  /** Lo que un selector necesita de una imagen; nunca los datos privados del original. */
  public record MediaItem(String id, String path, int width, int height, String alt, boolean retired, boolean demo) {}

  public record GalleryItem(String id, String slug, String title, String layout, int items, boolean demo) {}
}
