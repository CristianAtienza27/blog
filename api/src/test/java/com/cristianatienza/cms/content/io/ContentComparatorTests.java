package com.cristianatienza.cms.content.io;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Comparación semántica estricta. */
class ContentComparatorTests {

  private static JsonNode json(String text) {
    return ContentSource.JSON.readTree(text);
  }

  private static List<String> diff(String expected, String actual) {
    List<String> out = new ArrayList<>();
    ContentComparator.compare(out, "x", json(expected), json(actual));
    return out;
  }

  @Test
  void elOrdenDeLasClavesNoImporta() {
    assertThat(diff("{\"a\": 1, \"b\": [1, 2]}", "{\"b\": [1, 2], \"a\": 1}")).isEmpty();
  }

  @Test
  void elOrdenDeLasListasSi() {
    assertThat(diff("[1, 2]", "[2, 1]")).containsExactly("x[0]: 1 ≠ 2", "x[1]: 2 ≠ 1");
    assertThat(diff("[1, 2]", "[1]")).containsExactly("x: 2 elementos ≠ 1");
  }

  @Test
  void numerosPorSuValorDecimalExacto() {
    assertThat(diff("41.7197", "41.719700")).isEmpty();
    assertThat(diff("3", "3.0")).isEmpty();
    assertThat(diff("41.7197", "41.72")).hasSize(1);
    assertThat(diff("0.1", "0.10000000000000001")).hasSize(1);
  }

  @Test
  void ausenteNullVacioYListaVaciaSonDistintos() {
    assertThat(diff("{\"a\": null}", "{}")).containsExactly("x.a: falta (null)");
    assertThat(diff("{\"a\": {}}", "{\"a\": []}")).containsExactly("x.a: {} ≠ []");
    assertThat(diff("{\"a\": {}}", "{\"a\": null}")).containsExactly("x.a: {} ≠ null");
    assertThat(diff("{}", "{\"a\": []}")).containsExactly("x.a: sobra ([])");
    assertThat(diff("{\"a\": \"1\"}", "{\"a\": 1}")).containsExactly("x.a: texto \"1\" ≠ número 1");
  }

  @Test
  void textosIdenticosCodigoACodigo() {
    assertThat(diff("\"Cadaqués\"", "\"Cadaques\"")).hasSize(1);
    assertThat(diff("\"a b\"", "\"a  b\"")).hasSize(1);
  }

  @Test
  void archivosQueFaltanOSobran() {
    Content a = new Content(Map.of("tags", json("[]")), Map.of("uno", json("{}")));
    Content b = new Content(Map.of("tags", json("[]")), Map.of("dos", json("{}")));
    assertThat(ContentComparator.compare(a, b)).containsExactly("articles/uno.json: falta", "articles/dos.json: sobra");
  }
}
