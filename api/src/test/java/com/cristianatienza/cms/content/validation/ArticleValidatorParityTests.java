package com.cristianatienza.cms.content.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * Paridad con los validadores TypeScript (fase 6.4.1): el corpus compartido
 * (api/src/test/resources/contract-cases) da aquí exactamente lo mismo que en
 * src/app/content/records/contract-parity.spec.ts. «esperado» es el resultado de los dos; en las
 * divergencias documentadas, Java es más estricto que TS («esperadoTs»).
 */
class ArticleValidatorParityTests {

  private static ParityCorpus corpus;
  private static JsonNode articleCases;
  private static JsonNode richText;
  private static JsonNode reading;

  @BeforeAll
  static void load() {
    corpus = new ParityCorpus();
    articleCases = ParityCorpus.read("articulos.json").get("casos");
    richText = ParityCorpus.read("texto-enriquecido.json");
    reading = ParityCorpus.read("tiempo-de-lectura.json").get("casos");
  }

  @Test
  void losDoceArticulosRealesNoTienenIncidenciasYSuTiempoDeLecturaEsElCalculado() {
    JsonNode articles = corpus.articles();
    assertThat(articles.size()).isEqualTo(12);
    List<String> checked = new ArrayList<>();
    for (JsonNode article : articles.values()) {
      String id = article.get("id").stringValue();
      JsonNode c = ParityCorpus.JSON.createObjectNode().put("nombre", id).put("base", id);
      // Sin recalcular: los minutos guardados deben ser ya los calculados.
      JsonNode exact = ParityCorpus.JSON.createObjectNode().put("nombre", id).put("base", id)
          .set("poner", ParityCorpus.JSON.createObjectNode().set("readingMinutes", article.get("readingMinutes")));
      assertThat(corpus.run(c)).as(id).isEmpty();
      assertThat(corpus.run(exact)).as(id + " (minutos guardados)").isEmpty();
      JsonNode body = corpus.content.bodies().get(article.get("slug").stringValue());
      assertThat(ReadingTime.minutes(body.get("blocks"))).as(id).isEqualTo(article.get("readingMinutes").intValue());
      checked.add(id);
    }
    assertThat(checked).hasSize(12).doesNotHaveDuplicates();
  }

  @TestFactory
  Stream<DynamicTest> articulos() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : articleCases.values()) {
      String name = c.get("nombre").stringValue();
      tests.add(DynamicTest.dynamicTest(name, () -> {
        assertThat(c.has("esperado")).as("falta «esperado»").isTrue();
        assertThat(corpus.run(c)).isEqualTo(ParityCorpus.strings(c.get("esperado")));
      }));
    }
    return tests.stream();
  }

  @Test
  void lasDivergenciasEstanDocumentadasYJavaEsMasEstricto() {
    int divergences = 0;
    for (JsonNode c : articleCases.values()) {
      if (!c.has("esperadoTs")) {
        assertThat(c.has("divergencia")).as(c.get("nombre").stringValue()).isFalse();
        continue;
      }
      divergences++;
      String name = c.get("nombre").stringValue();
      assertThat(c.path("divergencia").asString("")).as(name).isNotBlank();
      List<String> ts = ParityCorpus.strings(c.get("esperadoTs"));
      List<String> java = ParityCorpus.strings(c.get("esperado"));
      assertThat(java).as(name).isNotEqualTo(ts);
      // Más estricto: TS no da ninguna incidencia (o lanza una excepción) y Java sí la da.
      assertThat(ts.isEmpty() || ts.equals(List.of("excepción TypeError"))).as(name).isTrue();
      assertThat(java).as(name).isNotEmpty();
    }
    assertThat(divergences).isEqualTo(5);
  }

  @Test
  void losRegistrosRealesDelCatalogoNoTienenIncidencias() {
    for (String collection : List.of("tags", "authors", "destinations", "trips", "projects")) {
      JsonNode items = corpus.content.collections().get(collection);
      for (int i = 0; i < items.size(); i++) {
        String where = collection + "[" + i + "]";
        assertThat(CatalogRules.record(collection, items.get(i), where)).as(where).isEmpty();
        assertThat(CatalogIntegrity.record(collection, items.get(i), corpus.refs(), where)).as(where).isEmpty();
      }
    }
  }

  @TestFactory
  Stream<DynamicTest> catalogo() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : ParityCorpus.read("catalogo.json").get("casos").values()) {
      tests.add(DynamicTest.dynamicTest(c.get("nombre").stringValue(), () -> {
        assertThat(c.has("esperado")).as("falta «esperado»").isTrue();
        assertThat(corpus.runCatalog(c)).isEqualTo(ParityCorpus.strings(c.get("esperado")));
      }));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> textoEnriquecido() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : richText.get("html").values()) {
      tests.add(DynamicTest.dynamicTest(c.get("nombre").stringValue(), () -> assertThat(
          RichTextPolicy.issues(c.get("html").stringValue())).isEqualTo(ParityCorpus.strings(c.get("esperado")))));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> enlaces() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : richText.get("enlaces").values()) {
      String href = c.get("href").stringValue();
      tests.add(DynamicTest.dynamicTest(ParityCorpus.JSON.writeValueAsString(href), () -> assertThat(
          RichTextPolicy.isAllowedHref(href)).isEqualTo(c.get("esperado").booleanValue())));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> tiempoDeLectura() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : reading.values()) {
      tests.add(DynamicTest.dynamicTest(c.get("nombre").stringValue(), () -> {
        JsonNode blocks = ParityCorpus.readingBlocks(c);
        assertThat(List.of(ReadingTime.countWords(blocks), ReadingTime.minutes(blocks)))
            .isEqualTo(List.of(c.get("esperado").get(0).intValue(), c.get("esperado").get(1).intValue()));
      }));
    }
    return tests.stream();
  }
}
