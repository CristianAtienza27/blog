package com.cristianatienza.cms.content.io;

import static com.cristianatienza.cms.content.io.ImportFixtures.REAL_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.content.snapshot.SnapshotFormat;
import com.cristianatienza.cms.content.snapshot.SnapshotVerifier;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter.Snapshot;
import com.cristianatienza.cms.schema.PostgresSchemaTest;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Instantáneas generadas desde PostgreSQL 18.6 real (fase 6.3.1), con el contenido real importado
 * como cms_tool: el contrato sin diferencias, la huella estable, solo lo publicado (nunca un
 * borrador) y la orden {@code generar-instantanea} completa.
 */
@ExtendWith(OutputCaptureExtension.class)
class SnapshotGenerationTests extends PostgresSchemaTest {

  @TempDir Path temp;

  private static Content importReal() {
    Content source = ContentSource.read(REAL_CONTENT);
    new ContentImporter(dataSourceAs(IMPORTER_LOGIN)).importContent(source, ImportFixtures.realUsages());
    return source;
  }

  private static Map<String, String> contentFiles(Path dir) {
    Map<String, String> files = new TreeMap<>(ContentSource.fingerprint(dir));
    files.remove(SnapshotFormat.MANIFEST);
    files.remove(ContentExporter.MARKER);
    return files;
  }

  @Test
  void desdeLaBaseDeDatosSinDiferenciasYConLaMismaHuella() throws Exception {
    Content source = importReal();
    Map<String, String> sourceBefore = ContentSource.fingerprint(REAL_CONTENT);
    SnapshotWriter writer = SnapshotWriter.at(temp.resolve("instantaneas"));

    Snapshot v1 = writer.write(ContentExporter.readPublished(dataSourceAs(IMPORTER_LOGIN)), "v1");
    Snapshot v2 = writer.write(ContentExporter.readPublished(dataSourceAs(IMPORTER_LOGIN)), "v2");

    assertThat(SnapshotVerifier.verify(v1.dir())).isEmpty();
    assertThat(SnapshotVerifier.verify(v2.dir())).isEmpty();
    // 0 diferencias con src/content: 9 colecciones y 12 cuerpos.
    assertThat(ContentComparator.compare(source, ContentSource.read(v1.dir()))).isEmpty();
    assertThat(v1.articles()).isEqualTo(12);
    // Dos generaciones del mismo estado: misma huella y mismos bytes.
    assertThat(v2.contentSha256()).isEqualTo(v1.contentSha256());
    assertThat(contentFiles(v2.dir())).isEqualTo(contentFiles(v1.dir())).hasSize(21);
    // Byte a byte igual que la exportación (6.1.3) del mismo estado.
    Path export = temp.resolve("export");
    ContentExporter.export(dataSourceAs(IMPORTER_LOGIN), export);
    assertThat(contentFiles(v1.dir())).isEqualTo(contentFiles(export));
    // Y la huella es la misma que la de src/content escrito por el mismo generador.
    assertThat(SnapshotWriter.at(temp.resolve("desde-src")).write(source, "v1").contentSha256())
        .isEqualTo(v1.contentSha256());
    // El origen no ha cambiado.
    assertThat(ContentSource.fingerprint(REAL_CONTENT)).isEqualTo(sourceBefore);
  }

  @Test
  void soloLoPublicadoNuncaUnBorrador() throws Exception {
    importReal();
    SnapshotWriter writer = SnapshotWriter.at(temp.resolve("instantaneas"));
    Snapshot before = writer.write(ContentExporter.readPublished(dataSourceAs(IMPORTER_LOGIN)), "v1");

    // Un borrador: una revisión nueva (cabeza) sin publicar, creada por la API como en el editor.
    String article = one(String.class, "SELECT id FROM article ORDER BY catalog_position LIMIT 1");
    long head = one(Long.class, "SELECT head_revision_id FROM article WHERE id = ?", article);
    String draft = one(String.class,
        "SELECT jsonb_set(article_revision_content(?), '{title}', '\"Borrador sin publicar\"')::text", head);
    asApp(createRevisionSql(article, head, draft));
    assertThat(one(Boolean.class, "SELECT head_revision_id <> published_revision_id FROM article WHERE id = ?", article))
        .isTrue();

    Snapshot after = writer.write(ContentExporter.readPublished(dataSourceAs(IMPORTER_LOGIN)), "v2");
    assertThat(after.contentSha256()).isEqualTo(before.contentSha256());
    try (Stream<Path> files = Files.walk(after.dir())) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        assertThat(Files.readString(file)).doesNotContain("Borrador sin publicar");
      }
    }
  }

  // --- La orden completa, como en el contenedor content-tool ---------------------------------------

  private static int tool(String... args) {
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CmsApplication.class)
        .web(WebApplicationType.NONE)
        .properties("CMS_DB_URL=" + TestDatabase.url(), "CMS_DB_USER=" + IMPORTER_LOGIN,
            "CMS_DB_PASSWORD=" + TestDatabase.password(IMPORTER_LOGIN), "cms.tool.system-exit=false")
        .run(args)) {
      return context.getBean(ContentTool.class).lastExitCode();
    }
  }

  @Test
  void ordenGenerarInstantanea(CapturedOutput output) throws Exception {
    importReal();
    Path root = temp.resolve("instantaneas");
    String dir = "--cms.snapshots-dir=" + root;

    assertThat(tool("--cms.tool=generar-instantanea", dir)).isZero();
    String expected = SnapshotWriter.at(temp.resolve("referencia"))
        .write(ContentExporter.readPublished(dataSourceAs(IMPORTER_LOGIN)), "v1").contentSha256();
    assertThat(output.getOut()).contains("Instantánea v1 escrita en").contains("21 archivos (12 artículos")
        .contains("contentSha256: " + expected);

    // La siguiente versión, y una temporal de una generación interrumpida se limpia antes.
    Files.createDirectory(root.resolve(".tmp-v2-caida"));
    assertThat(tool("--cms.tool=generar-instantanea", dir)).isZero();
    assertThat(output.getOut()).contains("Carpetas temporales de generaciones interrumpidas eliminadas: 1")
        .contains("Instantánea v2 escrita en");
    assertThat(SnapshotWriter.at(root).versions()).containsExactly("v1", "v2");
    assertThat(root.resolve(".tmp-v2-caida")).doesNotExist();

    // Una versión existente no se sobrescribe y una no válida se rechaza: código 1, sin cambios.
    Map<String, String> v1 = ContentSource.fingerprint(root.resolve("v1"));
    assertThat(tool("--cms.tool=generar-instantanea", dir, "--cms.snapshot-version=v1")).isEqualTo(1);
    assertThat(output.getOut()).contains("la versión v1 ya existe");
    assertThat(tool("--cms.tool=generar-instantanea", dir, "--cms.snapshot-version=../v9")).isEqualTo(1);
    assertThat(ContentSource.fingerprint(root.resolve("v1"))).isEqualTo(v1);
    assertThat(SnapshotWriter.at(root).versions()).containsExactly("v1", "v2");
    // Sin carpeta: error claro.
    assertThat(tool("--cms.tool=generar-instantanea")).isEqualTo(1);
    assertThat(output.getOut()).contains("falta --cms.snapshots-dir");
  }
}
