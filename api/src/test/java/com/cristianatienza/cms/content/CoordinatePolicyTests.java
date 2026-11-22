package com.cristianatienza.cms.content;

import static com.cristianatienza.cms.content.CoordinatePolicy.Use.DESTINATION;
import static com.cristianatienza.cms.content.CoordinatePolicy.Use.PHOTO_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.content.CoordinatePolicy.InvalidCoordinateException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Coordenadas que el backend rechaza antes de que PostgreSQL pueda redondearlas. */
class CoordinatePolicyTests {

  @ParameterizedTest(name = "destino {0}, {1}: válido")
  @CsvSource({
    "41.7197, 2.933", // datos reales (4 decimales)
    "41.123456, -9.123456", // 6 decimales
    "90, 180",
    "-90, -180",
    "0, 0",
    "41.120000, 2.900" // ceros finales: no cuentan
  })
  void destinoValido(String lat, String lng) {
    assertThatCode(() -> CoordinatePolicy.requireValid(lat, lng, DESTINATION)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "destino {0}, {1}: rechazado")
  @CsvSource({
    "41.1234567, 2.9", // 7 decimales: PostgreSQL lo redondearía en silencio
    "90.000001, 0", // fuera de rango por una millonésima
    "0, -180.5",
    "-90.5, 0",
    "abc, 0",
    "'', 0"
  })
  void destinoRechazado(String lat, String lng) {
    assertThatThrownBy(() -> CoordinatePolicy.requireValid(lat, lng, DESTINATION))
        .isInstanceOf(InvalidCoordinateException.class);
  }

  @ParameterizedTest(name = "foto {0}, {1}: válida")
  @CsvSource({"41.12, 2.93", "41.1, 2", "41.120, 2.900", "-90, 180"})
  void ubicacionDeFotoValida(String lat, String lng) {
    assertThatCode(() -> CoordinatePolicy.requireValid(lat, lng, PHOTO_LOCATION))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "foto {0}, {1}: rechazada")
  @CsvSource({
    "41.1200001, 2.93", // NUMERIC(9,6) lo dejaría en 41.12 y pasaría el CHECK de dos decimales
    "41.123, 2.93",
    "41.12, 2.931",
    "90.01, 0"
  })
  void ubicacionDeFotoRechazada(String lat, String lng) {
    assertThatThrownBy(() -> CoordinatePolicy.requireValid(lat, lng, PHOTO_LOCATION))
        .isInstanceOf(InvalidCoordinateException.class);
  }

  @Test
  void elMensajeExplicaLaReglaDePrivacidad() {
    assertThatThrownBy(() -> CoordinatePolicy.requireValid("41.1200001", "2.93", PHOTO_LOCATION))
        .hasMessageContaining("41.1200001")
        .hasMessageContaining("como máximo 2");
  }

  @Test
  void faltaUnValor() {
    assertThatThrownBy(() -> CoordinatePolicy.requireValid((BigDecimal) null, BigDecimal.ONE, DESTINATION))
        .isInstanceOf(InvalidCoordinateException.class)
        .hasMessageContaining("lat");
  }

  @Test
  void losCerosFinalesNoSonDecimales() {
    assertThat(CoordinatePolicy.decimals(new BigDecimal("41.120000"))).isEqualTo(2);
    assertThat(CoordinatePolicy.decimals(new BigDecimal("41"))).isZero();
    assertThat(CoordinatePolicy.decimals(new BigDecimal("4.1E+1"))).isZero();
  }
}
