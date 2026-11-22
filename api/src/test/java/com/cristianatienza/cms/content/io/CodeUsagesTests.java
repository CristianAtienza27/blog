package com.cristianatienza.cms.content.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Lectura estricta de RETIRED_MEDIA y SECTIONS[].preview desde el código de la web. */
class CodeUsagesTests {

  static final Path RETIRED = Path.of("../src/app/editorial/retired-media.ts");
  static final Path NAVIGATION = Path.of("../src/app/layout/navigation.ts");

  @Test
  void archivosReales() {
    CodeUsages.Usages usages = CodeUsages.read(RETIRED, NAVIGATION);
    assertThat(usages.retired())
        .containsExactly("motor-moto-trail", "motor-moto-cuadro", "motor-moto-costa", "tecnologia-teclado");
    assertThat(usages.previews()).containsExactly(
        java.util.Map.entry("viajes", "viajes-cadaques-bahia"),
        java.util.Map.entry("fotografia", "paisaje-seceda-panorama"),
        java.util.Map.entry("motor", "motor-stelvio-bormio"),
        java.util.Map.entry("experiencias", "naturaleza-fanal-niebla"),
        java.util.Map.entry("tecnologia", "tecnologia-codigo-pantalla"));
  }

  @Test
  void retiradasConFormatoAlteradoFallan() {
    assertThatThrownBy(() -> CodeUsages.retired("export const RETIRED: string[] = ['a'];"))
        .isInstanceOf(ContentException.class).hasMessageContaining("no se encuentra");
    assertThatThrownBy(() -> CodeUsages.retired("export const RETIRED_MEDIA: readonly string[] = [\n  'a',\n  RESTO,\n];"))
        .isInstanceOf(ContentException.class).hasMessageContaining("no reconocido");
    assertThatThrownBy(() -> CodeUsages.retired("export const RETIRED_MEDIA: readonly string[] = [\n];"))
        .isInstanceOf(ContentException.class).hasMessageContaining("ninguna");
    assertThatThrownBy(() -> CodeUsages.retired("export const RETIRED_MEDIA: readonly string[] = ['a', 'a'];"))
        .isInstanceOf(ContentException.class).hasMessageContaining("repetidas");
    // Comentarios de línea dentro de la lista: se admiten.
    assertThat(CodeUsages.retired("export const RETIRED_MEDIA: readonly string[] = [\n  'a', // motivo\n  'b',\n];"))
        .containsExactly("a", "b");
  }

  @Test
  void navegacionConFormatoAlteradoFalla() throws Exception {
    String real = Files.readString(NAVIGATION);
    assertThatThrownBy(() -> CodeUsages.previews(real.replace("preview: 'motor-stelvio-bormio',", "")))
        .isInstanceOf(ContentException.class).hasMessageContaining("SECTIONS[2] sin «preview");
    assertThatThrownBy(() -> CodeUsages.previews(real.replace("id: 'motor',", "id: 'coches',")))
        .isInstanceOf(ContentException.class).hasMessageContaining("se esperaba «motor»");
    assertThatThrownBy(() -> CodeUsages.previews(real.replace("export const SECTIONS: readonly NavSection[] = ",
            "export const SECCIONES: readonly NavSection[] = ")))
        .isInstanceOf(ContentException.class).hasMessageContaining("no se encuentra");
    String withoutLast = real.replaceFirst("(?s)  \\{\\s*id: 'tecnologia'.*?\\n  \\},\\n", "");
    assertThatThrownBy(() -> CodeUsages.previews(withoutLast))
        .isInstanceOf(ContentException.class).hasMessageContaining("debe tener 5 secciones");
  }
}
