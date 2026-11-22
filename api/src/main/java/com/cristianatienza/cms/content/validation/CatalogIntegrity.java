package com.cristianatienza.cms.content.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * La parte de {@code src/app/content/records/integrity.ts} que es de UN registro del catálogo (fase
 * 6.4.7), en el mismo orden y con los mismos mensajes. Solo con la forma ya válida:
 *
 * <ul>
 *   <li>autores: el avatar existe y no está retirado;
 *   <li>lugares: la portada existe y no está retirada;
 *   <li>proyectos: la portada, y cada etiqueta de la pila existe;
 *   <li>itinerarios: la portada, cada parada es un destino que existe y las paradas van 1…n.
 * </ul>
 *
 * Las reglas de conjunto (ids y slugs repetidos, imágenes sin usar, el autor de las fotos propias…)
 * siguen en la puerta de publicación; la API protege por su cuenta las que puede romper una edición
 * (slug ocupado, renombrar el autor de fotos propias).
 */
public final class CatalogIntegrity {

  /** Lo que un registro puede referenciar: imágenes (y cuáles están retiradas), etiquetas y destinos. */
  public record Refs(Set<String> media, Set<String> retiredMedia, Set<String> tags, Set<String> destinations) {}

  private CatalogIntegrity() {}

  public static List<Issue> record(String collection, JsonNode r, Refs refs, String where) {
    List<Issue> issues = new ArrayList<>();
    switch (collection) {
      case "authors" -> useMedia(refs, text(r, "avatar"), where + ".avatar", issues);
      case "destinations" -> useMedia(refs, text(r, "cover"), where + ".cover", issues);
      case "projects" -> {
        useMedia(refs, text(r, "cover"), where + ".cover", issues);
        JsonNode stack = r.get("stack");
        for (int j = 0; j < stack.size(); j++) {
          ref(refs.tags(), stack.get(j).stringValue(), where + ".stack[" + j + "]", "etiqueta", issues);
        }
      }
      case "trips" -> {
        useMedia(refs, text(r, "cover"), where + ".cover", issues);
        JsonNode stops = r.get("stops");
        for (int j = 0; j < stops.size(); j++) {
          JsonNode stop = stops.get(j);
          String at = where + ".stops[" + j + "]";
          ref(refs.destinations(), stop.get("destination").stringValue(), at, "destino", issues);
          double order = Js.number(stop.get("order"));
          if (order != j + 1) {
            issues.add(new Issue(at, "orden " + Js.number(order) + ", se esperaba " + (j + 1)));
          }
        }
      }
      case "tags" -> {
        // integrity.ts no comprueba nada de una etiqueta por sí sola.
      }
      default -> throw new IllegalArgumentException("colección sin integridad: " + collection);
    }
    return issues;
  }

  private static String text(JsonNode r, String field) {
    JsonNode value = r.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  private static void ref(Set<String> set, String id, String where, String kind, List<Issue> issues) {
    if (id != null && !set.contains(id)) {
      issues.add(new Issue(where, kind + " inexistente «" + id + "»"));
    }
  }

  private static void useMedia(Refs refs, String id, String where, List<Issue> issues) {
    if (id == null) {
      return;
    }
    ref(refs.media(), id, where, "imagen", issues);
    if (refs.retiredMedia().contains(id)) {
      issues.add(new Issue(where, "imagen retirada «" + id + "»"));
    }
  }
}
