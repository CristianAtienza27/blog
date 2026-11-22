package com.cristianatienza.cms.content.validation;

import com.cristianatienza.cms.content.validation.Rules.Check;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Validación de UN artículo y su cuerpo con las reglas de los validadores TypeScript (fase 6.4.1):
 *
 * <ol>
 *   <li><strong>Forma</strong> ({@code validate.ts}): campos, tipos, patrones, enumerados, rangos,
 *       bloques y el HTML de la lista blanca ({@link RichTextPolicy}).
 *   <li><strong>Integridad del artículo</strong> ({@code integrity.ts}, solo si la forma es válida,
 *       como {@code validateContent}): referencias existentes, imágenes retiradas, destinos
 *       repetidos, fecha de actualización, capítulos, anclas, enlaces a artículos y el tiempo de
 *       lectura ({@link ReadingTime}).
 * </ol>
 *
 * Las reglas de CONJUNTO de integrity.ts (ids y slugs repetidos entre artículos, orden repetido en
 * una sección, imágenes y galerías sin usar, autores de las fotos propias) no son de un artículo:
 * las sigue aplicando la puerta de publicación (6.3).
 *
 * <p>Las incidencias llevan las rutas de los validadores TS: {@code articlePath} para el artículo
 * («articles[3]») y «articles/&lt;slug&gt;.json» para el cuerpo.
 */
public final class ArticleValidator {

  /** {@code /href="([^"]*)"/g}. */
  private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

  /** {@code /^\/([a-z]+)\/([a-z0-9-]+)\/?(?:#.*)?$/} ({@code .} de JS: sin terminadores de línea). */
  private static final Pattern ARTICLE_LINK = Pattern.compile("/([a-z]+)/([a-z0-9-]+)/?(?:#" + Js.DOT + "*)?");

  /**
   * Lo que el artículo puede referenciar: los ids de cada catálogo, las paradas de cada itinerario
   * (en orden) y los artículos (slug y sección, en el orden del catálogo, incluido el propio).
   */
  public record Context(Set<String> tags, Set<String> authors, Set<String> media, Set<String> retiredMedia,
      Set<String> galleries, Set<String> destinations, Map<String, List<String>> tripStops, Set<String> projects,
      List<ArticleRef> articles) {

    public record ArticleRef(String slug, String category) {}
  }

  private ArticleValidator() {}

  /** Valida con la ruta del cuerpo «articles/&lt;slug&gt;.json» (String(slug), como el mapa de cuerpos en TS). */
  public static List<Issue> validate(JsonNode article, JsonNode body, Context context, String articlePath) {
    return validate(article, body, context, articlePath, "articles/" + Js.string(article.get("slug")) + ".json");
  }

  public static List<Issue> validate(JsonNode article, JsonNode body, Context context, String articlePath,
      String bodyPath) {
    List<Issue> issues = new ArrayList<>();
    ArticleRules.ARTICLE.check(new Check(issues, articlePath), article);
    ArticleRules.BODY.check(new Check(issues, bodyPath), body);
    if (!issues.isEmpty()) {
      return issues; // validateContent: las relaciones solo con la forma correcta
    }
    integrity(article, body, context, articlePath, bodyPath, issues);
    return issues;
  }

  // --- integrity.ts, la parte de un artículo (mismo orden de comprobaciones) --------------------

