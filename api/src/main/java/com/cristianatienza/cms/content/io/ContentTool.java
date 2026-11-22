package com.cristianatienza.cms.content.io;

import com.cristianatienza.cms.content.io.ContentImporter.ContentDifferenceException;
import com.cristianatienza.cms.content.io.ContentImporter.Result;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter.Snapshot;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Herramienta de línea de comandos (no hay endpoints HTTP de contenido). Solo se activa con
 * {@code --cms.tool=…}; conviene arrancarla sin servidor web
 * ({@code --spring.main.web-application-type=none}).
 *
 * <pre>
 *   --cms.tool=import   importa cms.content-dir (con cms.retired-file y cms.navigation-file)
 *   --cms.tool=export   exporta la base de datos a cms.export-dir (fuera del repositorio)
 *   --cms.tool=compare  compara cms.content-dir con cms.export-dir
 *   --cms.tool=generar-instantanea
 *                       genera una instantánea del estado publicado en cms.snapshots-dir (fase
 *                       6.3.1, para pruebas: la siguiente versión, o cms.snapshot-version si se da)
 *   --cms.tool=migrate  solo con el perfil «migrate» (contenedor de migraciones): Flyway ya ha
 *                       migrado al arrancar; informa de la versión y sale
 * </pre>
 *
 * Código de salida: 0 correcto o sin cambios; 2 diferencias; 1 error.
 */
@Component
// Como @ConditionalOnProperty(name = "cms.tool"), salvo las dos órdenes de la herramienta de
// seguridad (fase 6.2.6, H2), que atiende SecurityTool, y las de publicación (6.3.4, PublicationTool).
@ConditionalOnExpression("'${cms.tool:false}' != 'false' and '${cms.tool:false}' != 'crear-admin'"
    + " and '${cms.tool:false}' != 'recuperacion-emergencia' and '${cms.tool:false}' != 'publicar'"
    + " and '${cms.tool:false}' != 'activar' and '${cms.tool:false}' != 'purgar-instantaneas'")
public class ContentTool implements ApplicationRunner {

  private final DataSource dataSource;
  private final ConfigurableApplicationContext context;
  private final String tool;
  private final String contentDir;
  private final String retiredFile;
  private final String navigationFile;
  private final String exportDir;
  private final String snapshotsDir;
  private final String snapshotVersion;
  private final ObjectProvider<Flyway> flyway;
  private final boolean systemExit;

  public ContentTool(
      DataSource dataSource,
      ConfigurableApplicationContext context,
      @Value("${cms.tool}") String tool,
      @Value("${cms.content-dir:}") String contentDir,
      @Value("${cms.retired-file:}") String retiredFile,
      @Value("${cms.navigation-file:}") String navigationFile,
      @Value("${cms.export-dir:}") String exportDir,
      @Value("${cms.snapshots-dir:}") String snapshotsDir,
      @Value("${cms.snapshot-version:}") String snapshotVersion,
      ObjectProvider<Flyway> flyway,
      @Value("${cms.tool.system-exit:true}") boolean systemExit) {
    this.dataSource = dataSource;
    this.context = context;
    this.tool = tool;
    this.contentDir = contentDir;
    this.retiredFile = retiredFile;
    this.navigationFile = navigationFile;
    this.exportDir = exportDir;
    this.snapshotsDir = snapshotsDir;
    this.snapshotVersion = snapshotVersion;
    this.flyway = flyway;
    this.systemExit = systemExit;
  }

  /** Código de salida de la última ejecución (para los tests, con cms.tool.system-exit=false). */
  private volatile int lastExitCode = -1;

  public int lastExitCode() {
    return lastExitCode;
  }

  @Override
  public void run(ApplicationArguments args) {
    PrintStream out = System.out;
    int code;
    try {
      code = switch (tool) {
        case "import" -> importContent(out);
        case "export" -> export(out);
        case "compare" -> compare(out);
        case "migrate" -> migrated(out);
        case "generar-instantanea" -> snapshot(out);
        default -> throw new ContentException("herramienta desconocida: «" + tool
            + "» (import, export, compare, migrate o generar-instantanea)");
      };
    } catch (ContentDifferenceException e) {
      out.println("La base de datos no coincide con el origen; no se ha escrito nada (sin sincronización):");
      e.issues().forEach(issue -> out.println("  - " + issue));
      code = 2;
    } catch (ContentException e) {
      out.println("Error: " + e.getMessage());
      code = 1;
    }
    int exitCode = code;
    lastExitCode = exitCode;
    if (systemExit) {
      System.exit(SpringApplication.exit(context, () -> exitCode));
    }
  }

