package com.cristianatienza.cms.content.io;

import static com.cristianatienza.cms.content.io.ImportFixtures.FULL_FIXTURE;
import static com.cristianatienza.cms.content.io.ImportFixtures.NAVIGATION_TS;
import static com.cristianatienza.cms.content.io.ImportFixtures.REAL_CONTENT;
import static com.cristianatienza.cms.content.io.ImportFixtures.RETIRED_TS;
import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.content.io.ContentImporter.Outcome;
import com.cristianatienza.cms.content.io.ContentImporter.Result;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.schema.PostgresSchemaTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ida y vuelta con PostgreSQL 18.6 real: importar (como cms_importer, con un usuario real),
 * exportar fuera del repositorio y comparar. El contenido real de src/content y el sintético
 * con todo el contrato vuelven sin ninguna diferencia.
 */
class ContentRoundTripTests extends PostgresSchemaTest {

  @TempDir Path temp;

  private static ContentImporter importer() {
    return new ContentImporter(dataSourceAs(IMPORTER_LOGIN));
  }

  @Test
  void contenidoRealSinNingunaDiferencia() throws Exception {
    Map<String, String> before = ContentSource.fingerprint(REAL_CONTENT);
    String retiredBefore = ContentSource.sha256(RETIRED_TS);
    String navigationBefore = ContentSource.sha256(NAVIGATION_TS);

    Content source = ContentSource.read(REAL_CONTENT);
    Result result = importer().importContent(source, ImportFixtures.realUsages());
    assertThat(result.outcome()).isEqualTo(Outcome.IMPORTED);
    assertThat(result.tableCounts()).containsAllEntriesOf(Map.ofEntries(
        Map.entry("category", 5L), Map.entry("tag", 17L), Map.entry("author", 1L), Map.entry("media", 22L),
        Map.entry("media_original", 22L), Map.entry("media_reserved_use", 5L), Map.entry("gallery", 1L),
        Map.entry("gallery_item", 2L), Map.entry("destination", 13L), Map.entry("trip", 2L),
        Map.entry("trip_stop", 7L), Map.entry("project", 1L), Map.entry("project_stack_item", 4L),
        Map.entry("article", 12L), Map.entry("article_revision", 12L), Map.entry("article_revision_tag", 29L),
        Map.entry("article_revision_destination", 6L)));

    Path first = temp.resolve("primera");
    Path second = temp.resolve("segunda");
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), first);
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), second);

    // 0 diferencias con el origen en las 9 colecciones y los 12 cuerpos.
    assertThat(ContentComparator.compare(source, ContentSource.read(first))).isEmpty();
    assertThat(ContentSource.read(first).bodies()).hasSize(12);
    // Dos exportaciones seguidas: idénticas byte a byte.
    assertThat(ContentSource.fingerprint(second)).isEqualTo(ContentSource.fingerprint(first)).hasSize(22);
    // El origen no ha cambiado.
    assertThat(ContentSource.fingerprint(REAL_CONTENT)).isEqualTo(before).hasSize(21);
    assertThat(ContentSource.sha256(RETIRED_TS)).isEqualTo(retiredBefore);
    assertThat(ContentSource.sha256(NAVIGATION_TS)).isEqualTo(navigationBefore);
  }

  @Test
  void segundaImportacionSinEscrituras() throws Exception {
    Content source = ContentSource.read(REAL_CONTENT);
    Result first = importer().importContent(source, ImportFixtures.realUsages());
    Result second = importer().importContent(source, ImportFixtures.realUsages());
    assertThat(second.outcome()).isEqualTo(Outcome.UNCHANGED);
    assertThat(second.wrote()).as("la segunda importación no escribe nada").isFalse();
    assertThat(second.tableCounts()).isEqualTo(first.tableCounts());
    assertThat(one(Integer.class, "SELECT max(number) FROM article_revision")).isEqualTo(1);
  }

  @Test
  void imagenesRetiradasVistasPreviasRevisionesYDemo() throws Exception {
    Content source = ContentSource.read(REAL_CONTENT);
    importer().importContent(source, ImportFixtures.realUsages());
    assertThat(column("SELECT id FROM media WHERE retired ORDER BY catalog_position"))
        .containsExactly("motor-moto-trail", "motor-moto-cuadro", "motor-moto-costa", "tecnologia-teclado");
    assertThat(column("SELECT use_key || '=' || media_id FROM media_reserved_use ORDER BY use_key")).containsExactly(
        "navigation.preview.experiencias=naturaleza-fanal-niebla",
        "navigation.preview.fotografia=paisaje-seceda-panorama",
        "navigation.preview.motor=motor-stelvio-bormio",
        "navigation.preview.tecnologia=tecnologia-codigo-pantalla",
        "navigation.preview.viajes=viajes-cadaques-bahia");
    // Una sola revisión por artículo, importada, sin padre, de trabajo y publicada a la vez.
    assertThat(one(Long.class, "SELECT count(*) FROM article a JOIN article_revision r ON r.article_id = a.id"
        + " WHERE r.number = 1 AND r.origin = 'import' AND r.parent_revision_id IS NULL"
        + " AND a.head_revision_id = r.id AND a.published_revision_id = r.id")).isEqualTo(12L);
    // demo, registro a registro (además de la ida y vuelta completa).
    for (String collection : new String[] {"media", "galleries", "destinations", "trips", "projects", "articles"}) {
      String table = Map.of("media", "media", "galleries", "gallery", "destinations", "destination", "trips", "trip",
          "projects", "project", "articles", "article").get(collection);
      StringBuilder expected = new StringBuilder();
      source.collections().get(collection).values()
          .forEach(item -> expected.append(item.get("id").stringValue()).append('=').append(item.get("demo")).append(';'));
      assertThat(one(String.class, "SELECT string_agg(id || '=' || demo, ';' ORDER BY catalog_position) || ';' FROM "
          + table)).as(collection).isEqualTo(expected.toString());
    }
  }

  @Test
  void contratoCompletoSinNingunaDiferencia() throws Exception {
    Content source = ContentSource.read(FULL_FIXTURE);
    assertThat(ContentSource.check(source)).isEmpty();
    importer().importContent(source, ImportFixtures.fixtureUsages());
    Path out = temp.resolve("completo");
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), out);
    assertThat(ContentComparator.compare(source, ContentSource.read(out))).isEmpty();
    // Casos explícitos: objetos y listas vacíos, y la ausencia, tal cual.
    String media = Files.readString(out.resolve("media.json"));
    assertThat(media).contains("\"camera\": {}").contains("\"focalLength\": 6.765");
    String articles = Files.readString(out.resolve("articles.json"));
    assertThat(articles).contains("\"seo\": {}").contains("\"destinations\": []").contains("\"tags\": []");
    assertThat(Files.readString(out.resolve("projects.json"))).contains("\"stack\": []");
    assertThat(column("SELECT id FROM media WHERE retired")).containsExactly("f-retirada");
  }

  @Test
  void laExportacionSoloContieneLoQueEscribeElExportador() throws Exception {
    importer().importContent(ContentSource.read(REAL_CONTENT), ImportFixtures.realUsages());
    Path out = temp.resolve("export");
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), out);
    try (Stream<Path> files = Files.walk(out)) {
      assertThat(files.filter(Files::isRegularFile).map(p -> out.relativize(p).toString().replace('\\', '/')))
          .hasSize(22)
          .contains(ContentExporter.MARKER, "media.json", "articles/paso-del-stelvio.json");
    }
  }
}
