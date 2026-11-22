package com.cristianatienza.cms.content.validation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;

/**
 * Semántica de JavaScript que los validadores TypeScript usan y Java no tiene igual (fase 6.4.1).
 * Sin esto, el puerto «parecería» igual pero divergiría en los bordes:
 *
 * <ul>
 *   <li>{@code \s} y {@code trim()}: en JS incluyen los espacios Unicode (Zs), NBSP, BOM y los
 *       separadores de línea; en Java, {@code \s} solo es ASCII y {@code strip()} usa otra lista.
 *   <li>{@code .} sin la bandera {@code s}: en JS no casa con \n, \r, U+2028 ni U+2029; en Java
 *       tampoco con U+0085, que en JS sí casa. Por eso las expresiones portadas no usan {@code .}.
 *   <li>{@code $} sin la bandera {@code m}: en JS solo casa al final; en Java, también antes de un
 *       salto de línea final. Las expresiones portadas se evalúan con {@code matches()} (entera).
 *   <li>{@code String(valor)}: números con el formato de Number::toString, listas unidas con comas
 *       y objetos como «[object Object]».
 *   <li>{@code Object.keys}: primero las claves que son índices de lista, en orden numérico.
 * </ul>
 */
final class Js {

  /** Clase de caracteres de {@code \s} en JS (WhiteSpace y LineTerminator de ECMAScript). */
  static final String WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

  /** {@code .} de JS sin la bandera {@code s}: cualquier carácter salvo los terminadores de línea. */
  static final String DOT = "[^\\n\\r\\u2028\\u2029]";

  private Js() {}

  static boolean isWhitespace(char c) {
    // Puntos de código (no caracteres literales, que serían invisibles en el código fuente).
    return switch (c) {
      case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true;
      default -> c >= 0x2000 && c <= 0x200A;
    };
  }

  /** {@code String.prototype.trim}. */
  static String trim(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isWhitespace(s.charAt(start))) {
      start++;
    }
    while (end > start && isWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return s.substring(start, end);
  }

  /** {@code s.slice(0, n)} (unidades UTF-16, como en JS). */
  static String slice(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  /**
   * Valor de un número JSON tal como lo ve JS ({@code JSON.parse}): un double, infinito si no cabe
   * (1e400). Jackson lanza una excepción en {@code doubleValue()} fuera de rango; la conversión de
   * {@link Number#doubleValue()} (BigDecimal, BigInteger) da infinito, como JS.
   */
  static double number(JsonNode node) {
    return node.numberValue().doubleValue();
  }

  /** {@code Number::toString} de ECMAScript (base 10). */
  static String number(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (d == 0) {
      return "0"; // también -0
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    if (d < 0) {
      return "-" + number(-d);
    }
    BigDecimal decimal = shortest(d);
    String digits = decimal.unscaledValue().toString();
    int k = digits.length();
    int n = k - decimal.scale();
    if (k <= n && n <= 21) {
      return digits + "0".repeat(n - k);
    }
    if (0 < n && n <= 21) {
      return digits.substring(0, n) + "." + digits.substring(n);
    }
    if (-6 < n && n <= 0) {
      return "0." + "0".repeat(-n) + digits;
    }
    String exponent = (n - 1 >= 0 ? "+" : "-") + Math.abs(n - 1);
    return k == 1 ? digits + "e" + exponent : digits.charAt(0) + "." + digits.substring(1) + "e" + exponent;
  }

  /**
   * Los dígitos más cortos que vuelven al mismo double y, entre ellos, los más cercanos (Number::toString).
   * Double.toString ya da los más cortos desde Java 19, salvo que exige al menos dos cifras en notación
   * científica (5e-324 lo escribe 4.9E-324): por eso se prueba también con menos cifras.
   */
  private static BigDecimal shortest(double d) {
    BigDecimal fromJava = new BigDecimal(Double.toString(d)).stripTrailingZeros();
    for (int precision = 1; precision < fromJava.precision(); precision++) {
      BigDecimal candidate = new BigDecimal(d).round(new MathContext(precision, RoundingMode.HALF_EVEN));
      if (candidate.doubleValue() == d) {
        return candidate.stripTrailingZeros();
      }
    }
    return fromJava;
  }

  /** {@code String(valor)}; {@code null} (clave ausente) es {@code undefined}. */
  static String string(JsonNode node) {
    if (node == null || node.isMissingNode()) {
      return "undefined";
    }
    if (node.isNull()) {
      return "null";
    }
    if (node.isString()) {
      return node.stringValue();
    }
    if (node.isNumber()) {
      return number(number(node));
    }
    if (node.isBoolean()) {
      return Boolean.toString(node.booleanValue());
    }
    if (node.isArray()) {
      StringJoiner joined = new StringJoiner(",");
      for (JsonNode element : node.values()) {
        // Array.prototype.join: null y undefined se convierten en cadena vacía.
        joined.add(element.isNull() ? "" : string(element));
      }
      return joined.toString();
    }
    return "[object Object]";
  }

  /** {@code Object.keys} de un objeto JSON: primero los índices de lista, en orden numérico. */
  static List<String> keys(JsonNode object) {
    List<String> indexes = new ArrayList<>();
    List<String> others = new ArrayList<>();
    for (String key : object.propertyNames()) {
      (isArrayIndex(key) ? indexes : others).add(key);
    }
    indexes.sort((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
    indexes.addAll(others);
    return indexes;
  }

  /** Índice de lista de ECMAScript: forma canónica de un entero entre 0 y 2³² − 2. */
  private static boolean isArrayIndex(String key) {
    if (key.isEmpty() || key.length() > 10 || (key.length() > 1 && key.charAt(0) == '0')) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      if (key.charAt(i) < '0' || key.charAt(i) > '9') {
        return false;
      }
    }
    return Long.parseLong(key) < 4_294_967_295L;
  }
}
