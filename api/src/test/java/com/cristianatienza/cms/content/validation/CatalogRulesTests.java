package com.cristianatienza.cms.content.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.ContentContract;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * La regla de la etiqueta (fase 6.4.6) no se desfasa: mismos campos, orden y obligatoriedad que
 * ContentContract (comparado con validate.ts en ContentContractTests), y los mensajes de las
 * primitivas de validate.ts, con la semántica de JS (espacios Unicode, recorte a 60).
 */
class CatalogRulesTests {

  @Test
  void camposDeCadaColeccionComoEnContentContract() {
    for (String collection : List.of("tags", "authors", "destinations", "trips", "projects")) {
      List<String> java = CatalogRules.FIELDS.get(collection).entrySet().stream()
          .map(e -> e.getKey() + (e.getValue().optional() ? "?" : ""))
          .toList();
      List<String> contract = ContentContract.COLLECTIONS.get(collection).fields().stream()
          .map(f -> f.name() + (f.required() ? "" : "?"))
          .toList();
      assertThat(java).as(collection).containsExactlyElementsOf(contract);
    }
  }

  @Test
  void constantesComoEnElModeloTypeScript() throws Exception {
    assertThat(CatalogRules.TRAVEL_MODES).isEqualTo(tsArray("../src/app/content/models/travel.ts", "TRAVEL_MODES"));
    assertThat(CatalogRules.PROJECT_STATUSES)
        .isEqualTo(tsArray("../src/app/content/models/project.ts", "PROJECT_STATUSES"));
  }

  private static List<String> tsArray(String file, String name) throws Exception {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("export const " + name + " = \\[([^\\]]*)\\]")
        .matcher(java.nio.file.Files.readString(java.nio.file.Path.of(file)));
    assertThat(m.find()).as(name).isTrue();
    List<String> values = new java.util.ArrayList<>();
    java.util.regex.Matcher item = java.util.regex.Pattern.compile("'([^']*)'").matcher(m.group(1));
    while (item.find()) {
      values.add(item.group(1));
    }
    return values;
  }

  @Test
  void camposComoEnContentContract() {
    List<String> java = CatalogRules.TAG_FIELDS.entrySet().stream()
        .map(e -> e.getKey() + (e.getValue().optional() ? "?" : ""))
        .toList();
    List<String> contract = ContentContract.COLLECTIONS.get("tags").fields().stream()
        .map(f -> f.name() + (f.required() ? "" : "?"))
        .toList();
    assertThat(java).containsExactlyElementsOf(contract);
  }

  @Test
  void mensajesDeValidateTs() {
    String spaces = new String(new char[] {0xA0, 0x3000, 0xFEFF});
    var tag = ParityCorpus.JSON.createObjectNode().put("id", "costa").put("slug", "Costa Brava").put("label", spaces);
    tag.put("color", "azul");
    assertThat(CatalogRules.tag(tag, "tags[0]").stream().map(Issue::toString).toList()).containsExactly(
        "tags[0].slug: slug no válido: «Costa Brava»",
        "tags[0].label: se esperaba un texto no vacío",
        "tags[0].color: campo desconocido");
    var valid = ParityCorpus.JSON.createObjectNode().put("id", "costa").put("slug", "costa").put("label", "Costa");
    assertThat(CatalogRules.tag(valid, "tags[0]")).isEmpty();
    // U+0085 no es espacio en JS: un texto así no está vacío.
    var nel = ParityCorpus.JSON.createObjectNode().put("id", "a").put("slug", "a").put("label", String.valueOf((char) 0x85));
    assertThat(CatalogRules.tag(nel, "t")).isEmpty();
  }
}
