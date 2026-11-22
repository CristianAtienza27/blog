package com.cristianatienza.cms.content.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * La semántica de JS que el puerto necesita (fase 6.4.1). Los valores esperados se obtuvieron con
 * Node 26 ({@code String(n)}, {@code trim()}, {@code Object.keys}, {@code \s}).
 */
class JsTests {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "0|0", "-0.0|0", "1|1", "-1|-1", "1.5|1.5", "0.1|0.1", "0.30000000000000004|0.30000000000000004",
      "1e21|1e+21", "1e20|100000000000000000000", "123456789012345678901234|1.2345678901234569e+23",
      "1.5e-7|1.5e-7", "1e-6|0.000001", "1.234e-7|1.234e-7", "4.9e-324|5e-324",
      "1.7976931348623157e308|1.7976931348623157e+308", "12345678901234567890|12345678901234567000",
      "9007199254740992|9007199254740992", "-1.5e-7|-1.5e-7", "100|100", "1e100|1e+100", "0.5|0.5",
      "-123.456|-123.456", "4294967295|4294967295", "0.3333333333333333|0.3333333333333333"})
  void numeroComoStringDeJs(double value, String expected) {
    assertThat(Js.number(value)).isEqualTo(expected);
  }

  @Test
  void infinitosYNaN() {
    assertThat(Js.number(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
    assertThat(Js.number(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
    assertThat(Js.number(Double.NaN)).isEqualTo("NaN");
  }

  @Test
  void stringDeJsDeCadaTipoDeValor() {
    JsonNode value = ParityCorpus.JSON.readTree("[1, [2, null], {\"a\": 1}, \"x\", true, 1.50, 1e400]");
    assertThat(Js.string(value)).isEqualTo("1,2,,[object Object],x,true,1.5,Infinity");
    assertThat(Js.string(ParityCorpus.JSON.readTree("null"))).isEqualTo("null");
    assertThat(Js.string(null)).isEqualTo("undefined");
    assertThat(Js.string(ParityCorpus.JSON.readTree("{}"))).isEqualTo("[object Object]");
    assertThat(Js.string(ParityCorpus.JSON.readTree("[]"))).isEmpty();
  }

  @Test
  void ordenDeObjectKeysIndicesPrimero() {
    JsonNode object = ParityCorpus.JSON.readTree(
        "{\"b\":1,\"2\":1,\"a\":1,\"1\":1,\"__proto__\":5,\"01\":1,\"4294967295\":1,\"4294967294\":1,\"-1\":1}");
    assertThat(Js.keys(object)).containsExactly("1", "2", "4294967294", "b", "a", "__proto__", "01", "4294967295", "-1");
  }

  @Test
  void trimYEspaciosDeJs() {
    char nel = (char) 0x85;
    char mongolian = (char) 0x180E;
    String spaces = new String(new char[] {0xA0, 0xFEFF, 0x2028, 0x3000, 0x1680, 0x2000, 0x200A, 0x202F, 0x205F, 0x0B});
    assertThat(Js.trim(spaces)).isEmpty();
    assertThat(Js.trim(spaces + nel + spaces)).isEqualTo(String.valueOf(nel));
    assertThat(Js.trim(String.valueOf(mongolian))).hasSize(1);
    assertThat(Js.isWhitespace((char) 0x200B)).isFalse(); // espacio de ancho cero: no es Zs
    assertThat("a\nb".matches("a" + Js.DOT + "b")).isFalse();
    assertThat(("a" + nel + "b").matches("a" + Js.DOT + "b")).isTrue(); // el «.» de JS casa con U+0085
  }
}