  private static void integrity(JsonNode a, JsonNode body, Context ctx, String where, String bodyName,
      List<Issue> issues) {
    String slug = a.get("slug").stringValue();

    List<String> tags = strings(a.get("tags"));
    for (int j = 0; j < tags.size(); j++) {
      ref(ctx.tags(), tags.get(j), where + ".tags[" + j + "]", "etiqueta", issues);
    }
    ref(ctx.authors(), a.get("author").stringValue(), where + ".author", "autor", issues);
    String trip = ArticleRules.stringOrNull(a.get("trip"));
    ref(ctx.tripStops().keySet(), trip, where + ".trip", "itinerario", issues);
    List<String> destinations = a.has("destinations") ? strings(a.get("destinations")) : List.of();
    for (int j = 0; j < destinations.size(); j++) {
      ref(ctx.destinations(), destinations.get(j), where + ".destinations[" + j + "]", "destino", issues);
    }
    List<String> stops = trip == null ? List.of() : ctx.tripStops().getOrDefault(trip, List.of());
    List<String> own = new ArrayList<>(stops);
    own.addAll(destinations);
    for (String id : duplicates(own)) {
      issues.add(new Issue(where + ".destinations", "destino repetido «" + id + "»"));
    }
    ref(ctx.projects(), ArticleRules.stringOrNull(a.get("project")), where + ".project", "proyecto", issues);
    useMedia(ctx, ArticleRules.stringOrNull(a.get("cover")), where + ".cover", issues);
    String updatedAt = ArticleRules.stringOrNull(a.get("updatedAt"));
    if (updatedAt != null && !updatedAt.isEmpty() && updatedAt.compareTo(a.get("publishedAt").stringValue()) < 0) {
      issues.add(new Issue(where + ".updatedAt", "anterior a la publicación"));
    }

    String bodySlug = body.get("slug").stringValue();
    if (!bodySlug.equals(slug)) {
      issues.add(new Issue(bodyName, "slug «" + bodySlug + "» distinto"));
    }

    List<String> chapters = new ArrayList<>();
    List<String> chapterPlaces = new ArrayList<>();
    List<String[]> anchors = new ArrayList<>(); // {href, at}
    JsonNode blocks = body.get("blocks");
    for (int j = 0; j < blocks.size(); j++) {
      JsonNode block = blocks.get(j);
      String at = bodyName + ".blocks[" + j + "]";
      switch (block.get("type").stringValue()) {
        case "image" -> useMedia(ctx, block.get("media").stringValue(), at, issues);
        case "figures" -> {
          JsonNode items = block.get("items");
          for (int k = 0; k < items.size(); k++) {
            useMedia(ctx, items.get(k).get("media").stringValue(), at + ".items[" + k + "]", issues);
          }
        }
        case "gallery" -> ref(ctx.galleries(), block.get("gallery").stringValue(), at, "galería", issues);
        case "map" -> {
          ref(ctx.tripStops().keySet(), ArticleRules.stringOrNull(block.get("trip")), at, "itinerario", issues);
          if (block.has("destinations")) {
            for (String d : strings(block.get("destinations"))) {
              ref(ctx.destinations(), d, at, "destino", issues);
            }
          }
        }
        case "chapter" -> {
          String id = block.get("id").stringValue();
          chapters.add(id);
          if (ArticleRules.RESERVED_IDS.contains(id)) {
            issues.add(new Issue(at, "id reservado «" + id + "»"));
          }
          if (id.startsWith(ArticleRules.SPECS_ID_PREFIX)) {
            issues.add(new Issue(at, "el prefijo «" + ArticleRules.SPECS_ID_PREFIX + "» es de las fichas («" + id + "»)"));
          }
          // El destino del capítulo existe y es uno de los del artículo (parada o destino propio).
          String destination = ArticleRules.stringOrNull(block.get("destination"));
          if (destination != null) {
            chapterPlaces.add(destination);
            ref(ctx.destinations(), destination, at + ".destination", "destino", issues);
            if (ctx.destinations().contains(destination) && !own.contains(destination)) {
              issues.add(new Issue(at + ".destination",
                  "«" + destination + "» no es una parada ni un destino del artículo"));
            }
          }
        }
        case "text" -> {
          Matcher href = HREF.matcher(block.get("html").stringValue());
          while (href.find()) {
            anchors.add(new String[] {href.group(1), at});
          }
        }
        default -> {
          // quote, code, specs, divider: sin referencias
        }
      }
    }
    for (String id : duplicates(chapters)) {
      issues.add(new Issue(bodyName, "capítulo repetido «" + id + "»"));
    }
    for (String id : duplicates(chapterPlaces)) {
      issues.add(new Issue(bodyName, "dos capítulos tratan el destino «" + id + "»"));
    }
    // Anclas a capítulos del propio artículo y enlaces a artículos que existan en su sección.
    for (String[] anchor : anchors) {
      String href = anchor[0];
      if (href.startsWith("#") && !chapters.contains(href.substring(1))) {
        issues.add(new Issue(anchor[1], "ancla a un capítulo inexistente «" + href + "»"));
      }
      Matcher target = ARTICLE_LINK.matcher(href);
      if (target.matches() && ArticleRules.SECTION_IDS.contains(target.group(1))) {
        String linkedCategory = ctx.articles().stream()
            .filter(other -> other.slug().equals(target.group(2)))
            .findFirst()
            .map(Context.ArticleRef::category)
            .orElse(null);
        if (!Objects.equals(linkedCategory, target.group(1))) {
          issues.add(new Issue(anchor[1], "enlace a un artículo inexistente «" + href + "»"));
        }
      }
    }

    int minutes = ReadingTime.minutes(blocks);
    double declared = Js.number(a.get("readingMinutes"));
    if (declared != minutes) {
      issues.add(new Issue(where + ".readingMinutes", Js.number(declared) + ", calculado " + minutes));
    }
  }

  /** {@code ref}: un id presente que no existe en su catálogo. */
  private static void ref(Set<String> set, String id, String where, String kind, List<Issue> issues) {
    if (id != null && !set.contains(id)) {
      issues.add(new Issue(where, kind + " inexistente «" + id + "»"));
    }
  }

  /** {@code useMedia}: la imagen existe y no está retirada. */
  private static void useMedia(Context ctx, String id, String where, List<Issue> issues) {
    if (id == null) {
      return;
    }
    ref(ctx.media(), id, where, "imagen", issues);
    if (ctx.retiredMedia().contains(id)) {
      issues.add(new Issue(where, "imagen retirada «" + id + "»"));
    }
  }

  /** {@code duplicates}: cada valor repetido una sola vez, en el orden de su primera repetición. */
  static List<String> duplicates(List<String> values) {
    Set<String> seen = new LinkedHashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    for (String value : values) {
      if (!seen.add(value)) {
        repeated.add(value);
      }
    }
    return List.copyOf(repeated);
  }

  private static List<String> strings(JsonNode list) {
    List<String> out = new ArrayList<>();
    for (JsonNode item : list.values()) {
      out.add(item.stringValue());
    }
    return out;
  }
}
