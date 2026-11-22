package com.cristianatienza.cms.content.validation;

import static com.cristianatienza.cms.content.validation.Rules.bool;
import static com.cristianatienza.cms.content.validation.Rules.fields;
import static com.cristianatienza.cms.content.validation.Rules.integer;
import static com.cristianatienza.cms.content.validation.Rules.list;
import static com.cristianatienza.cms.content.validation.Rules.num;
import static com.cristianatienza.cms.content.validation.Rules.oneOf;
import static com.cristianatienza.cms.content.validation.Rules.optional;
import static com.cristianatienza.cms.content.validation.Rules.shape;
import static com.cristianatienza.cms.content.validation.Rules.slug;
import static com.cristianatienza.cms.content.validation.Rules.text;
import static com.cristianatienza.cms.content.validation.Rules.url;

import com.cristianatienza.cms.content.validation.Rules.Check;
import com.cristianatienza.cms.content.validation.Rules.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reglas de forma de las colecciones del catálogo que gestiona el CMS, regla a regla como en
 * {@code src/app/content/records/validate.ts} (fases 6.4.6 y 6.4.7): etiquetas, autores, lugares,
 * itinerarios y proyectos. Las mismas primitivas y mensajes que el artículo ({@link Rules}, con la
 * semántica de JS de {@link Js}). CatalogRulesTests compara los campos con ContentContract, y el
 * corpus compartido ({@code contract-cases/catalogo.json}) los resultados con TypeScript.
 */
public final class CatalogRules {

  static final List<String> TRAVEL_MODES = List.of("coche", "moto", "tren", "barco", "a-pie");
  static final List<String> PROJECT_STATUSES = List.of("activo", "pausado", "archivado");
  private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

  /** {@code tag = shape({ id: slug, slug, label: text() })}. */
  static final Map<String, Rule> TAG_FIELDS = fields("id", slug(), "slug", slug(), "label", text());
  static final Rule TAG = shape(TAG_FIELDS);

  /** {@code author}. */
  static final Map<String, Rule> AUTHOR_FIELDS = fields(
      "id", slug(),
      "name", text(),
      "role", text(),
      "bio", optional(text()),
      "avatar", optional(slug()));

  /** {@code geoPoint = shape({ lat: num({ min: -90, max: 90 }), lng: num({ min: -180, max: 180 }) })}. */
  private static final Rule GEO_POINT = shape(fields(
      "lat", num(-90, 90, false),
      "lng", num(-180, 180, false)));

  /** {@code destination}. */
  static final Map<String, Rule> DESTINATION_FIELDS = fields(
      "id", slug(),
      "slug", slug(),
      "name", text(),
      "country", text(COUNTRY, "código de país"),
      "region", optional(text()),
      "location", GEO_POINT,
      "elevation", optional(num(-500, 9000, true)),
      "summary", text(),
      "cover", optional(slug()),
      "demo", bool());

  /** {@code trip}, con sus paradas. */
  static final Map<String, Rule> TRIP_FIELDS = fields(
      "id", slug(),
      "slug", slug(),
      "title", text(),
      "summary", text(),
      "stops", list(shape(fields(
          "order", integer(1),
          "destination", slug(),
          "note", optional(text()),
          "distanceKm", optional(num(0, Double.POSITIVE_INFINITY, false)),
          "mode", optional(oneOf(TRAVEL_MODES)))), 2),
      "cover", optional(slug()),
      "demo", bool());

  /** {@code project}. */
  static final Map<String, Rule> PROJECT_FIELDS = fields(
      "id", slug(),
      "slug", slug(),
      "name", text(),
      "summary", text(),
      "status", oneOf(PROJECT_STATUSES),
      "year", num(1990, 2100, true),
      "stack", list(slug()),
      "repositoryUrl", optional(url()),
      "cover", optional(slug()),
      "demo", bool());

  /** Colección → campos, en el orden de COLLECTION_NAMES. */
  static final Map<String, Map<String, Rule>> FIELDS = Map.of(
      "tags", TAG_FIELDS, "authors", AUTHOR_FIELDS, "destinations", DESTINATION_FIELDS, "trips", TRIP_FIELDS,
      "projects", PROJECT_FIELDS);

  private CatalogRules() {}

  /** Incidencias de forma de una etiqueta, con las rutas a partir de {@code path}. */
  public static List<Issue> tag(JsonNode tag, String path) {
    return check(TAG_FIELDS, tag, path);
  }

  /**
   * Incidencias de forma de un registro de la colección ({@code authors}, {@code destinations},
   * {@code trips}, {@code projects} o {@code tags}).
   */
  public static List<Issue> record(String collection, JsonNode record, String path) {
    Map<String, Rule> fields = FIELDS.get(collection);
    if (fields == null) {
      throw new IllegalArgumentException("colección sin reglas: " + collection);
    }
    return check(fields, record, path);
  }

  private static List<Issue> check(Map<String, Rule> fields, JsonNode value, String path) {
    List<Issue> issues = new ArrayList<>();
    shape(fields).check(new Check(issues, path), value);
    return issues;
  }
}