  /** Flyway ya ha migrado al crearse el contexto (perfil migrate); aquí solo se informa. */
  private int migrated(PrintStream out) {
    Flyway migrations = flyway.getIfAvailable();
    if (migrations == null) {
      throw new ContentException("Flyway está desactivado: las migraciones solo se ejecutan con el perfil «migrate»");
    }
    var current = migrations.info().current();
    out.println("Migraciones aplicadas. Versión del esquema: " + (current == null ? "vacío" : current.getVersion())
        + " · pendientes: " + migrations.info().pending().length);
    return migrations.info().pending().length == 0 ? 0 : 1;
  }

  private int importContent(PrintStream out) {
    Content content = ContentSource.read(path("cms.content-dir", contentDir));
    CodeUsages.Usages usages = CodeUsages.read(path("cms.retired-file", retiredFile), path("cms.navigation-file", navigationFile));
    Map<String, String> before = ContentSource.fingerprint(path("cms.content-dir", contentDir));
    Result result = new ContentImporter(dataSource).importContent(content, usages);
    if (!before.equals(ContentSource.fingerprint(path("cms.content-dir", contentDir)))) {
      throw new ContentException("el origen ha cambiado durante la importación");
    }
    out.println(result.outcome() == ContentImporter.Outcome.IMPORTED
        ? "Importación completa."
        : "Sin cambios: la base de datos ya coincide con el origen (" + (result.wrote() ? "ATENCIÓN: hubo escrituras" : "ninguna escritura") + ").");
    out.println("Imágenes retiradas: " + usages.retired());
    out.println("Vistas previas del menú: " + usages.previews());
    result.tableCounts().forEach((table, count) -> out.printf("  %-30s %d%n", table, count));
    return 0;
  }

  private int export(PrintStream out) {
    Path target = path("cms.export-dir", exportDir);
    Content content = ContentExporter.export(dataSource, target);
    out.println("Exportación escrita en " + target.toAbsolutePath() + ": " + content.collections().size()
        + " colecciones y " + content.bodies().size() + " cuerpos.");
    return 0;
  }

  private int snapshot(PrintStream out) {
    SnapshotWriter writer = SnapshotWriter.at(path("cms.snapshots-dir", snapshotsDir));
    int temporaries = writer.removeTemporaries();
    if (temporaries > 0) {
      out.println("Carpetas temporales de generaciones interrumpidas eliminadas: " + temporaries);
    }
    Content content = ContentExporter.readPublished(dataSource);
    String version = snapshotVersion == null || snapshotVersion.isBlank() ? writer.nextVersion() : snapshotVersion;
    Snapshot snapshot = writer.write(content, version);
    out.println("Instantánea " + snapshot.version() + " escrita en " + snapshot.dir() + ": " + snapshot.files()
        + " archivos (" + snapshot.articles() + " artículos, " + snapshot.bytes() + " bytes).");
    out.println("contentSha256: " + snapshot.contentSha256());
    return 0;
  }

  private int compare(PrintStream out) {
    Content expected = ContentSource.read(path("cms.content-dir", contentDir));
    Content actual = ContentSource.read(path("cms.export-dir", exportDir));
    List<String> differences = ContentComparator.compare(expected, actual);
    if (differences.isEmpty()) {
      out.println("Sin diferencias: " + expected.collections().size() + " colecciones y " + expected.bodies().size()
          + " cuerpos idénticos en su contenido.");
      return 0;
    }
    out.println(differences.size() + " diferencias:");
    differences.forEach(d -> out.println("  - " + d));
    return 2;
  }

  private static Path path(String property, String value) {
    if (value == null || value.isBlank()) {
      throw new ContentException("falta --" + property);
    }
    return Path.of(value);
  }
}
