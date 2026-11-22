package com.cristianatienza.cms.content.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Rutas y utilidades de los tests de importación. */
final class ImportFixtures {

  /** El contenido real de la web (solo lectura). */
  static final Path REAL_CONTENT = Path.of("../src/content");
  static final Path RETIRED_TS = Path.of("../src/app/editorial/retired-media.ts");
  static final Path NAVIGATION_TS = Path.of("../src/app/layout/navigation.ts");
  /** Contenido sintético con todo el contrato. */
  static final Path FULL_FIXTURE = Path.of("src/test/resources/content-fixtures/completo");

  private ImportFixtures() {}

  static CodeUsages.Usages realUsages() {
    return CodeUsages.read(RETIRED_TS, NAVIGATION_TS);
  }

  /** Usos del contenido sintético: una imagen retirada y la misma vista previa en las 5 secciones. */
  static CodeUsages.Usages fixtureUsages() {
    Map<String, String> previews = new LinkedHashMap<>();
    CodeUsages.SECTION_IDS.forEach(section -> previews.put(section, "f-menu"));
    return new CodeUsages.Usages(List.of("f-retirada"), previews);
  }

  /** Copia una carpeta de contenido (para modificarla sin tocar el original). */
  static Path copy(Path source, Path target) throws IOException {
    try (Stream<Path> files = Files.walk(source)) {
      for (Path file : files.toList()) {
        Path dest = target.resolve(source.relativize(file).toString());
        if (Files.isDirectory(file)) {
          Files.createDirectories(dest);
        } else {
          Files.copy(file, dest);
        }
      }
    }
    return target;
  }

  /** Sustituye un texto en un archivo de la copia; falla si no aparece (el test no sería válido). */
  static void replace(Path file, String from, String to) throws IOException {
    String text = Files.readString(file);
    if (!text.contains(from)) {
      throw new IllegalStateException("«" + from + "» no aparece en " + file);
    }
    Files.writeString(file, text.replaceFirst(java.util.regex.Pattern.quote(from),
        java.util.regex.Matcher.quoteReplacement(to)));
  }
}
