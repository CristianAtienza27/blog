package com.cristianatienza.cms.content.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.content.io.ContentComparator;
import com.cristianatienza.cms.content.io.ContentException;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generación de instantáneas (fase 6.3.1) sin base de datos, con el contenido real de src/content:
 * formato, determinismo, inmutabilidad, rutas peligrosas e interrupciones en cada paso.
 */
class SnapshotWriterTests {

  private static final Path REAL_CONTENT = Path.of("../src/content");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path temp;

  private static Content real() {
    return ContentSource.read(REAL_CONTENT);
  }

  private static Clock at(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }

  private SnapshotWriter writer() {
    return SnapshotWriter.at(temp.resolve("instantaneas"));
  }

  /** Archivos de contenido de una versión (sin el manifiesto) con su hash. */
  private static Map<String, String> contentFiles(Path dir) {
    Map<String, String> files = new TreeMap<>(ContentSource.fingerprint(dir));
    files.remove(SnapshotFormat.MANIFEST);
    return files;
  }

  private static JsonNode manifest(Path dir) throws IOException {
    return JSON.readTree(Files.readAllBytes(dir.resolve(SnapshotFormat.MANIFEST)));
  }

  private static List<String> entries(Path dir) throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  // --- Formato y contrato ------------------------------------------------------------------------

  @Test
  void escribeUnaVersionCompletaConSuManifiesto() throws Exception {
    Content source = real();
    SnapshotWriter writer = SnapshotWriter.at(temp.resolve("instantaneas"), at("2026-11-18T10:15:30.123Z"), (s, t) -> {});
    Snapshot snapshot = writer.write(source, "v1");

    Path v1 = writer.root().resolve("v1");
    assertThat(snapshot.dir()).isEqualTo(v1);
    assertThat(entries(writer.root())).containsExactly("v1");
    // Las 9 colecciones, los 12 cuerpos y el manifiesto; nada más.
    assertThat(contentFiles(v1)).hasSize(21);
    assertThat(snapshot.files()).isEqualTo(21);
    assertThat(snapshot.articles()).isEqualTo(12);
    assertThat(SnapshotVerifier.verify(v1)).isEmpty();
    // Mismo contrato que src/content: 0 diferencias.
    assertThat(ContentComparator.compare(source, ContentSource.read(v1))).isEmpty();

    JsonNode manifest = manifest(v1);
    assertThat(manifest.get("schemaVersion").intValue()).isEqualTo(1);
    assertThat(manifest.get("version").stringValue()).isEqualTo("v1");
    assertThat(manifest.get("createdAt").stringValue()).isEqualTo("2026-11-18T10:15:30Z");
    assertThat(manifest.get("generator").stringValue()).isEqualTo("cms-api/snapshot-1");
    assertThat(manifest.get("articles").intValue()).isEqualTo(12);
    assertThat(manifest.get("contentSha256").stringValue()).isEqualTo(snapshot.contentSha256()).hasSize(64);
    List<String> paths = new ArrayList<>();
    long bytes = 0;
    for (JsonNode file : manifest.get("files").values()) {
      paths.add(file.get("path").stringValue());
      bytes += file.get("bytes").longValue();
      assertThat(file.get("sha256").stringValue())
          .isEqualTo(contentFiles(v1).get(file.get("path").stringValue()));
    }
    assertThat(paths).isSorted().containsExactlyElementsOf(contentFiles(v1).keySet());
    assertThat(bytes).isEqualTo(snapshot.bytes());
    // El contrato interno es exacto: media.json conserva original (la web lo quita al servirlo, 6.3.2).
    assertThat(Files.readString(v1.resolve("media.json"))).contains("\"original\"").contains("\"sha256\"");
  }

