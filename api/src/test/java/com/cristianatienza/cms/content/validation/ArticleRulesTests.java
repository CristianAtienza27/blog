package com.cristianatienza.cms.content.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.ContentContract;
import com.cristianatienza.cms.content.validation.Rules.Rule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Las reglas Java no se desfasan de sus fuentes (fase 6.4.1): las constantes, frente a los archivos
 * TypeScript de los que salen; los campos del artículo y de cada bloque, frente a ContentContract
 * (que ContentContractTests ya compara con validate.ts): mismos nombres, mismo orden y mismos
 * obligatorios y opcionales.
 */
class ArticleRulesTests {

  private static final Path MODELS = Path.of("../src/app/content/models");

  private static List<String> tsArray(Path file, String name) throws Exception {
    String source = Files.readString(file);
    // Array literal o «new Set([...])» (ALLOWED_TAGS), con o sin anotación de tipo.
    Matcher m = Pattern.compile("export const " + name + "(?::[^=]+)? = (?:new Set\\()?\\[([^\\]]*)\\]", Pattern.DOTALL)
        .matcher(source);
    assertThat(m.find()).as(name + " en " + file).isTrue();
    List<String> values = new ArrayList<>();
    Matcher item = Pattern.compile("'([^']*)'").matcher(m.group(1));
    while (item.find()) {
      values.add(item.group(1));
    }
    return values;
  }

  private static String tsString(Path file, String name) throws Exception {
    Matcher m = Pattern.compile("export const " + name + " = '([^']*)'").matcher(Files.readString(file));
    assertThat(m.find()).as(name).isTrue();
    return m.group(1);
  }

  @Test
  void constantesIgualesALasDelModeloTypeScript() throws Exception {
    assertThat(ArticleRules.SECTION_IDS).isEqualTo(tsArray(MODELS.resolve("taxonomy.ts"), "SECTION_IDS"));
    assertThat(ArticleRules.ARTICLE_FORMATS).isEqualTo(tsArray(MODELS.resolve("article.ts"), "ARTICLE_FORMATS"));
    assertThat(ArticleRules.IMAGE_RATIOS).isEqualTo(tsArray(MODELS.resolve("content-block.ts"), "IMAGE_RATIOS"));
    assertThat(ArticleRules.IMAGE_ALIGNS).isEqualTo(tsArray(MODELS.resolve("content-block.ts"), "IMAGE_ALIGNS"));
    assertThat(ArticleRules.FIGURES_LAYOUTS).isEqualTo(tsArray(MODELS.resolve("content-block.ts"), "FIGURES_LAYOUTS"));
    assertThat(ArticleRules.SPECS_ID_PREFIX).isEqualTo(tsString(MODELS.resolve("content-block.ts"), "SPECS_ID_PREFIX"));
    assertThat(ArticleRules.RESERVED_IDS)
        .isEqualTo(tsArray(Path.of("../src/app/content/records/integrity.ts"), "RESERVED_IDS"));
    assertThat(RichTextPolicy.ALLOWED_TAGS).containsExactlyInAnyOrderElementsOf(
        tsArray(Path.of("../src/app/content/rich-text.ts"), "ALLOWED_TAGS"));
    Matcher wpm = Pattern.compile("export const WORDS_PER_MINUTE = (\\d+);")
        .matcher(Files.readString(Path.of("../src/app/content/records/reading-time.ts")));
    assertThat(wpm.find()).isTrue();
    assertThat(ReadingTime.WORDS_PER_MINUTE).isEqualTo(Integer.parseInt(wpm.group(1)));
  }

  @Test
  void camposDelArticuloComoEnContentContract() {
    assertSameFields("article", ArticleRules.ARTICLE_FIELDS, ContentContract.COLLECTIONS.get("articles"));
  }

  @Test
  void camposDeCadaBloqueComoEnContentContract() {
    assertThat(ArticleRules.BLOCK_FIELDS.keySet()).containsExactlyElementsOf(ContentContract.BLOCKS.keySet());
    for (Map.Entry<String, Map<String, Rule>> block : ArticleRules.BLOCK_FIELDS.entrySet()) {
      assertSameFields(block.getKey(), block.getValue(), ContentContract.BLOCKS.get(block.getKey()));
    }
  }

  private static void assertSameFields(String name, Map<String, Rule> rules, ContentContract.Shape shape) {
    List<String> java = rules.entrySet().stream()
        .map(e -> e.getKey() + (e.getValue().optional() ? "?" : ""))
        .toList();
    List<String> contract = shape.fields().stream()
        .map(f -> f.name() + (f.required() ? "" : "?"))
        .toList();
    assertThat(java).as(name).containsExactlyElementsOf(contract);
  }
}
