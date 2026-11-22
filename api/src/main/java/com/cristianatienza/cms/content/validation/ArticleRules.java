package com.cristianatienza.cms.content.validation;

import static com.cristianatienza.cms.content.validation.Rules.bool;
import static com.cristianatienza.cms.content.validation.Rules.fields;
import static com.cristianatienza.cms.content.validation.Rules.integer;
import static com.cristianatienza.cms.content.validation.Rules.isRecord;
import static com.cristianatienza.cms.content.validation.Rules.isoDate;
import static com.cristianatienza.cms.content.validation.Rules.list;
import static com.cristianatienza.cms.content.validation.Rules.num;
import static com.cristianatienza.cms.content.validation.Rules.oneOf;
import static com.cristianatienza.cms.content.validation.Rules.optional;
import static com.cristianatienza.cms.content.validation.Rules.shape;
import static com.cristianatienza.cms.content.validation.Rules.slug;
import static com.cristianatienza.cms.content.validation.Rules.text;
import static com.cristianatienza.cms.content.validation.Rules.union;

import com.cristianatienza.cms.content.validation.Rules.Rule;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Las reglas de forma de un artículo y de su cuerpo, regla a regla como en
 * {@code src/app/content/records/validate.ts} ({@code article}, {@code block}, {@code articleBody})
 * (fase 6.4.1). Las constantes son las de {@code content/models}; ArticleRulesTests comprueba que
 * no se desfasan.
 */
final class ArticleRules {

  static final List<String> SECTION_IDS = List.of("viajes", "fotografia", "motor", "experiencias", "tecnologia");
  static final List<String> ARTICLE_FORMATS = List.of("reportaje", "guia", "ensayo-fotografico", "ficha", "nota");
  static final List<String> IMAGE_RATIOS = List.of("4/5", "1/1", "3/2", "16/9", "21/9");
  static final List<String> IMAGE_ALIGNS = List.of("start", "end");
  static final List<String> FIGURES_LAYOUTS = List.of("diptych", "triptych", "sequence");
  static final String SPECS_ID_PREFIX = "datos-";
  /** {@code RESERVED_IDS} de integrity.ts: ids que ya usa la página de artículo. */
  static final List<String> RESERVED_IDS = List.of("article-title", "contenido", "site-menu");

  /** Fotografías de cada composición: el díptico, dos; el tríptico, tres; la secuencia, 2 o 3. */
  private static final Map<String, int[]> FIGURES_COUNT = Map.of(
      "diptych", new int[] {2, 2}, "triptych", new int[] {3, 3}, "sequence", new int[] {2, 3});

  private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9+#-]+");

  private ArticleRules() {}

  private static final Rule RICH_TEXT = (c, v) -> {
    text().check(c, v);
    if (v != null && v.isString()) {
      for (String issue : RichTextPolicy.issues(v.stringValue())) {
        c.fail(issue);
      }
    }
  };

  /** Campos del artículo, en el orden de validate.ts (ArticleRulesTests los compara con ContentContract). */
  static final Map<String, Rule> ARTICLE_FIELDS = fields(
      "id", slug(),
      "slug", slug(),
      "title", text(),
      "dek", text(),
      "excerpt", text(),
      "category", oneOf(SECTION_IDS),
      "tags", list(slug()),
      "format", oneOf(ARTICLE_FORMATS),
      "cover", optional(slug()),
      "author", slug(),
      "order", integer(1),
      "publishedAt", isoDate(),
      "updatedAt", optional(isoDate()),
      "readingMinutes", integer(1),
      "featured", bool(),
      "demo", bool(),
      "trip", optional(slug()),
      "destinations", optional(list(slug())),
      "project", optional(slug()),
      "seo", optional(shape(fields("description", optional(text())))));

  static final Rule ARTICLE = shape(ARTICLE_FIELDS);

  private static final Rule FOCAL_POINT = shape(fields(
      "x", num(0, 1, false),
      "y", num(0, 1, false)));

  private static final Rule FIGURE_ITEM = shape(fields(
      "media", slug(),
      "ratio", optional(oneOf(IMAGE_RATIOS)),
      "focal", optional(FOCAL_POINT),
      "caption", optional(text())));

  private static final Map<String, Rule> IMAGE_FIELDS = fields(
      "type", text(),
      "media", slug(),
      "layout", oneOf(List.of("content", "wide", "full")),
      "caption", optional(text()),
      "ratio", optional(oneOf(IMAGE_RATIOS)),
      "align", optional(oneOf(IMAGE_ALIGNS)),
      "focal", optional(FOCAL_POINT));

