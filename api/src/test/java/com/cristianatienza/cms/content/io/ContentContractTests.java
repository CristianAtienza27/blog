package com.cristianatienza.cms.content.io;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.ContentContract.Field;
import com.cristianatienza.cms.content.io.ContentContract.Kind;
import com.cristianatienza.cms.content.io.ContentContract.Shape;
import com.cristianatienza.cms.content.io.ContentContract.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * ContentContract frente a la fuente de verdad, content/records/validate.ts: mismas claves, en el
 * mismo orden, mismos obligatorios y opcionales, mismo tipo y mismos objetos anidados, para
 * cada colección y cada tipo de bloque. Y la política de null: hoy ningún campo lo admite.
 */
class ContentContractTests {

  static final Path VALIDATE_TS = Path.of("../src/app/content/records/validate.ts");
  private static String source;
  private static Map<String, String> named;

  @BeforeAll
  static void readValidator() throws Exception {
    source = Files.readString(VALIDATE_TS);
    named = namedShapes(source);
  }

  // --- Comparación con validate.ts ------------------------------------------------------------

  static List<Object[]> shapes() {
    List<Object[]> cases = new ArrayList<>();
    ContentContract.COLLECTIONS.forEach((name, shape) -> cases.add(new Object[] {name, shape}));
    ContentContract.BLOCKS.forEach((type, shape) -> cases.add(new Object[] {"bloque " + type, shape}));
    cases.add(new Object[] {"cuerpo", ContentContract.BODY});
    return cases;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapes")
  void coincideConValidateTs(String name, Shape shape) {
    String literal = tsShapeFor(name);
    assertThat(describe(shape)).as(name).isEqualTo(describeTs(literal));
  }

  @Test
  void cubreTodasLasColeccionesYTodosLosBloques() {
    assertThat(ContentContract.COLLECTIONS.keySet()).containsExactly(
        "categories", "tags", "authors", "media", "galleries", "destinations", "trips", "projects", "articles");
    Matcher union = Pattern.compile("const block = union\\('type', \\{").matcher(source);
    assertThat(union.find()).isTrue();
    assertThat(ContentContract.BLOCKS.keySet())
        .containsExactlyElementsOf(topLevelEntries(objectAt(source, union.end() - 1)).keySet());
  }

  @Test
  void hoyNingunCampoAdmiteNull() throws Exception {
    // validate.ts: el único null es el de isRecord, que lo excluye de los objetos.
    assertThat(source.lines().filter(l -> l.contains("null")).toList())
        .containsExactly("  return typeof value === 'object' && value !== null && !Array.isArray(value);");
    // Ningún tipo del contrato declara «| null».
    for (Path file : List.of(Path.of("../src/app/content/records/records.ts"), Path.of("../src/app/content/models"))) {
      try (var files = Files.walk(file)) {
        for (Path ts : files.filter(p -> p.toString().endsWith(".ts")).toList()) {
          assertThat(Files.readString(ts)).as(ts.toString()).doesNotContain("| null").doesNotContain("null |");
        }
      }
    }
    // Y así lo refleja ContentContract en todos los campos.
    List<Field> all = new ArrayList<>();
    shapes().forEach(c -> collectFields((Shape) c[1], all));
    assertThat(all).isNotEmpty().noneMatch(Field::nullable);
  }

  @Test
  void elContenidoRealCumpleElContrato() {
    var content = ContentSource.read(Path.of("../src/content"));
    assertThat(ContentSource.check(content)).isEmpty();
  }

  @Test
  void rechazaNullAusenciasYCamposDesconocidosConSuRuta() throws Exception {
    JsonNode tag = ContentSource.JSON.readTree("{\"id\": \"a\", \"slug\": null, \"extra\": 1}");
    assertThat(ContentContract.check(tag, ContentContract.TAG, "tags[0]")).containsExactlyInAnyOrder(
        "tags[0].slug: null no admitido por el contrato",
        "tags[0].label: campo obligatorio",
        "tags[0].extra: campo desconocido");
    JsonNode body = ContentSource.JSON.readTree(
        "{\"slug\": \"x\", \"blocks\": [{\"type\": \"image\", \"media\": \"m\", \"layout\": \"wide\", \"focal\": null},"
            + " {\"type\": \"video\"}]}");
    assertThat(ContentContract.check(body, ContentContract.BODY, "articles/x.json")).containsExactly(
        "articles/x.json.blocks[0].focal: null no admitido por el contrato",
        "articles/x.json.blocks[1].type: tipo de bloque desconocido");
  }

  // --- Descripción comparable --------------------------------------------------------------------

  /** «campo:tipo[?]» para cada campo, con los objetos anidados entre llaves. */
  private static List<String> describe(Shape shape) {
    List<String> out = new ArrayList<>();
    for (Field f : shape.fields()) {
      out.add(f.name() + (f.required() ? "" : "?") + ":" + describe(f.type()));
    }
    return out;
  }

  private static String describe(Type type) {
    return switch (type.kind()) {
      case LIST -> "list(" + describe(type.element()) + ")";
      case OBJECT -> "{" + String.join(",", describe(type.shape())) + "}";
      case BLOCK -> "block";
      default -> type.kind().name().toLowerCase();
    };
  }

  private static List<String> describeTs(String objectLiteral) {
    List<String> out = new ArrayList<>();
    topLevelEntries(objectLiteral).forEach((key, expr) -> {
      boolean optional = expr.startsWith("optional(");
      String inner = optional ? unwrap(expr, "optional") : expr;
      out.add(key + (optional ? "?" : "") + ":" + describeTsType(key, inner));
    });
    return out;
  }

  private static String describeTsType(String key, String expr) {
    if (expr.startsWith("list(")) {
      String args = unwrap(expr, "list");
      return "list(" + describeTsType(key, splitTopLevel(args, ',').getFirst().strip()) + ")";
    }
    if (expr.startsWith("shape(")) {
      return "{" + String.join(",", describeTs(unwrap(expr, "shape"))) + "}";
    }
    if (expr.equals("coarsePoint")) {
      return "{" + String.join(",", describeTs(named.get("geoPoint"))) + "}";
    }
    if (named.containsKey(expr)) {
      return "{" + String.join(",", describeTs(named.get(expr))) + "}";
    }
    if (expr.equals("block")) return "block";
    if (expr.startsWith("num(")) return "number";
    if (expr.equals("bool")) return "boolean";
    if (expr.startsWith("text(") || expr.startsWith("oneOf(")
        || List.of("slug", "url", "isoDate", "richText").contains(expr)) {
      return "string";
    }
    throw new AssertionError("expresión no reconocida en validate.ts para «" + key + "»: " + expr);
  }

  private static void collectFields(Shape shape, List<Field> all) {
    for (Field f : shape.fields()) {
      all.add(f);
      Type t = f.type().kind() == Kind.LIST ? f.type().element() : f.type();
      if (t.kind() == Kind.OBJECT) collectFields(t.shape(), all);
    }
  }

  // --- Lectura de validate.ts -------------------------------------------------------------------

  /** El objeto literal de shape({ … }) que describe cada colección, bloque o cuerpo. */
  private static String tsShapeFor(String name) {
    return switch (name) {
      case "categories" -> named.get("category");
      case "tags" -> named.get("tag");
      case "authors" -> named.get("author");
      case "galleries" -> named.get("gallery");
      case "destinations" -> named.get("destination");
      case "trips" -> named.get("trip");
      case "projects" -> named.get("project");
      case "articles" -> named.get("article");
      case "cuerpo" -> named.get("articleBody");
      case "media" -> firstShapeAfter("const media: Rule");
      default -> {
        String type = name.substring("bloque ".length());
        Matcher union = Pattern.compile("const block = union\\('type', \\{").matcher(source);
        union.find();
        String expr = topLevelEntries(objectAt(source, union.end() - 1)).get(type);
        int at = expr.indexOf("shape({");
        yield objectAt(expr, at + "shape(".length());
      }
    };
  }

  private static String firstShapeAfter(String marker) {
    int at = source.indexOf("shape({", source.indexOf(marker));
    return objectAt(source, at + "shape(".length());
  }

  /** const NOMBRE = shape({ … }) → literal del objeto. */
  private static Map<String, String> namedShapes(String src) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher m = Pattern.compile("const (\\w+) = shape\\(\\{").matcher(src);
    while (m.find()) {
      out.put(m.group(1), objectAt(src, m.end() - 1));
    }
    return out;
  }

