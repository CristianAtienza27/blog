package com.cristianatienza.cms.content.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Política del texto enriquecido (TextBlock.html), puerto exacto de {@code src/app/content/rich-text.ts}
 * (fase 6.4.1): la misma lista blanca, los mismos pasos en el mismo orden y los mismos mensajes. La
 * paridad con TypeScript la comprueba el corpus compartido ({@code contract-cases/texto-enriquecido.json}).
 *
 * <p>Las expresiones son las de rich-text.ts con la semántica de JS (clase {@code \s}, fin de cadena
 * con {@code matches()}): ver {@link Js}. Ninguna regla se ha resumido en una expresión nueva.
 */
public final class RichTextPolicy {

  /** Etiquetas admitidas: las de ALLOWED_TAGS de rich-text.ts. */
  public static final Set<String> ALLOWED_TAGS = Set.of("p", "h3", "em", "strong", "a", "code", "ul", "ol", "li", "br");

  /** {@code /<[^<>]*>/g}: cualquier cosa entre < y >. */
  private static final Pattern ANY_TAG = Pattern.compile("<[^<>]*>");

  /** {@code /^<\/?([a-zA-Z][a-zA-Z0-9-]*)(.*)>$/s}. */
  private static final Pattern PARTS = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9-]*)(.*)>", Pattern.DOTALL);

  /** {@code /\/\s*$/}: la barra final de una etiqueta («<br />»). */
  private static final Pattern TRAILING_SLASH = Pattern.compile("/[" + Js.WS + "]*\\z");

  /** {@code CANONICAL}: formas canónicas, las únicas que se admiten. */
  private static final Pattern CANONICAL = Pattern.compile("<(?:/?(?:p|h3|em|strong|code|ul|ol|li|a)|br ?/?)>");

  /** {@code LINK}: enlaces exactamente como {@code <a href="…">}. */
  private static final Pattern LINK = Pattern.compile("<a href=\"([^\"]*)\">");

  /** {@code INTERNAL = /^\/(?!\/)[^"<>\s\\]*$/}. */
  private static final Pattern INTERNAL = Pattern.compile("/(?!/)[^\"<>" + Js.WS + "\\\\]*");

  /** {@code ANCHOR = /^#[a-z0-9-]+$/}. */
  private static final Pattern ANCHOR = Pattern.compile("#[a-z0-9-]+");

  /** {@code EXTERNAL = /^https:\/\/[^"<>\s\\]+$/}. */
  private static final Pattern EXTERNAL = Pattern.compile("https://[^\"<>" + Js.WS + "\\\\]+");

  /** Origen ficticio con el que rich-text.ts resuelve las rutas internas. */
  private static final String ORIGIN_HOST = "revista.invalid";

  private RichTextPolicy() {}

  /**
   * {@code isInternalPath}: empieza por una sola «/», sin barras invertidas ni espacios, y el
   * navegador la resuelve en el mismo origen ({@code new URL(href, ORIGIN).origin === ORIGIN}).
   */
  public static boolean isInternalPath(String href) {
    return INTERNAL.matcher(href).matches() && resolvesToOrigin(href);
  }

  /** {@code isAllowedHref}: ruta interna segura, ancla a un capítulo o https. */
  public static boolean isAllowedHref(String href) {
    return isInternalPath(href) || ANCHOR.matcher(href).matches() || EXTERNAL.matcher(href).matches();
  }

  /** {@code htmlIssues}: incidencias de un HTML respecto a la lista blanca (vacía si es válido). */
  public static List<String> issues(String html) {
    List<String> issues = new ArrayList<>();
    boolean inLink = false;
    Matcher tags = ANY_TAG.matcher(html);
    while (tags.find()) {
      String raw = tags.group();
      Matcher parts = PARTS.matcher(raw);
      if (!parts.matches()) {
        issues.add("carácter < suelto");
        continue;
      }
      String name = parts.group(1).toLowerCase(Locale.ROOT);
      String attributes = Js.trim(TRAILING_SLASH.matcher(parts.group(2)).replaceFirst(""));
      Matcher link = LINK.matcher(raw);
      boolean isLink = link.matches();
      if (!ALLOWED_TAGS.contains(name)) {
        issues.add("etiqueta no permitida: <" + name + ">");
      } else if (isLink) {
        if (!isAllowedHref(link.group(1))) {
          issues.add("atributos no permitidos en <a>: href=\"" + link.group(1) + "\"");
        }
        if (inLink) {
          issues.add("enlace dentro de otro enlace");
        }
        inLink = true;
      } else if (!attributes.isEmpty() && !raw.startsWith("</")) {
        issues.add("atributos no permitidos en <" + name + ">: " + attributes);
      } else if (!CANONICAL.matcher(raw).matches()) {
        issues.add("formato no admitido: " + raw + " (se espera <" + (raw.startsWith("</") ? "/" : "") + name + ">)");
      } else if (raw.equals("</a>")) {
        inLink = false;
      }
    }
    if (ANY_TAG.matcher(html).replaceAll("").contains("<")) {
      issues.add("carácter < suelto");
    }
    return issues;
  }

  /**
   * {@code new URL(href, "https://revista.invalid").origin === "https://revista.invalid"} para una
   * referencia que empieza por «/» (la única forma que llega aquí: INTERNAL ya lo exige). Sigue los
   * pasos del analizador de URL de WHATWG que deciden el origen de una referencia relativa a una base
   * https («special»):
   *
   * <ol>
   *   <li>Se quitan los controles C0 y espacios del principio y del final.
   *   <li>Se eliminan todos los tabuladores y saltos de línea ASCII (en cualquier posición).
   *   <li>Estado relativo: «/» lleva al estado de barra relativa. Si el siguiente es «/» o «\»
   *       (base especial), la referencia trae su propio host (estado de autoridad); si no, es una
   *       ruta y conserva el host de la base: mismo origen.
   * </ol>
   *
   * Con autoridad propia, el origen solo coincide si el host es exactamente el de la base, sin
   * puerto o con el 443. INTERNAL impide llegar ahí ((?!/), sin «\» ni espacios), pero el paso se
   * conserva para no depender de esa expresión.
   */
  private static boolean resolvesToOrigin(String href) {
    int start = 0;
    int end = href.length();
    while (start < end && href.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && href.charAt(end - 1) <= ' ') {
      end--;
    }
    StringBuilder input = new StringBuilder(end - start);
    for (int i = start; i < end; i++) {
      char c = href.charAt(i);
      if (c != '\t' && c != '\n' && c != '\r') {
        input.append(c);
      }
    }
    if (input.isEmpty()) {
      return true; // la propia base
    }
    if (input.charAt(0) != '/' && input.charAt(0) != '\\') {
      return false; // fuera del alcance de este método (INTERNAL exige «/» al principio)
    }
    if (input.length() < 2 || (input.charAt(1) != '/' && input.charAt(1) != '\\')) {
      return true; // ruta absoluta: hereda el host de la base
    }
    // Autoridad propia: //[usuario@]host[:puerto] hasta «/», «\», «?» o «#».
    int i = 2;
    while (i < input.length() && (input.charAt(i) == '/' || input.charAt(i) == '\\')) {
      i++;
    }
    int authorityEnd = i;
    while (authorityEnd < input.length() && "/\\?#".indexOf(input.charAt(authorityEnd)) < 0) {
      authorityEnd++;
    }
    String authority = input.substring(i, authorityEnd);
    String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
    int colon = hostPort.lastIndexOf(':');
    String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
    String port = colon < 0 ? "" : hostPort.substring(colon + 1);
    return host.toLowerCase(Locale.ROOT).equals(ORIGIN_HOST) && (port.isEmpty() || port.equals("443"));
  }
}
