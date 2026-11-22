package com.cristianatienza.cms.content.io;

import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Comparación semántica estricta de dos árboles de contenido:
 *
 * <ul>
 *   <li>los mismos archivos (9 colecciones y un cuerpo por slug);
 *   <li>objetos: las mismas claves, sin importar su orden;
 *   <li>listas: los mismos elementos en el mismo orden;
 *   <li>números: el mismo valor decimal exacto (41.7197 = 41.71970; 41.7197 ≠ 41.72);
 *   <li>textos y booleanos: idénticos;
 *   <li>ausente, null, objeto vacío y lista vacía son cosas distintas.
 * </ul>
 */
public final class ContentComparator {

  private ContentComparator() {}

  /** Diferencias de {@code actual} respecto a {@code expected}, con su ruta (vacía si son iguales). */
  public static List<String> compare(Content expected, Content actual) {
    List<String> differences = new ArrayList<>();
    files(differences, "", expected.collections(), actual.collections(), ".json");
    files(differences, "articles/", expected.bodies(), actual.bodies(), ".json");
    return differences;
  }

  private static void files(List<String> differences, String prefix, Map<String, JsonNode> expected,
      Map<String, JsonNode> actual, String suffix) {
    Set<String> names = new LinkedHashSet<>(expected.keySet());
    names.addAll(actual.keySet());
    for (String name : names) {
      String file = prefix + name + suffix;
      if (!actual.containsKey(name)) {
        differences.add(file + ": falta");
      } else if (!expected.containsKey(name)) {
        differences.add(file + ": sobra");
      } else {
        compare(differences, prefix.isEmpty() ? name : file, expected.get(name), actual.get(name));
      }
    }
  }

  static void compare(List<String> differences, String path, JsonNode expected, JsonNode actual) {
    if (expected.isNumber() && actual.isNumber()) {
      if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
        differences.add(path + ": " + expected + " ≠ " + actual);
      }
      return;
    }
    if (expected.getNodeType() != actual.getNodeType()) {
      differences.add(path + ": " + describe(expected) + " ≠ " + describe(actual));
      return;
    }
    if (expected.isObject()) {
      Set<String> keys = new LinkedHashSet<>(expected.propertyNames());
      keys.addAll(actual.propertyNames());
      for (String key : keys) {
        if (!actual.has(key)) {
          differences.add(path + "." + key + ": falta (" + describe(expected.get(key)) + ")");
        } else if (!expected.has(key)) {
          differences.add(path + "." + key + ": sobra (" + describe(actual.get(key)) + ")");
        } else {
          compare(differences, path + "." + key, expected.get(key), actual.get(key));
        }
      }
    } else if (expected.isArray()) {
      if (expected.size() != actual.size()) {
        differences.add(path + ": " + expected.size() + " elementos ≠ " + actual.size());
      }
      for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
        compare(differences, path + "[" + i + "]", expected.get(i), actual.get(i));
      }
    } else if (!expected.equals(actual)) {
      differences.add(path + ": " + expected + " ≠ " + actual);
    }
  }

  private static String describe(JsonNode value) {
    return switch (value.getNodeType()) {
      case NULL -> "null";
      case OBJECT -> value.isEmpty() ? "{}" : "objeto";
      case ARRAY -> value.isEmpty() ? "[]" : "lista";
      case STRING -> "texto " + value;
      case NUMBER -> "número " + value;
      case BOOLEAN -> "booleano " + value;
      default -> value.getNodeType().toString();
    };
  }
}
