package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filtros, orden y página del listado de artículos (fase 6.4.2), leídos de los parámetros de la
 * consulta con validación estricta: un parámetro desconocido, repetido o mal formado es un 400 con
 * todas las incidencias (así una errata en el CMS no se convierte en un filtro ignorado).
 *
 * <pre>
 *   estado     borrador | publicado | con-cambios
 *   seccion    viajes | fotografia | motor | experiencias | tecnologia
 *   etiqueta   id de una etiqueta (slug)
 *   q          texto en el título (revisión de trabajo) o el slug, 1–100 caracteres
 *   demo       true | false
 *   destacado  true | false
 *   orden      actualizado (por defecto) | publicado | titulo | seccion
 *   page       0, 1, 2…  (por defecto 0)
 *   size       1–100     (por defecto 20)
 * </pre>
 */
public record ArticleQuery(String state, String section, String tag, String text, Boolean demo, Boolean featured,
    String order, int page, int size) {

  static final List<String> STATES = List.of("borrador", "publicado", "con-cambios");
  static final List<String> SECTIONS = List.of("viajes", "fotografia", "motor", "experiencias", "tecnologia");
  static final List<String> ORDERS = List.of("actualizado", "publicado", "titulo", "seccion");
  static final int DEFAULT_SIZE = 20;
  static final int MAX_SIZE = 100;
  static final int MAX_TEXT = 100;
  /** Página máxima: evita desplazamientos absurdos (OFFSET) sin limitar un uso real. */
  static final int MAX_PAGE = 10_000;

  private static final Set<String> PARAMETERS =
      Set.of("estado", "seccion", "etiqueta", "q", "demo", "destacado", "orden", "page", "size");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern NUMBER = Pattern.compile("0|[1-9][0-9]{0,8}");

  /** Lee y valida los parámetros; lanza un 400 con todas las incidencias. */
  static ArticleQuery parse(Map<String, String[]> parameters) {
    List<FieldError> errors = new ArrayList<>();
    for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
      if (!PARAMETERS.contains(entry.getKey())) {
        errors.add(new FieldError(entry.getKey(), "parámetro desconocido"));
      } else if (entry.getValue().length != 1) {
        errors.add(new FieldError(entry.getKey(), "parámetro repetido"));
      }
    }
    String state = oneOf(parameters, "estado", STATES, errors);
    String section = oneOf(parameters, "seccion", SECTIONS, errors);
    String tag = single(parameters, "etiqueta");
    if (tag != null && !SLUG.matcher(tag).matches()) {
      errors.add(new FieldError("etiqueta", "se esperaba el id de una etiqueta"));
    }
    String text = single(parameters, "q");
    if (text != null) {
      text = text.strip();
      if (text.isEmpty() || text.length() > MAX_TEXT) {
        errors.add(new FieldError("q", "entre 1 y " + MAX_TEXT + " caracteres"));
      }
    }
    Boolean demo = bool(parameters, "demo", errors);
    Boolean featured = bool(parameters, "destacado", errors);
    String order = oneOf(parameters, "orden", ORDERS, errors);
    int page = number(parameters, "page", 0, 0, MAX_PAGE, errors);
    int size = number(parameters, "size", DEFAULT_SIZE, 1, MAX_SIZE, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidParameters(errors);
    }
    return new ArticleQuery(state, section, tag, text, demo, featured, order == null ? "actualizado" : order, page,
        size);
  }

  /** Página de un listado sin filtros (el historial de revisiones): solo {@code page} y {@code size}. */
  record PageQuery(int page, int size) {}

  static PageQuery pageOnly(Map<String, String[]> parameters) {
    List<FieldError> errors = new ArrayList<>();
    for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
      if (!entry.getKey().equals("page") && !entry.getKey().equals("size")) {
        errors.add(new FieldError(entry.getKey(), "parámetro desconocido"));
      } else if (entry.getValue().length != 1) {
        errors.add(new FieldError(entry.getKey(), "parámetro repetido"));
      }
    }
    int page = number(parameters, "page", 0, 0, MAX_PAGE, errors);
    int size = number(parameters, "size", DEFAULT_SIZE, 1, MAX_SIZE, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidParameters(errors);
    }
    return new PageQuery(page, size);
  }

  private static String single(Map<String, String[]> parameters, String name) {
    String[] values = parameters.get(name);
    return values == null || values.length != 1 ? null : values[0];
  }

  private static String oneOf(Map<String, String[]> parameters, String name, List<String> allowed,
      List<FieldError> errors) {
    String value = single(parameters, name);
    if (value != null && !allowed.contains(value)) {
      errors.add(new FieldError(name, "valor no permitido (admitidos: " + String.join(", ", allowed) + ")"));
      return null;
    }
    return value;
  }

  private static Boolean bool(Map<String, String[]> parameters, String name, List<FieldError> errors) {
    String value = single(parameters, name);
    if (value == null) {
      return null;
    }
    if (!value.equals("true") && !value.equals("false")) {
      errors.add(new FieldError(name, "se esperaba true o false"));
      return null;
    }
    return Boolean.valueOf(value);
  }

  private static int number(Map<String, String[]> parameters, String name, int fallback, int min, int max,
      List<FieldError> errors) {
    String value = single(parameters, name);
    if (value == null) {
      return fallback;
    }
    if (!NUMBER.matcher(value).matches() || Integer.parseInt(value) < min || Integer.parseInt(value) > max) {
      errors.add(new FieldError(name, "se esperaba un entero entre " + min + " y " + max));
      return fallback;
    }
    return Integer.parseInt(value);
  }
}
