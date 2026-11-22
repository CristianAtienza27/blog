package com.cristianatienza.cms.content.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Las primitivas de {@code src/app/content/records/validate.ts} (Check, text, slug, url, isoDate,
 * oneOf, num, bool, list, shape, union, optional), con sus mensajes exactos (fase 6.4.1).
 *
 * <p>Un valor {@code null} de Java es una clave AUSENTE ({@code undefined} en JS); un {@code null} de
 * JSON llega como {@code NullNode}, igual que en JS llega {@code null} a la regla.
 *
 * <p><strong>Única diferencia deliberada:</strong> {@code shape} y {@code union} solo reconocen las
 * claves propias de la regla. En TS, {@code key in fields} y {@code variants[variant]} consultan
 * también el prototipo de Object, así que una clave {@code constructor}, {@code toString} o
 * {@code __proto__} no se marca como desconocida y un bloque {@code "type": "constructor"} se acepta
 * (o, con otros nombres, el validador lanza una excepción). Java las rechaza: es más estricto. Los
 * casos están en el corpus como divergencias documentadas.
 */
final class Rules {

  /** {@code Check}: acumula incidencias con la ruta del dato («articles[2].tags[0]: …»). */
  record Check(List<Issue> issues, String path) {
    Check at(String key) {
      return new Check(issues, path + "." + key);
    }

    Check at(int index) {
      return new Check(issues, path + "[" + index + "]");
    }

    void fail(String message) {
      issues.add(new Issue(path, message));
    }
  }

  /** {@code Rule}: comprueba un valor ({@code null} = ausente). */
  interface Rule {
    void check(Check c, JsonNode v);

    default boolean optional() {
      return false;
    }
  }

  static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern HTTPS_URL = Pattern.compile("https://[^" + Js.WS + "\"'<>]+");

  private Rules() {}

  static Rule optional(Rule rule) {
    return new Rule() {
      @Override
      public void check(Check c, JsonNode v) {
        rule.check(c, v);
      }

      @Override
      public boolean optional() {
        return true;
      }
    };
  }

  static boolean isRecord(JsonNode v) {
    return v != null && v.isObject();
  }

  /** {@code text(pattern?, label = 'formato')}. */
  static Rule text(Pattern pattern, String label) {
    return (c, v) -> {
      if (v == null || !v.isString() || Js.trim(v.stringValue()).isEmpty()) {
        c.fail("se esperaba un texto no vacío");
        return;
      }
      if (pattern != null && !pattern.matcher(v.stringValue()).matches()) {
        c.fail(label + " no válido: «" + Js.slice(v.stringValue(), 60) + "»");
      }
    };
  }

  static Rule text() {
    return text(null, "formato");
  }

  static Rule slug() {
    return text(SLUG, "slug");
  }

  static Rule url() {
    return text(HTTPS_URL, "URL https");
  }

  /** {@code isoDate}: formato AAAA-MM-DD y fecha que existe (como {@code new Date(…T00:00:00Z)}). */
  static Rule isoDate() {
    Rule format = text(ISO_DATE, "fecha");
    return (c, v) -> {
      format.check(c, v);
      if (v != null && v.isString() && ISO_DATE.matcher(v.stringValue()).matches()) {
        try {
          LocalDate.parse(v.stringValue());
        } catch (DateTimeParseException e) {
          c.fail("fecha inexistente: «" + v.stringValue() + "»");
        }
      }
    };
  }

  /** {@code oneOf(values)}. */
  static Rule oneOf(List<String> values) {
    return (c, v) -> {
      if (v == null || !v.isString() || !values.contains(v.stringValue())) {
        c.fail("valor no permitido: «" + Js.string(v) + "» (admitidos: " + String.join(", ", values) + ")");
      }
    };
  }

  /** {@code num({ min = -Infinity, max = Infinity, integer = false })}. */
  static Rule num(double min, double max, boolean integer) {
    return (c, v) -> {
      if (v == null || !v.isNumber() || !Double.isFinite(Js.number(v))) {
        c.fail("se esperaba un número");
        return;
      }
      double d = Js.number(v);
      if (integer && d != Math.rint(d)) {
        c.fail("se esperaba un entero: " + Js.number(d));
      }
      if (d < min || d > max) {
        c.fail("fuera de rango [" + Js.number(min) + ", " + Js.number(max) + "]: " + Js.number(d));
      }
    };
  }

  static Rule integer(double min) {
    return num(min, Double.POSITIVE_INFINITY, true);
  }

  static Rule bool() {
    return (c, v) -> {
      if (v == null || !v.isBoolean()) {
        c.fail("se esperaba true o false");
      }
    };
  }

  /** {@code list(each, min = 0)}. */
  static Rule list(Rule each, int min) {
    return (c, v) -> {
      if (v == null || !v.isArray()) {
        c.fail("se esperaba una lista");
        return;
      }
      if (v.size() < min) {
        c.fail("se esperaban al menos " + min + " elementos");
      }
      for (int i = 0; i < v.size(); i++) {
        each.check(c.at(i), v.get(i));
      }
    };
  }

  static Rule list(Rule each) {
    return list(each, 0);
  }

  /** {@code shape(fields)}: rechaza los que faltan y los desconocidos (erratas). */
  static Rule shape(Map<String, Rule> fields) {
    return (c, v) -> {
      if (!isRecord(v)) {
        c.fail("se esperaba un objeto");
        return;
      }
      for (Map.Entry<String, Rule> field : fields.entrySet()) {
        if (!v.has(field.getKey())) {
          if (!field.getValue().optional()) {
            c.at(field.getKey()).fail("campo obligatorio");
          }
          continue;
        }
        field.getValue().check(c.at(field.getKey()), v.get(field.getKey()));
      }
      for (String key : Js.keys(v)) {
        if (!fields.containsKey(key)) {
          c.at(key).fail("campo desconocido");
        }
      }
    };
  }

  /** {@code union(key, variants)}: la regla según el valor de {@code key}. */
  static Rule union(String key, Map<String, Rule> variants) {
    return (c, v) -> {
      if (!isRecord(v)) {
        c.fail("se esperaba un objeto");
        return;
      }
      JsonNode variant = v.get(key);
      Rule rule = variant != null && variant.isString() ? variants.get(variant.stringValue()) : null;
      if (rule == null) {
        c.at(key).fail("tipo desconocido: «" + Js.string(variant) + "»");
        return;
      }
      rule.check(c, v);
    };
  }

  /** Mapa ordenado de campos (el orden de declaración, como un literal de objeto en JS). */
  static Map<String, Rule> fields(Object... pairs) {
    Map<String, Rule> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], (Rule) pairs[i + 1]);
    }
    return map;
  }
}
