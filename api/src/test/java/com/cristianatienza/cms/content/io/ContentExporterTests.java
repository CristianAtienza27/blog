package com.cristianatienza.cms.content.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Destino seguro de la exportación y formato estable. */
class ContentExporterTests {

  @TempDir Path temp;

  private static Content sample() {
    return new Content(
        Map.of("tags", ContentSource.JSON.readTree("[{\"label\": \"Mar\", \"id\": \"mar\", \"slug\": \"mar\"}]")),
        Map.of());
  }

  @Test
  void nuncaDentroDeUnRepositorioGit() {
    // api/target está dentro de este repositorio.
    Path inside = Path.of("target", "exportacion-de-prueba").toAbsolutePath();
    assertThatThrownBy(() -> ContentExporter.prepareTarget(inside))
        .isInstanceOf(ContentException.class).hasMessageContaining("repositorio Git");
    assertThat(inside).doesNotExist();
    assertThatThrownBy(() -> ContentExporter.prepareTarget(Path.of("../src/content")))
        .isInstanceOf(ContentException.class).hasMessageContaining("repositorio Git");
  }

  @Test
  void unaCarpetaConOtrosArchivosNoSeSobrescribe() throws Exception {
    Files.writeString(temp.resolve("notas.txt"), "mío");
    assertThatThrownBy(() -> ContentExporter.prepareTarget(temp))
        .isInstanceOf(ContentException.class).hasMessageContaining("no está vacía");
    assertThat(temp.resolve("notas.txt")).hasContent("mío");
  }

  @Test
  void unaExportacionAnteriorSeSustituyeEntera() throws Exception {
    Path dir = temp.resolve("export");
    ContentExporter.write(sample(), dir);
    Files.writeString(dir.resolve("articles").resolve("viejo.json"), "{}");
    ContentExporter.write(sample(), dir);
    assertThat(dir.resolve("articles").resolve("viejo.json")).doesNotExist();
    // Pero si alguien añadió un archivo ajeno, no se toca nada.
    Files.writeString(dir.resolve("ajeno.txt"), "x");
    assertThatThrownBy(() -> ContentExporter.write(sample(), dir))
        .isInstanceOf(ContentException.class).hasMessageContaining("ajenos");
    assertThat(dir.resolve("ajeno.txt")).exists();
  }

  @Test
  void formatoEstable() throws Exception {
    String text = ContentExporter.JsonText.write(ContentSource.JSON.readTree(
        "{\"a\": 41.719700, \"b\": [], \"c\": {}, \"d\": [1, \"«x»\\n\"], \"e\": 42.000, \"f\": true}"));
    assertThat(text).isEqualTo("""
        {
          "a": 41.7197,
          "b": [],
          "c": {},
          "d": [
            1,
            "«x»\\n"
          ],
          "e": 42,
          "f": true
        }
        """);
    assertThat(text).doesNotContain("\r");
  }

  @Test
  void ordenCanonicoDelContrato() throws Exception {
    Path dir = temp.resolve("canonico");
    Content canonical = new Content(
        Map.of("tags", ContentSource.JSON.createArrayNode().add(
            ContentContract.canonical(sample().collections().get("tags").get(0), ContentContract.TAG))),
        Map.of());
    ContentExporter.write(canonical, dir);
    assertThat(Files.readString(dir.resolve("tags.json")))
        .isEqualTo("[\n  {\n    \"id\": \"mar\",\n    \"slug\": \"mar\",\n    \"label\": \"Mar\"\n  }\n]\n");
  }
}
