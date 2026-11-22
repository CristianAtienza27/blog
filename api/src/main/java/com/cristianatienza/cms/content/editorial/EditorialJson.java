package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lectura estricta del cuerpo de las escrituras editoriales (fase 6.4.3): solo
 * {@code application/json}, como mucho 1 MiB, sin claves duplicadas ni basura final, con los números
 * exactos (el contenido se guarda tal cual en jsonb). Y las comprobaciones de los campos de primer
 * nivel, que acumulan TODAS las incidencias.
 */
final class EditorialJson {

  /** Tamaño máximo de un cuerpo (el artículo real más grande ocupa unos 30 KB). */
  static final int MAX_BODY = 1024 * 1024;
  static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  static final int MAX_SLUG = 100;
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  private EditorialJson() {}

  /** El cuerpo como objeto JSON, o el error correspondiente (415, 413, 400 o 422). */
  static JsonNode object(HttpServletRequest request) {
    String type = request.getContentType() == null ? "" : request.getContentType().split(";")[0].strip();
    if (!type.equalsIgnoreCase("application/json")) {
      throw new EditorialException(415, "unsupported_media_type", "se esperaba JSON", List.of());
    }
    if (request.getContentLengthLong() > MAX_BODY) {
      throw tooLarge();
    }
    byte[] bytes;
    try (InputStream in = request.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int total = 0;
      for (int n; (n = in.read(buffer)) > 0; ) {
        total += n;
        if (total > MAX_BODY) {
          throw tooLarge();
        }
        out.write(buffer, 0, n);
      }
      bytes = out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JsonNode body;
    try {
      body = JSON.readTree(bytes);
    } catch (JacksonException e) {
      throw new EditorialException(400, "bad_request", "JSON no válido", List.of());
    }
    if (body == null || !body.isObject()) {
      throw EditorialException.invalidContent(List.of(new FieldError("", "se esperaba un objeto JSON")));
    }
    return body;
  }

  private static EditorialException tooLarge() {
    return new EditorialException(413, "payload_too_large", "solicitud demasiado grande", List.of());
  }

  /** Campos de primer nivel desconocidos. */
  static void onlyFields(JsonNode body, Set<String> allowed, List<FieldError> errors) {
    for (String key : body.propertyNames()) {
      if (!allowed.contains(key)) {
        errors.add(new FieldError(key, "campo desconocido"));
      }
    }
  }

  static String slug(JsonNode body, String field, List<FieldError> errors) {
    JsonNode value = body.get(field);
    if (value == null || !value.isString()) {
      errors.add(new FieldError(field, value == null ? "campo obligatorio" : "se esperaba un texto"));
      return null;
    }
    String slug = value.stringValue();
    if (!SLUG.matcher(slug).matches() || slug.length() > MAX_SLUG) {
      errors.add(new FieldError(field, "slug no válido: minúsculas, números y guiones simples, como mucho "
          + MAX_SLUG + " caracteres"));
      return null;
    }
    return slug;
  }

  static String section(JsonNode body, String field, List<FieldError> errors) {
    JsonNode value = body.get(field);
    if (value == null || !value.isString() || !ArticleQuery.SECTIONS.contains(value.stringValue())) {
      errors.add(new FieldError(field, value == null ? "campo obligatorio"
          : "valor no permitido (admitidos: " + String.join(", ", ArticleQuery.SECTIONS) + ")"));
      return null;
    }
    return value.stringValue();
  }

  static Boolean bool(JsonNode body, String field, List<FieldError> errors) {
    JsonNode value = body.get(field);
    if (value == null || !value.isBoolean()) {
      errors.add(new FieldError(field, value == null ? "campo obligatorio" : "se esperaba true o false"));
      return null;
    }
    return value.booleanValue();
  }

  /** Entero positivo (ids de revisión) o no negativo (versiones). */
  static Long integer(JsonNode body, String field, long min, List<FieldError> errors) {
    JsonNode value = body.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min) {
      errors.add(new FieldError(field, value == null ? "campo obligatorio" : "se esperaba un entero mayor o igual que " + min));
      return null;
    }
    return value.longValue();
  }

  /** Fecha AAAA-MM-DD que existe, o null (la clave es obligatoria; su valor puede ser null). */
  static String date(JsonNode body, String field, List<FieldError> errors) {
    JsonNode value = body.get(field);
    if (value == null) {
      errors.add(new FieldError(field, "campo obligatorio (null si no tiene)"));
      return null;
    }
    if (value.isNull()) {
      return null;
    }
    if (!value.isString() || !ISO_DATE.matcher(value.stringValue()).matches()) {
      errors.add(new FieldError(field, "se esperaba una fecha AAAA-MM-DD o null"));
      return null;
    }
    try {
      LocalDate.parse(value.stringValue());
    } catch (DateTimeParseException e) {
      errors.add(new FieldError(field, "fecha inexistente"));
      return null;
    }
    return value.stringValue();
  }
}