  @Test
  void ordenCanonicoYCamposDelContrato() throws Exception {
    Content source = real();
    // Las mismas claves en otro orden: mismos bytes y misma huella (orden canónico del exportador).
    Map<String, JsonNode> collections = new LinkedHashMap<>(source.collections());
    ArrayNode tags = JSON.createArrayNode();
    for (JsonNode tag : source.collections().get("tags").values()) {
      tags.add(JSON.createObjectNode().put("label", tag.get("label").stringValue())
          .put("slug", tag.get("slug").stringValue()).put("id", tag.get("id").stringValue()));
    }
    collections.put("tags", tags);
    SnapshotWriter writer = writer();
    Snapshot a = writer.write(source, "v1");
    Snapshot b = writer.write(new Content(collections, source.bodies()), "v2");
    assertThat(b.contentSha256()).isEqualTo(a.contentSha256());
    assertThat(Files.readString(a.dir().resolve("tags.json")))
        .startsWith("[\n  {\n    \"id\": \"mediterraneo\",\n    \"slug\": \"mediterraneo\",\n");

    // Un campo que el contrato no conoce no se publica.
    ArrayNode unknown = tags.deepCopy();
    ((ObjectNode) unknown.get(0)).put("privado", "x");
    collections.put("tags", unknown);
    assertThatThrownBy(() -> writer.write(new Content(collections, source.bodies()), "v3"))
        .isInstanceOf(ContentException.class).hasMessageContaining("no cumple el contrato");
    assertThat(writer.versions()).containsExactly("v1", "v2");
  }

  // --- Determinismo ------------------------------------------------------------------------------

  @Test
  void mismoEstadoMismaHuellaYMismosBytes() throws Exception {
    Path root = temp.resolve("instantaneas");
    Snapshot first = SnapshotWriter.at(root, at("2026-11-18T10:00:00Z"), (s, t) -> {}).write(real(), "v1");
    Snapshot second = SnapshotWriter.at(root, at("2026-11-19T08:30:00Z"), (s, t) -> {}).write(real(), "v2");

    assertThat(second.contentSha256()).isEqualTo(first.contentSha256());
    assertThat(contentFiles(root.resolve("v2"))).isEqualTo(contentFiles(root.resolve("v1")));
    // Los manifiestos solo difieren en la versión y la fecha.
    ObjectNode m1 = (ObjectNode) manifest(root.resolve("v1"));
    ObjectNode m2 = (ObjectNode) manifest(root.resolve("v2"));
    for (ObjectNode m : List.of(m1, m2)) {
      m.remove("version");
      m.remove("createdAt");
    }
    assertThat(m2).isEqualTo(m1);
  }

  @Test
  void otroEstadoOtraHuella() {
    Content source = real();
    Map<String, JsonNode> collections = new LinkedHashMap<>(source.collections());
    ArrayNode tags = ((ArrayNode) collections.get("tags")).deepCopy();
    ((ObjectNode) tags.get(0)).put("label", "Mediterráneo azul");
    collections.put("tags", tags);
    Content changed = new Content(collections, source.bodies());

    SnapshotWriter writer = writer();
    Snapshot a = writer.write(source, "v1");
    Snapshot b = writer.write(changed, "v2");
    assertThat(b.contentSha256()).isNotEqualTo(a.contentSha256());
    assertThat(contentFiles(b.dir())).containsEntry("articles.json", contentFiles(a.dir()).get("articles.json"));
    assertThat(contentFiles(b.dir()).get("tags.json")).isNotEqualTo(contentFiles(a.dir()).get("tags.json"));
  }

  // --- Inmutabilidad y versiones -----------------------------------------------------------------

  @Test
  void unaVersionExistenteNuncaSeSobrescribe() throws Exception {
    SnapshotWriter writer = writer();
    writer.write(real(), "v1");
    Map<String, String> before = ContentSource.fingerprint(writer.root().resolve("v1"));
    assertThatThrownBy(() -> writer.write(real(), "v1"))
        .isInstanceOf(ContentException.class).hasMessageContaining("ya existe");
    assertThat(ContentSource.fingerprint(writer.root().resolve("v1"))).isEqualTo(before);
    assertThat(entries(writer.root())).containsExactly("v1");
  }

  @Test
  void versionesEnOrdenNumericoYSoloLasValidas() throws Exception {
    SnapshotWriter writer = writer();
    assertThat(writer.versions()).isEmpty();
    assertThat(writer.nextVersion()).isEqualTo("v1");
    for (String name : List.of("v2", "v10", "v9", "estado", "v01", "V3", ".tmp-v11-abc")) {
      Files.createDirectory(writer.root().resolve(name));
    }
    Files.writeString(writer.root().resolve("v12"), "un archivo, no una carpeta");
    assertThat(writer.versions()).containsExactly("v2", "v9", "v10");
    assertThat(writer.nextVersion()).isEqualTo("v11");
  }

