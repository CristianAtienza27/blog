package com.cristianatienza.cms.content.editorial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Error de la API editorial (fase 6.4): un ProblemDetail (RFC 9457) con su {@code code} estable y,
 * si procede, las incidencias por campo en {@code errors} ({@code [{path, message}]}) y datos para
 * resolver un conflicto (por ejemplo, {@code currentRevisionId}). Nunca lleva datos internos.
 */
public class EditorialException extends RuntimeException {

  /** Una incidencia de un campo o parámetro concreto. */
  public record FieldError(String path, String message) {}

  private final int status;
  private final String code;
  private final transient List<FieldError> errors;
  private final transient Map<String, Object> properties;

  public EditorialException(int status, String code, String detail, List<FieldError> errors) {
    this(status, code, detail, errors, Map.of());
  }

  public EditorialException(int status, String code, String detail, List<FieldError> errors,
      Map<String, Object> properties) {
    super(detail);
    this.status = status;
    this.code = code;
    this.errors = List.copyOf(errors);
    this.properties = new LinkedHashMap<>(properties);
  }

  /** 400 {@code invalid_parameters}: parámetros de consulta no válidos, con cada incidencia. */
  public static EditorialException invalidParameters(List<FieldError> errors) {
    return new EditorialException(400, "invalid_parameters", "parámetros no válidos", errors);
  }

  /** 404 {@code not_found}: el recurso no existe (o el identificador no puede existir). */
  public static EditorialException notFound() {
    return new EditorialException(404, "not_found", "no encontrado", List.of());
  }

  /** 422 {@code invalid_content}: el cuerpo no cumple el contrato, con todas las incidencias. */
  public static EditorialException invalidContent(List<FieldError> errors) {
    return new EditorialException(422, "invalid_content", "contenido no válido", errors);
  }

  /** 409 con un {@code code} propio y, si procede, datos para resolverlo. */
  public static EditorialException conflict(String code, String detail, Map<String, Object> properties) {
    return new EditorialException(409, code, detail, List.of(), properties);
  }

  /** 403 {@code forbidden}: el rol no permite esta operación sobre este recurso. */
  public static EditorialException forbidden(String detail) {
    return new EditorialException(403, "forbidden", detail, List.of());
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<FieldError> errors() {
    return errors;
  }

  public Map<String, Object> properties() {
    return properties;
  }
}
