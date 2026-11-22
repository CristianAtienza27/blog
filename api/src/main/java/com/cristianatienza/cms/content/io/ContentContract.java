package com.cristianatienza.cms.content.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * La <em>forma</em> del contrato de contenido (content/records/validate.ts) en Java: para cada
 * colección, objeto anidado y tipo de bloque, sus claves admitidas, cuáles son obligatorias, el
 * tipo de cada valor, si admite {@code null} y el orden canónico de las claves (el del
 * validador).
 *
 * <p>Las reglas semánticas (patrones, rangos, enumerados, reglas cruzadas) no están aquí: las
 * aplica PostgreSQL al escribir y la puerta TypeScript al publicar. ContentContractTests compara
 * esta descripción con validate.ts para que no se desfase.
 *
 * <p>{@code null}: hoy el contrato no lo admite en ningún campo (validate.ts solo trata como
 * ausente {@code undefined} y ninguna regla acepta {@code null}). La política es por campo: si
 * un campo lo admitiera, se conservaría; nunca se convierte en ausente.
 */
public final class ContentContract {

  /** Tipo de valor JSON. */
  public enum Kind {
    STRING,
    NUMBER,
    BOOLEAN,
    LIST,
    OBJECT,
    /** Bloque del cuerpo: objeto cuya forma depende de su {@code type}. */
    BLOCK
  }

  /** Tipo de un campo: para listas, el de sus elementos; para objetos, su forma. */
  public record Type(Kind kind, Type element, Shape shape) {
    static final Type STRING = new Type(Kind.STRING, null, null);
    static final Type NUMBER = new Type(Kind.NUMBER, null, null);
    static final Type BOOLEAN = new Type(Kind.BOOLEAN, null, null);
    static final Type BLOCK = new Type(Kind.BLOCK, null, null);

    static Type list(Type element) {
      return new Type(Kind.LIST, element, null);
    }

    static Type object(Shape shape) {
      return new Type(Kind.OBJECT, null, shape);
    }
  }

  /** Campo de un objeto: nombre, tipo, si es obligatorio y si admite {@code null}. */
  public record Field(String name, Type type, boolean required, boolean nullable) {}

  /** Objeto con campos conocidos, en el orden canónico. */
  public record Shape(String name, List<Field> fields) {
    public Optional<Field> field(String key) {
      return fields.stream().filter(f -> f.name().equals(key)).findFirst();
    }
  }

  private static Field req(String name, Type type) {
    return new Field(name, type, true, false);
  }

  private static Field opt(String name, Type type) {
    return new Field(name, type, false, false);
  }

  private static Shape shape(String name, Field... fields) {
    return new Shape(name, List.of(fields));
  }

  private static final Type S = Type.STRING;
  private static final Type N = Type.NUMBER;
  private static final Type B = Type.BOOLEAN;

  // --- Objetos compartidos ----------------------------------------------------------------------

  static final Shape GEO_POINT = shape("geoPoint", req("lat", N), req("lng", N));
  static final Shape FOCAL_POINT = shape("focalPoint", req("x", N), req("y", N));

  // --- Colecciones (en el orden de COLLECTION_NAMES) ---------------------------------------------

  static final Shape CATEGORY = shape("category",
      req("id", S), req("slug", S), req("name", S), req("description", S));

  static final Shape TAG = shape("tag", req("id", S), req("slug", S), req("label", S));

  static final Shape AUTHOR = shape("author",
      req("id", S), req("name", S), req("role", S), opt("bio", S), opt("avatar", S));

  static final Shape MEDIA = shape("media",
      req("id", S),
      req("path", S),
      req("width", N),
      req("height", N),
      req("widths", Type.list(N)),
      req("alt", S),
      opt("title", S),
      opt("caption", S),
      opt("description", S),
      opt("takenAt", S),
      opt("location", Type.object(shape("location",
          req("name", S), opt("region", S), opt("country", S), opt("point", Type.object(GEO_POINT))))),
      opt("camera", Type.object(shape("camera",
          opt("make", S), opt("model", S), opt("lens", S), opt("focalLength", N), opt("aperture", N),
          opt("exposure", S), opt("iso", N)))),
      req("focal", Type.object(FOCAL_POINT)),
      req("placeholder", S),
      req("credit", Type.object(shape("credit",
          req("author", S), req("source", S), opt("sourceUrl", S), req("license", S), opt("licenseUrl", S)))),
      req("original", Type.object(shape("original",
          req("file", S), req("format", S), req("width", N), req("height", N), req("bytes", N), req("sha256", S),
          opt("url", S),
          opt("raw", Type.object(shape("raw", req("file", S), req("bytes", N), req("sha256", S))))))),
      req("demo", B));