  // --- Rutas peligrosas ---------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"", "v0", "v01", "V1", "1", "v-1", "../v1", "v1/..", "v1\\..", "v1/x", "v1.json", "v 1",
      "v1234567890", ".tmp-v1"})
  void versionesNoValidasSeRechazanSinEscribirNada(String version) throws Exception {
    SnapshotWriter writer = writer();
    assertThatThrownBy(() -> writer.write(real(), version))
        .isInstanceOf(ContentException.class).hasMessageContaining("versión no válida");
    assertThat(entries(writer.root())).isEmpty();
    assertThat(entries(temp)).containsExactly("instantaneas");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../fuera", "..", "a/b", "a\\b", "Mayusculas", "con espacio", "", "-guion", "doble--guion"})
  void slugsQueNoSonNombresSegurosSeRechazan(String slug) throws Exception {
    Content source = real();
    Map<String, JsonNode> bodies = new TreeMap<>(source.bodies());
    bodies.put(slug, bodies.values().iterator().next());
    SnapshotWriter writer = writer();
    assertThatThrownBy(() -> writer.write(new Content(source.collections(), bodies), "v1"))
        .isInstanceOf(ContentException.class).hasMessageContaining("slug");
    assertThat(entries(writer.root())).isEmpty();
    assertThat(entries(temp)).containsExactly("instantaneas");
  }

  @Test
  void soloLasColeccionesDelContrato() throws Exception {
    Content source = real();
    Map<String, JsonNode> extra = new LinkedHashMap<>(source.collections());
    extra.put("../secreto", JSON.readTree("[]"));
    Map<String, JsonNode> missing = new LinkedHashMap<>(source.collections());
    missing.remove("media");
    SnapshotWriter writer = writer();
    for (Map<String, JsonNode> collections : List.of(extra, missing)) {
      assertThatThrownBy(() -> writer.write(new Content(collections, source.bodies()), "v1"))
          .isInstanceOf(ContentException.class).hasMessageContaining("contrato");
    }
    assertThat(entries(writer.root())).isEmpty();
  }

  @Test
  void contenidoQueNoCumpleElContratoNoSePublica() throws Exception {
    Content source = real();
    Map<String, JsonNode> bodies = new TreeMap<>(source.bodies());
    bodies.remove(bodies.keySet().iterator().next()); // un artículo sin cuerpo
    SnapshotWriter writer = writer();
    assertThatThrownBy(() -> writer.write(new Content(source.collections(), bodies), "v1"))
        .isInstanceOf(ContentException.class).hasMessageContaining("falta el cuerpo");
    assertThat(entries(writer.root())).isEmpty();
  }

  @Test
  void nuncaDentroDeUnRepositorioGit() {
    Path inside = Path.of("target", "instantaneas-de-prueba").toAbsolutePath();
    assertThatThrownBy(() -> SnapshotWriter.at(inside))
        .isInstanceOf(ContentException.class).hasMessageContaining("repositorio Git");
    assertThat(inside).doesNotExist();
  }

  // --- Interrupciones ----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"file:articles.json", "file:articles/alicante-desde-el-puerto.json", "file:trips.json",
      "manifest", "verified"})
  void unErrorEnCualquierPasoNoDejaNingunaVersion(String failAt) throws Exception {
    SnapshotWriter writer = SnapshotWriter.at(temp.resolve("instantaneas"), Clock.systemUTC(), (step, dir) -> {
      if (step.equals(failAt)) {
        throw new IOException("fallo simulado en " + step);
      }
    });
    assertThatThrownBy(() -> writer.write(real(), "v1")).isInstanceOf(ContentException.class);
    // Ni v1 ni la carpeta temporal: el error la borra.
    assertThat(entries(writer.root())).isEmpty();
    assertThat(writer.versions()).isEmpty();
    // Se puede volver a generar la misma versión.
    writer().write(real(), "v1");
    assertThat(SnapshotVerifier.verify(writer.root().resolve("v1"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"file:categories.json", "file:articles/calas-de-mallorca.json", "manifest", "verified"})
  void unaCaidaDejaSoloUnaTemporalQueNoEsVersionYSeLimpia(String crashAt) throws Exception {
    // Una caída (kill, corte de luz) no ejecuta la limpieza: se reproduce copiando la carpeta
    // temporal tal como está en ese momento, que es lo que quedaría en el disco.
    Path root = temp.resolve("instantaneas");
    SnapshotWriter writer = SnapshotWriter.at(root, Clock.systemUTC(), (step, dir) -> {
      if (step.equals(crashAt)) {
        copyTree(dir, root.resolve(".tmp-v1-caida"));
        throw new IOException("caída simulada en " + step);
      }
    });
    assertThatThrownBy(() -> writer.write(real(), "v1")).isInstanceOf(ContentException.class);

    assertThat(entries(root)).containsExactly(".tmp-v1-caida");
    assertThat(writer.versions()).as("una temporal nunca es una versión").isEmpty();
    assertThat(writer.nextVersion()).isEqualTo("v1");
    assertThat(writer.removeTemporaries()).isEqualTo(1);
    assertThat(entries(root)).isEmpty();
    writer().write(real(), "v1");
    assertThat(SnapshotVerifier.verify(root.resolve("v1"))).isEmpty();
  }

  @Test
  void unaCaidaTrasElRenombradoDejaLaVersionCompleta() throws Exception {
    Path root = temp.resolve("instantaneas");
    SnapshotWriter writer = SnapshotWriter.at(root, Clock.systemUTC(), (step, dir) -> {
      if (step.equals("published")) {
        throw new IOException("caída simulada tras el renombrado");
      }
    });
    assertThatThrownBy(() -> writer.write(real(), "v1")).isInstanceOf(ContentException.class);
    assertThat(entries(root)).containsExactly("v1");
    assertThat(SnapshotVerifier.verify(root.resolve("v1"))).isEmpty();
  }

  // --- Comprobación de una versión --------------------------------------------------------------

  @Test
  void laComprobacionDetectaCualquierAlteracion() throws Exception {
    SnapshotWriter writer = writer();
    writer.write(real(), "v1");
    Path root = writer.root();

    // Un byte cambiado en un cuerpo.
    Path v2 = copyVersion(root, "v1", "v2", true);
    Path body = v2.resolve("articles").resolve("calas-de-mallorca.json");
    Files.writeString(body, Files.readString(body).replaceFirst("\"", "\" "), StandardCharsets.UTF_8);
    assertThat(SnapshotVerifier.verify(v2)).anyMatch(i -> i.contains("hash distinto"));

    // Un archivo de más, uno de menos y una carpeta ajena.
    Path v3 = copyVersion(root, "v1", "v3", true);
    Files.writeString(v3.resolve("notas.json"), "[]");
    assertThat(SnapshotVerifier.verify(v3)).anyMatch(i -> i.contains("no declarado"));
    Path v4 = copyVersion(root, "v1", "v4", true);
    Files.delete(v4.resolve("tags.json"));
    assertThat(SnapshotVerifier.verify(v4)).anyMatch(i -> i.contains("falta el archivo «tags.json»"));
    Path v5 = copyVersion(root, "v1", "v5", true);
    Files.createDirectory(v5.resolve("privado"));
    assertThat(SnapshotVerifier.verify(v5)).anyMatch(i -> i.contains("carpeta no permitida"));

    // Una versión copiada con otro nombre: el manifiesto dice v1.
    Path v6 = copyVersion(root, "v1", "v6", false);
    assertThat(SnapshotVerifier.verify(v6)).containsExactly("la versión del manifiesto no coincide con la carpeta");

    // Un contrato que no se conoce.
    Path v7 = copyVersion(root, "v1", "v7", true);
    ObjectNode manifest = (ObjectNode) manifest(v7);
    manifest.put("schemaVersion", 2);
    Files.writeString(v7.resolve(SnapshotFormat.MANIFEST), JSON.writeValueAsString(manifest));
    assertThat(SnapshotVerifier.verify(v7)).containsExactly("schemaVersion desconocida (se esperaba 1)");

    // Un nombre que no es una versión, y una versión sin manifiesto.
    assertThat(SnapshotVerifier.verify(copyVersion(root, "v1", "v1-copia", false)))
        .containsExactly("el nombre de la carpeta no es una versión válida");
    Path v8 = copyVersion(root, "v1", "v8", true);
    Files.delete(v8.resolve(SnapshotFormat.MANIFEST));
    assertThat(SnapshotVerifier.verify(v8)).containsExactly("falta manifest.json");

    // El original sigue intacto.
    assertThat(SnapshotVerifier.verify(root.resolve("v1"))).isEmpty();
  }

  /** Copia una versión; con {@code renumber}, ajusta el manifiesto a la nueva carpeta. */
  private static Path copyVersion(Path root, String from, String to, boolean renumber) throws IOException {
    Path target = root.resolve(to);
    copyTree(root.resolve(from), target);
    if (renumber) {
      ObjectNode manifest = (ObjectNode) manifest(target);
      manifest.put("version", to);
      Files.writeString(target.resolve(SnapshotFormat.MANIFEST), JSON.writeValueAsString(manifest));
    }
    return target;
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path p : walk.toList()) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }
}
