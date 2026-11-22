package com.cristianatienza.cms.content.io;

import static com.cristianatienza.cms.content.io.ImportFixtures.FULL_FIXTURE;
import static com.cristianatienza.cms.content.io.ImportFixtures.REAL_CONTENT;
import static com.cristianatienza.cms.content.io.ImportFixtures.copy;
import static com.cristianatienza.cms.content.io.ImportFixtures.replace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.content.io.ContentImporter.ContentDifferenceException;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.schema.PostgresSchemaTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rechazos antes de escribir, reversión completa, cambios detectados y permisos del importador. */
class ContentImporterTests extends PostgresSchemaTest {

  @TempDir Path temp;

  private static ContentImporter importer() {
    return new ContentImporter(dataSourceAs(IMPORTER_LOGIN));
  }

  private static void assertDatabaseEmpty() throws Exception {
    for (String table : ContentImporter.TABLES) {
      assertThat(one(Long.class, "SELECT count(*) FROM " + table)).as(table).isZero();
    }
  }

  private Path fixtureCopy() throws Exception {
    return copy(FULL_FIXTURE, temp.resolve("copia"));
  }

  // --- Cambios detectados, sin sincronización -----------------------------------------------------

  @Test
  void unOrigenModificadoSeDetieneSinEscribir() throws Exception {
    importer().importContent(ContentSource.read(REAL_CONTENT), ImportFixtures.realUsages());
    Map<String, Long> before = counts();
    Path changed = copy(REAL_CONTENT, temp.resolve("cambiado"));
    replace(changed.resolve("tags.json"), "\"label\": \"Costa\"", "\"label\": \"Costa Brava\"");

    assertThatThrownBy(() -> importer().importContent(ContentSource.read(changed), ImportFixtures.realUsages()))
        .isInstanceOf(ContentDifferenceException.class)
        .hasMessageContaining("tags[")
        .hasMessageContaining("\"Costa Brava\" ≠ \"Costa\"");
    assertThat(counts()).isEqualTo(before);
    Path out = temp.resolve("export");
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), out);
    assertThat(ContentComparator.compare(ContentSource.read(REAL_CONTENT), ContentSource.read(out))).isEmpty();
  }

  @Test
  void otrasImagenesRetiradasEnElCodigoTambienSeDetectan() throws Exception {
    Content real = ContentSource.read(REAL_CONTENT);
    importer().importContent(real, ImportFixtures.realUsages());
    CodeUsages.Usages changed = new CodeUsages.Usages(List.of("motor-moto-trail"), ImportFixtures.realUsages().previews());
    assertThatThrownBy(() -> importer().importContent(real, changed))
        .isInstanceOf(ContentDifferenceException.class).hasMessageContaining("media.retired");
  }

  // --- Reversión completa ----------------------------------------------------------------------------

  @Test
  void unErrorEnElUltimoArticuloNoDejaNada() throws Exception {
    Path dir = fixtureCopy();
    // El último artículo usa la imagen retirada: lo detiene la base de datos al final de todo.
    replace(dir.resolve("articles/minimo.json"), "{ \"type\": \"text\", \"html\": \"<p>Mínimo</p>\" }",
        "{ \"type\": \"image\", \"media\": \"f-retirada\", \"layout\": \"wide\" }");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("revertido por completo")
        .hasMessageContaining("f-retirada");
    assertDatabaseEmpty();
  }

  @Test
  void unaReferenciaDelCatalogoAUnaRetiradaTampocoDejaNada() throws Exception {
    Path dir = fixtureCopy();
    replace(dir.resolve("galleries.json"), "\"media\": \"f-propia\"", "\"media\": \"f-retirada\"");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class).hasMessageContaining("f-retirada");
    assertDatabaseEmpty();
  }

  // --- Rechazos antes de escribir ----------------------------------------------------------------------

  @Test
  void nullSegunElContratoConSuRuta() throws Exception {
    Path dir = fixtureCopy();
    replace(dir.resolve("media.json"), "\"caption\": \"Zahara de la Sierra\"", "\"caption\": null");
    replace(dir.resolve("articles/completo.json"), "\"cite\": \"Alguien\"", "\"cite\": null");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("media[0].caption: null no admitido por el contrato")
        .hasMessageContaining("articles/completo.json.blocks[5].cite: null no admitido por el contrato");
    assertDatabaseEmpty();
  }

  @Test
  void camposDesconocidosObligatoriosYCuerposSinArticulo() throws Exception {
    Path dir = fixtureCopy();
    replace(dir.resolve("tags.json"), "\"label\": \"Mar\"", "\"etiqueta\": \"Mar\"");
    Files.writeString(dir.resolve("articles/huerfano.json"), "{ \"slug\": \"huerfano\", \"blocks\": [] }");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("tags[1].label: campo obligatorio")
        .hasMessageContaining("tags[1].etiqueta: campo desconocido")
        .hasMessageContaining("articles/huerfano.json: cuerpo sin artículo");
    assertDatabaseEmpty();
  }

  @Test
  void coordenadasConMasDecimalesDeLosAdmitidos() throws Exception {
    Path dir = fixtureCopy();
    replace(dir.resolve("destinations.json"), "\"lat\": 46.528472", "\"lat\": 46.5284721");
    replace(dir.resolve("media.json"), "\"lat\": 36.84", "\"lat\": 36.841");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("destinations[0].location.lat: 46.5284721 tiene 7 decimales")
        .hasMessageContaining("media[0].location.point.lat: 36.841 tiene 3 decimales");
    assertDatabaseEmpty();
  }

  @Test
  void fechasYClavesDuplicadas() throws Exception {
    Path dir = fixtureCopy();
    replace(dir.resolve("media.json"), "\"takenAt\": \"2026-05-01\"", "\"takenAt\": \"2026-5-1\"");
    assertThatThrownBy(() -> importer().importContent(ContentSource.read(dir), ImportFixtures.fixtureUsages()))
        .isInstanceOf(ContentException.class).hasMessageContaining("media[0].takenAt: se esperaba una fecha AAAA-MM-DD");
    Path dup = temp.resolve("duplicada");
    copy(FULL_FIXTURE, dup);
    replace(dup.resolve("tags.json"), "\"label\": \"Mar\"", "\"label\": \"Mar\", \"label\": \"Otro\"");
    assertThatThrownBy(() -> ContentSource.read(dup)).isInstanceOf(ContentException.class).hasMessageContaining("tags.json");
    assertDatabaseEmpty();
  }

  @Test
  void vistasPreviasInexistentesORetiradas() throws Exception {
    Content content = ContentSource.read(FULL_FIXTURE);
    Map<String, String> previews = new LinkedHashMap<>(ImportFixtures.fixtureUsages().previews());
    previews.put("motor", "no-existe");
    previews.put("tecnologia", "f-retirada");
    assertThatThrownBy(() -> importer().importContent(content, new CodeUsages.Usages(List.of("f-retirada"), previews)))
        .isInstanceOf(ContentException.class)
        .hasMessageContaining("SECTIONS.motor.preview: «no-existe» no está en media.json")
        .hasMessageContaining("SECTIONS.tecnologia.preview: «f-retirada» está retirada");
    assertThatThrownBy(() -> importer().importContent(content,
            new CodeUsages.Usages(List.of("no-existe"), ImportFixtures.fixtureUsages().previews())))
        .isInstanceOf(ContentException.class).hasMessageContaining("RETIRED_MEDIA: «no-existe» no está en media.json");
    assertDatabaseEmpty();
  }

  // --- Solo el importador autorizado ----------------------------------------------------------------

  @Test
  void sinPertenecerACmsImporterNoSeImportaNada() throws Exception {
    Content content = ContentSource.read(FULL_FIXTURE);
    for (String user : List.of(APP_LOGIN, NOBODY_LOGIN)) {
      assertThatThrownBy(() -> new ContentImporter(dataSourceAs(user)).importContent(content, ImportFixtures.fixtureUsages()))
          .as(user).isInstanceOf(ContentException.class).hasMessageContaining("permission denied");
    }
    assertDatabaseEmpty();
  }

  private static Map<String, Long> counts() throws Exception {
    Map<String, Long> out = new LinkedHashMap<>();
    for (String table : ContentImporter.TABLES) {
      out.put(table, one(Long.class, "SELECT count(*) FROM " + table));
    }
    return out;
  }
}