  static final Shape GALLERY = shape("gallery",
      req("id", S), req("slug", S), req("title", S), opt("description", S), req("layout", S),
      req("items", Type.list(Type.object(shape("galleryItem", req("media", S), opt("caption", S))))),
      req("demo", B));

  static final Shape DESTINATION = shape("destination",
      req("id", S), req("slug", S), req("name", S), req("country", S), opt("region", S),
      req("location", Type.object(GEO_POINT)), opt("elevation", N), req("summary", S), opt("cover", S),
      req("demo", B));

  static final Shape TRIP = shape("trip",
      req("id", S), req("slug", S), req("title", S), req("summary", S),
      req("stops", Type.list(Type.object(shape("tripStop",
          req("order", N), req("destination", S), opt("note", S), opt("distanceKm", N), opt("mode", S))))),
      opt("cover", S), req("demo", B));

  static final Shape PROJECT = shape("project",
      req("id", S), req("slug", S), req("name", S), req("summary", S), req("status", S), req("year", N),
      req("stack", Type.list(S)), opt("repositoryUrl", S), opt("cover", S), req("demo", B));

  static final Shape ARTICLE = shape("article",
      req("id", S), req("slug", S), req("title", S), req("dek", S), req("excerpt", S), req("category", S),
      req("tags", Type.list(S)), req("format", S), opt("cover", S), req("author", S), req("order", N),
      req("publishedAt", S), opt("updatedAt", S), req("readingMinutes", N), req("featured", B), req("demo", B),
      opt("trip", S), opt("destinations", Type.list(S)), opt("project", S),
      opt("seo", Type.object(shape("seo", opt("description", S)))));

  /** Colecciones en el orden de COLLECTION_NAMES (records.ts). */
  public static final Map<String, Shape> COLLECTIONS = orderedMap(
      "categories", CATEGORY, "tags", TAG, "authors", AUTHOR, "media", MEDIA, "galleries", GALLERY,
      "destinations", DESTINATION, "trips", TRIP, "projects", PROJECT, "articles", ARTICLE);

  // --- Cuerpo y bloques ----------------------------------------------------------------------------

  public static final Shape BODY = shape("articleBody", req("slug", S), req("blocks", Type.list(Type.BLOCK)));

  private static final Shape FIGURE_ITEM = shape("figureItem",
      req("media", S), opt("ratio", S), opt("focal", Type.object(FOCAL_POINT)), opt("caption", S));

  /** Bloques por {@code type}, en el orden de validate.ts. */
  public static final Map<String, Shape> BLOCKS = orderedMap(
      "text", shape("text", req("type", S), req("html", S)),
      "image", shape("image", req("type", S), req("media", S), req("layout", S), opt("caption", S),
          opt("ratio", S), opt("align", S), opt("focal", Type.object(FOCAL_POINT))),
      "figures", shape("figures", req("type", S), req("layout", S), req("items", Type.list(Type.object(FIGURE_ITEM)))),
      "gallery", shape("gallery", req("type", S), req("gallery", S)),
      "quote", shape("quote", req("type", S), req("text", S), opt("cite", S), req("variant", S)),
      "map", shape("map", req("type", S), opt("trip", S), opt("destinations", Type.list(S)), req("route", B),
          opt("caption", S)),
      "code", shape("code", req("type", S), req("language", S), req("code", S), opt("filename", S),
          opt("caption", S)),
      "specs", shape("specs", req("type", S), opt("title", S),
          req("items", Type.list(Type.object(shape("specItem", req("label", S), req("value", S), opt("unit", S))))),
          opt("note", S)),
      "chapter", shape("chapter", req("type", S), req("id", S), opt("number", N), req("title", S),
          opt("kicker", S), opt("destination", S)),
      "divider", shape("divider", req("type", S), req("variant", S)));

