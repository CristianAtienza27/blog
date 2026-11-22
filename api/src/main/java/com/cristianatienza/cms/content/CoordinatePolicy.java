package com.cristianatienza.cms.content;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Coordenadas que acepta el backend antes de guardarlas.
 *
 * <p>PostgreSQL guarda las coordenadas como {@code NUMERIC(9,6)} y redondea en silencio un valor
 * con más decimales (41.1200001 pasaría a 41.12). Por eso el backend rechaza esos valores antes
 * de escribirlos, en lugar de dejar que la base de datos los altere:
 *
 * <ul>
 *   <li>{@link Use#DESTINATION}: lugares, rutas y mapas. Como máximo 6 decimales.
 *   <li>{@link Use#PHOTO_LOCATION}: ubicación pública de una fotografía. Como máximo 2 decimales
 *       (unos 1 km), la regla de privacidad del contrato (validate.ts, {@code coarsePoint}).
 * </ul>
 *
 * <p>Se trabaja siempre con {@link BigDecimal} a partir del valor original, nunca con
 * {@code double}, para no introducir decimales que no estaban.
 */
public final class CoordinatePolicy {

  /** Uso de la coordenada: decide los decimales admitidos. */
  public enum Use {
    DESTINATION(6),
    PHOTO_LOCATION(2);

    private final int maxDecimals;

    Use(int maxDecimals) {
      this.maxDecimals = maxDecimals;
    }

    public int maxDecimals() {
      return maxDecimals;
    }
  }

  private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
  private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

  private CoordinatePolicy() {}

  /**
   * Comprueba un par latitud/longitud.
   *
   * @throws InvalidCoordinateException si falta un valor, está fuera de rango o tiene más
   *     decimales de los que admite su uso.
   */
  public static void requireValid(BigDecimal lat, BigDecimal lng, Use use) {
    Objects.requireNonNull(use, "use");
    requireValue("lat", lat, MAX_LAT, use);
    requireValue("lng", lng, MAX_LNG, use);
  }

  /** Igual que {@link #requireValid(BigDecimal, BigDecimal, Use)} a partir del texto del JSON. */
  public static void requireValid(String lat, String lng, Use use) {
    requireValid(parse("lat", lat), parse("lng", lng), use);
  }

  /** Decimales significativos de un valor (sin contar los ceros finales). */
  static int decimals(BigDecimal value) {
    return Math.max(0, value.stripTrailingZeros().scale());
  }

  private static void requireValue(String name, BigDecimal value, BigDecimal max, Use use) {
    if (value == null) {
      throw new InvalidCoordinateException(name + ": falta el valor");
    }
    if (value.abs().compareTo(max) > 0) {
      throw new InvalidCoordinateException(
          name + ": fuera de rango [-" + max + ", " + max + "]: " + value.toPlainString());
    }
    int decimals = decimals(value);
    if (decimals > use.maxDecimals()) {
      throw new InvalidCoordinateException(
          name
              + ": "
              + value.toPlainString()
              + " tiene "
              + decimals
              + " decimales; "
              + (use == Use.PHOTO_LOCATION
                  ? "la ubicación pública de una foto admite como máximo 2 (privacidad)"
                  : "se admiten como máximo " + use.maxDecimals()));
    }
  }

  private static BigDecimal parse(String name, String text) {
    if (text == null || text.isBlank()) {
      throw new InvalidCoordinateException(name + ": falta el valor");
    }
    try {
      return new BigDecimal(text.trim());
    } catch (NumberFormatException e) {
      throw new InvalidCoordinateException(name + ": no es un número: «" + text + "»");
    }
  }

  /** Coordenada que no se puede guardar tal como llega. */
  public static final class InvalidCoordinateException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidCoordinateException(String message) {
      super(message);
    }
  }
}
