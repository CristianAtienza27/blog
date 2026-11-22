package com.cristianatienza.cms.content.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Usos de imágenes que viven en el código de la web, no en src/content:
 *
 * <ul>
 *   <li>{@code RETIRED_MEDIA} (src/app/editorial/retired-media.ts): imágenes retiradas.
 *   <li>{@code SECTIONS[].preview} (src/app/layout/navigation.ts): vista previa de cada sección
 *       en el menú.
 * </ul>
 *
 * <p>navigation.ts importa un componente Angular, así que no se puede cargar: se lee como
 * texto con un análisis estricto. Si el formato no es el esperado, falla con un mensaje claro;
 * nunca devuelve una lista vacía por no haber encontrado nada.
 */
public final class CodeUsages {

  /** Las secciones de la revista, en su orden (SECTION_IDS en models/taxonomy.ts). */
  public static final List<String> SECTION_IDS = List.of("viajes", "fotografia", "motor", "experiencias", "tecnologia");

  private static final String SLUG = "[a-z0-9]+(?:-[a-z0-9]+)*";
  private static final Pattern RETIRED_DECLARATION =
      Pattern.compile("export const RETIRED_MEDIA: readonly string\\[] = \\[(.*?)\\];", Pattern.DOTALL);
  private static final Pattern RETIRED_ENTRY = Pattern.compile("\\s*'(" + SLUG + ")',?");
  private static final Pattern ID = Pattern.compile("(?m)^\\s{4}id: '([a-z]+)',\\s*$");
  private static final Pattern PREVIEW = Pattern.compile("(?m)^\\s{4}preview: '(" + SLUG + ")',\\s*$");

  /** Imágenes retiradas y vista previa de cada sección (en el orden de las secciones). */
  public record Usages(List<String> retired, Map<String, String> previews) {}

  private CodeUsages() {}

  public static Usages read(Path retiredMediaTs, Path navigationTs) {
    return new Usages(retired(read(retiredMediaTs)), previews(read(navigationTs)));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + file, e);
    }
  }

  static List<String> retired(String source) {
    Matcher declaration = RETIRED_DECLARATION.matcher(source);
    if (!declaration.find()) {
      throw new ContentException("retired-media.ts: no se encuentra «export const RETIRED_MEDIA: readonly string[] = [ … ];»");
    }
    List<String> ids = new ArrayList<>();
    String body = declaration.group(1).replaceAll("//[^\\n]*", "");
    Matcher entry = RETIRED_ENTRY.matcher(body);
    int position = 0;
    while (entry.find() && entry.start() == position) {
      ids.add(entry.group(1));
      position = entry.end();
    }
    if (!body.substring(position).isBlank()) {
      throw new ContentException("retired-media.ts: contenido no reconocido en RETIRED_MEDIA: «"
          + body.substring(position).strip() + "»");
    }
    if (ids.isEmpty()) {
      throw new ContentException("retired-media.ts: RETIRED_MEDIA no tiene ninguna imagen");
    }
    if (ids.stream().distinct().count() != ids.size()) {
      throw new ContentException("retired-media.ts: imágenes repetidas en RETIRED_MEDIA");
    }
    return List.copyOf(ids);
  }

  static Map<String, String> previews(String source) {
    String block = arrayLiteral(source, "export const SECTIONS: readonly NavSection[] = ");
    List<String> sections = topLevelObjects(block);
    if (sections.size() != SECTION_IDS.size()) {
      throw new ContentException("navigation.ts: SECTIONS debe tener " + SECTION_IDS.size()
          + " secciones y tiene " + sections.size());
    }
    Map<String, String> previews = new LinkedHashMap<>();
    for (int i = 0; i < sections.size(); i++) {
      String section = sections.get(i);
      String id = single(ID, section, "id", i);
      if (!id.equals(SECTION_IDS.get(i))) {
        throw new ContentException("navigation.ts: SECTIONS[" + i + "] es «" + id + "»; se esperaba «"
            + SECTION_IDS.get(i) + "» (orden de SECTION_IDS)");
      }
      previews.put(id, single(PREVIEW, section, "preview", i));
    }
    return previews;
  }

  private static String single(Pattern pattern, String section, String name, int index) {
    Matcher matcher = pattern.matcher(section);
    if (!matcher.find()) {
      throw new ContentException("navigation.ts: SECTIONS[" + index + "] sin «" + name + ": '…',»");
    }
    String value = matcher.group(1);
    if (matcher.find()) {
      throw new ContentException("navigation.ts: SECTIONS[" + index + "] tiene más de un «" + name + "»");
    }
    return value;
  }

  /** Texto entre los corchetes del literal de lista que sigue a la declaración. */
  private static String arrayLiteral(String source, String declaration) {
    int start = source.indexOf(declaration);
    if (start < 0 || source.indexOf(declaration, start + 1) >= 0) {
      throw new ContentException("navigation.ts: no se encuentra una única «" + declaration.strip() + "[ … ];»");
    }
    int open = start + declaration.length();
    if (open >= source.length() || source.charAt(open) != '[') {
      throw new ContentException("navigation.ts: se esperaba «[» tras «" + declaration.strip() + "»");
    }
    int close = matching(source, open, '[', ']');
    return source.substring(open + 1, close);
  }

  /** Objetos de primer nivel de una lista literal ({ … }, { … }). */
  private static List<String> topLevelObjects(String list) {
    List<String> objects = new ArrayList<>();
    int i = 0;
    while (i < list.length()) {
      char c = list.charAt(i);
      if (c == '{') {
        int close = matching(list, i, '{', '}');
        objects.add(list.substring(i, close + 1));
        i = close + 1;
      } else if (Character.isWhitespace(c) || c == ',') {
        i++;
      } else {
        throw new ContentException("navigation.ts: contenido no reconocido en SECTIONS cerca de «"
            + list.substring(i, Math.min(list.length(), i + 30)) + "»");
      }
    }
    return objects;
  }

  /** Posición del cierre que corresponde a la apertura en {@code open}, saltando textos. */
  private static int matching(String text, int open, char opening, char closing) {
    int depth = 0;
    char quote = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
      } else if (c == opening) {
        depth++;
      } else if (c == closing && --depth == 0) {
        return i;
      }
    }
    throw new ContentException("navigation.ts: falta el cierre «" + closing + "»");
  }
}
