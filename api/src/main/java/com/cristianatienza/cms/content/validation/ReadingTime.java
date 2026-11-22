package com.cristianatienza.cms.content.validation;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Tiempo de lectura de un cuerpo, puerto de {@code src/app/content/records/reading-time.ts} (fase
 * 6.4.1). El servidor lo calcula al guardar: la puerta de publicación exige que {@code readingMinutes}
 * sea exactamente este valor. Opera sobre bloques que ya cumplen la forma del contrato.
 */
public final class ReadingTime {

  /** Velocidad media de lectura en español (palabras por minuto). */
  public static final int WORDS_PER_MINUTE = 200;

  /** {@code /<[^>]*>/g}. */
  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** {@code /\s+/} con la clase de espacios de JS. */
  private static final Pattern SPACES = Pattern.compile("[" + Js.WS + "]+");

  /** {@code /[\p{L}\p{N}]/u}: una palabra tiene al menos una letra o un número. */
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]");

  private ReadingTime() {}

  /** Minutos de lectura, como mínimo uno ({@code readingMinutes}). */
  public static int minutes(JsonNode blocks) {
    return (int) Math.max(1, Math.round(countWords(blocks) / (double) WORDS_PER_MINUTE));
  }

  /** Palabras legibles de un cuerpo: texto, citas, capítulos, fichas y código ({@code countWords}). */
  public static int countWords(JsonNode blocks) {
    int total = 0;
    for (JsonNode block : blocks.values()) {
      switch (block.path("type").asString("")) {
        case "text" -> total += words(block, "html");
        case "quote" -> total += words(block, "text") + words(block, "cite");
        case "chapter" -> total += words(block, "title") + words(block, "kicker");
        case "specs" -> {
          total += words(block, "title") + words(block, "note");
          for (JsonNode item : block.path("items").values()) {
            total += words(item, "label") + words(item, "value");
          }
        }
        case "code" -> total += words(block, "code") + words(block, "caption");
        case "figures" -> {
          for (JsonNode item : block.path("items").values()) {
            total += words(item, "caption");
          }
        }
        case "image", "map" -> total += words(block, "caption");
        default -> {
          // gallery y divider no tienen texto que leer
        }
      }
    }
    return total;
  }

  private static int words(JsonNode owner, String field) {
    JsonNode value = owner.get(field);
    return value != null && value.isString() ? words(value.stringValue()) : 0;
  }

  /** {@code words}: etiquetas fuera, separar por espacios y contar lo que tenga letra o número. */
  static int words(String text) {
    if (text.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (String word : SPACES.split(TAG.matcher(text).replaceAll(" "))) {
      if (WORD.matcher(word).find()) {
        count++;
      }
    }
    return count;
  }
}