  /** Texto de «{ … }» que empieza en {@code open}. */
  private static String objectAt(String text, int open) {
    return text.substring(open, closing(text, open) + 1);
  }

  /** Argumentos de «nombre( … )» sin el nombre ni los paréntesis exteriores. */
  private static String unwrap(String expr, String function) {
    int open = function.length();
    int close = closing(expr, open);
    return expr.substring(open + 1, close).strip();
  }

  /** Entradas «clave: expresión» de primer nivel de un objeto literal. */
  private static Map<String, String> topLevelEntries(String objectLiteral) {
    Map<String, String> out = new LinkedHashMap<>();
    String body = objectLiteral.substring(1, objectLiteral.length() - 1);
    for (String entry : splitTopLevel(body, ',')) {
      String e = entry.replaceAll("(?m)^\\s*//.*$", "").strip();
      if (e.isEmpty()) continue;
      int colon = e.indexOf(':');
      if (colon < 0) {
        out.put(e, e); // propiedad abreviada: «slug» equivale a «slug: slug»
      } else {
        out.put(e.substring(0, colon).strip(), e.substring(colon + 1).strip());
      }
    }
    return out;
  }

  private static List<String> splitTopLevel(String text, char separator) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    char quote = 0;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
      } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
        i = text.indexOf('\n', i) < 0 ? text.length() : text.indexOf('\n', i);
      } else if (c == '(' || c == '{' || c == '[') {
        depth++;
      } else if (c == ')' || c == '}' || c == ']') {
        depth--;
      } else if (c == separator && depth == 0) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(text.substring(start));
    return parts;
  }

  private static int closing(String text, int open) {
    int depth = 0;
    char quote = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
      } else if (c == '(' || c == '{' || c == '[') {
        depth++;
      } else if ((c == ')' || c == '}' || c == ']') && --depth == 0) {
        return i;
      }
    }
    throw new AssertionError("sin cierre desde " + open);
  }
}