  private static final Map<String, Rule> FIGURES_FIELDS = fields(
      "type", text(),
      "layout", oneOf(FIGURES_LAYOUTS),
      "items", list(FIGURE_ITEM, 2));

  private static final Map<String, Rule> MAP_FIELDS = fields(
      "type", text(),
      "trip", optional(slug()),
      "destinations", optional(list(slug(), 1)),
      "route", bool(),
      "caption", optional(text()));

  /** Campos de cada tipo de bloque, en el orden de validate.ts (comparados con ContentContract). */
  static final Map<String, Map<String, Rule>> BLOCK_FIELDS = blockFields();

  private static Map<String, Map<String, Rule>> blockFields() {
    Map<String, Map<String, Rule>> map = new java.util.LinkedHashMap<>();
    map.put("text", fields("type", text(), "html", RICH_TEXT));
    map.put("image", IMAGE_FIELDS);
    map.put("figures", FIGURES_FIELDS);
    map.put("gallery", fields("type", text(), "gallery", slug()));
    map.put("quote", fields("type", text(), "text", text(), "cite", optional(text()), "variant", oneOf(List.of("pull", "block"))));
    map.put("map", MAP_FIELDS);
    map.put("code", fields("type", text(), "language", text(LANGUAGE, "lenguaje"), "code", text(),
        "filename", optional(text()), "caption", optional(text())));
    map.put("specs", fields("type", text(), "title", optional(text()),
        "items", list(shape(fields("label", text(), "value", text(), "unit", optional(text()))), 1),
        "note", optional(text())));
    map.put("chapter", fields("type", text(), "id", slug(), "number", optional(integer(1)), "title", text(),
        "kicker", optional(text()), "destination", optional(slug())));
    map.put("divider", fields("type", text(), "variant", oneOf(List.of("line", "ornament", "pause"))));
    return java.util.Collections.unmodifiableMap(map);
  }

  private static final Rule IMAGE_SHAPE = shape(IMAGE_FIELDS);
  private static final Rule FIGURES_SHAPE = shape(FIGURES_FIELDS);
  private static final Rule MAP_SHAPE = shape(MAP_FIELDS);

  static final Rule BLOCK = union("type", Map.of(
      "text", shape(BLOCK_FIELDS.get("text")),
      "image", (c, v) -> {
        IMAGE_SHAPE.check(c, v);
        // A sangre ocupa la pantalla entera: no hay margen hacia el que romper.
        if (isRecord(v) && "full".equals(stringOrNull(v.get("layout"))) && v.has("align")) {
          c.at("align").fail("una imagen a sangre (full) no se alinea a un margen");
        }
      },
      "figures", (c, v) -> {
        FIGURES_SHAPE.check(c, v);
        if (!isRecord(v) || v.get("items") == null || !v.get("items").isArray()) {
          return;
        }
        // FIGURES_COUNT[v.layout]: JS convierte la clave con String() (["diptych"] → "diptych").
        int[] range = FIGURES_COUNT.get(Js.string(v.get("layout")));
        if (range == null) {
          return;
        }
        int min = range[0];
        int max = range[1];
        int n = v.get("items").size();
        if (n < min || n > max) {
          String expected = min == max ? String.valueOf(min) : "entre " + min + " y " + max;
          c.at("items").fail(Js.string(v.get("layout")) + ": " + expected + " fotografías, no " + n);
        }
      },
      "gallery", shape(BLOCK_FIELDS.get("gallery")),
      "quote", shape(BLOCK_FIELDS.get("quote")),
      "map", (c, v) -> {
        MAP_SHAPE.check(c, v);
        if (isRecord(v) && v.has("trip") == v.has("destinations")) {
          c.fail("el mapa necesita un itinerario (trip) o una lista de destinos, no ambos");
        }
      },
      "code", shape(BLOCK_FIELDS.get("code")),
      "specs", shape(BLOCK_FIELDS.get("specs")),
      "chapter", shape(BLOCK_FIELDS.get("chapter")),
      "divider", shape(BLOCK_FIELDS.get("divider"))));

  static final Rule BODY = shape(fields("slug", slug(), "blocks", list(BLOCK, 1)));

  /** El texto de un nodo si es una cadena; si no, null (como comparar con === en JS). */
  static String stringOrNull(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : null;
  }
}