  private ContentContract() {}

  @SuppressWarnings("unchecked")
  private static <V> Map<String, V> orderedMap(Object... pairs) {
    Map<String, V> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], (V) pairs[i + 1]);
    }
    return java.util.Collections.unmodifiableMap(map);
  }

  // --- Comprobación de la forma ----------------------------------------------------------------

  /** Incidencias de forma de un valor (vacía si cumple el contrato), con la ruta de cada una. */
  public static List<String> check(JsonNode value, Shape shape, String path) {
    List<String> issues = new ArrayList<>();
    checkObject(value, shape, path, issues);
    return issues;
  }

  private static void checkObject(JsonNode value, Shape shape, String path, List<String> issues) {
    if (!value.isObject()) {
      issues.add(path + ": se esperaba un objeto");
      return;
    }
    for (Field field : shape.fields()) {
      if (!value.has(field.name())) {
        if (field.required()) {
          issues.add(path + "." + field.name() + ": campo obligatorio");
        }
        continue;
      }
      checkValue(value.get(field.name()), field.type(), field.nullable(), path + "." + field.name(), issues);
    }
    for (String key : value.propertyNames()) {
      if (shape.field(key).isEmpty()) {
        issues.add(path + "." + key + ": campo desconocido");
      }
    }
  }

  private static void checkValue(JsonNode value, Type type, boolean nullable, String path, List<String> issues) {
    if (value.isNull()) {
      if (!nullable) {
        issues.add(path + ": null no admitido por el contrato");
      }
      return;
    }
    switch (type.kind()) {
      case STRING -> {
        if (!value.isString()) issues.add(path + ": se esperaba un texto");
      }
      case NUMBER -> {
        if (!value.isNumber()) issues.add(path + ": se esperaba un número");
      }
      case BOOLEAN -> {
        if (!value.isBoolean()) issues.add(path + ": se esperaba true o false");
      }
      case LIST -> {
        if (!value.isArray()) {
          issues.add(path + ": se esperaba una lista");
          return;
        }
        for (int i = 0; i < value.size(); i++) {
          checkValue(value.get(i), type.element(), false, path + "[" + i + "]", issues);
        }
      }
      case OBJECT -> checkObject(value, type.shape(), path, issues);
      case BLOCK -> {
        Shape block = blockShape(value);
        if (block == null) {
          issues.add(path + ".type: tipo de bloque desconocido");
          return;
        }
        checkObject(value, block, path, issues);
      }
    }
  }

  /** Forma de un bloque según su {@code type}, o null si no es un bloque conocido. */
  static Shape blockShape(JsonNode block) {
    if (!block.isObject() || !block.path("type").isString()) {
      return null;
    }
    return BLOCKS.get(block.get("type").stringValue());
  }

  // --- Orden canónico ----------------------------------------------------------------------------

  /** Copia del valor con las claves de cada objeto en el orden canónico del contrato. */
  public static JsonNode canonical(JsonNode value, Shape shape) {
    return canonicalValue(value, Type.object(shape));
  }

  private static JsonNode canonicalValue(JsonNode value, Type type) {
    if (value == null || value.isNull()) {
      return value;
    }
    return switch (type.kind()) {
      case LIST -> {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode element : value.values()) {
          out.add(canonicalValue(element, type.element()));
        }
        yield out;
      }
      case OBJECT -> canonicalObject(value, type.shape());
      case BLOCK -> {
        Shape block = blockShape(value);
        if (block == null) {
          throw new IllegalArgumentException("bloque desconocido: " + value);
        }
        yield canonicalObject(value, block);
      }
      default -> value;
    };
  }

  private static ObjectNode canonicalObject(JsonNode value, Shape shape) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (Field field : shape.fields()) {
      if (value.has(field.name())) {
        out.set(field.name(), canonicalValue(value.get(field.name()), field.type()));
      }
    }
    for (String key : value.propertyNames()) {
      if (shape.field(key).isEmpty()) {
        throw new IllegalArgumentException("campo desconocido «" + key + "» en " + shape.name());
      }
    }
    return out;
  }
}
