package com.cristianatienza.cms.content.snapshot;

import com.cristianatienza.cms.content.io.ContentContract;
import com.cristianatienza.cms.content.io.ContentException;
import com.cristianatienza.cms.content.io.ContentSource;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comprobación en Java de una instantánea (fase 6.3.1): el manifiesto, los hashes y la forma del
 * contrato. No sustituye a la puerta autoritativa, que es {@code validateContent()} en la web
 * (6.3.3): esta comprobación solo garantiza que lo escrito es exactamente lo que se generó.
 *
 * <p>Devuelve las incidencias (vacía si es correcta); nunca modifica nada.
 */
public final class SnapshotVerifier {

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  private SnapshotVerifier() {}

  /** Datos del manifiesto de una versión comprobada. */
  public record Summary(String contentSha256, int files, long bytes, int articles) {}

  /**
   * El resumen de una versión, solo si la comprobación completa no encuentra ninguna incidencia
   * (6.3.4: la reconciliación adopta así una carpeta vN cuya fila quedó en «generando»).
   */
  public static java.util.Optional<Summary> summary(Path versionDir) {
    if (!verify(versionDir).isEmpty()) {
      return java.util.Optional.empty();
    }
    try {
      JsonNode manifest = JSON.readTree(Files.readAllBytes(versionDir.resolve(SnapshotFormat.MANIFEST)));
      long bytes = 0;
      for (JsonNode file : manifest.get("files").values()) {
        bytes += file.get("bytes").longValue();
      }
      return java.util.Optional.of(new Summary(manifest.get("contentSha256").stringValue(),
          manifest.get("files").size(), bytes, manifest.get("articles").intValue()));
    } catch (IOException | RuntimeException e) {
      return java.util.Optional.empty();
    }
  }

  /** Comprueba la carpeta de una versión publicada: su nombre es la versión. */
  public static List<String> verify(Path versionDir) {
    String name = versionDir.getFileName().toString();
    if (!SnapshotFormat.isVersion(name)) {
      return List.of("el nombre de la carpeta no es una versión válida");
    }
    return verify(versionDir, name);
  }

  /** Comprueba una carpeta (también la temporal, antes de renombrarla) contra la versión esperada. */
  static List<String> verify(Path dir, String expectedVersion) {
    List<String> issues = new ArrayList<>();
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return List.of("la versión no existe o no es una carpeta");
    }
    Path manifestFile = dir.resolve(SnapshotFormat.MANIFEST);
    if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
      return List.of("falta " + SnapshotFormat.MANIFEST);
    }
    JsonNode manifest;
    try {
      manifest = JSON.readTree(Files.readAllBytes(manifestFile));
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer el manifiesto", e);
    } catch (JacksonException e) {
      return List.of(SnapshotFormat.MANIFEST + ": JSON no válido");
    }

    // Cabecera.
    if (!manifest.path("schemaVersion").isInt() || manifest.get("schemaVersion").intValue() != SnapshotFormat.SCHEMA_VERSION) {
      return List.of("schemaVersion desconocida (se esperaba " + SnapshotFormat.SCHEMA_VERSION + ")");
    }
    if (!expectedVersion.equals(manifest.path("version").asString(null))) {
      issues.add("la versión del manifiesto no coincide con la carpeta");
    }
    if (!manifest.path("createdAt").isString()) {
      issues.add("falta createdAt");
    }

    // Archivos declarados.
    Map<String, JsonNode> declared = new TreeMap<>();
    JsonNode files = manifest.path("files");
    if (!files.isArray() || files.isEmpty()) {
      issues.add("files: se esperaba una lista no vacía");
    } else {
      for (JsonNode entry : files.values()) {
        String path = entry.path("path").asString(null);
        if (path == null || !SnapshotFormat.isContentPath(path)) {
          issues.add("files: ruta no permitida");
        } else if (declared.put(path, entry) != null) {
          issues.add("files: ruta repetida («" + path + "»)");
        }
      }
    }

    // Archivos presentes: solo los declarados, sin enlaces ni otros tipos.
    Set<String> present = new TreeSet<>();
    try {
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
          String rel = relative(dir, d);
          if (!rel.isEmpty() && !rel.equals(SnapshotFormat.ARTICLES_DIR)) {
            issues.add("carpeta no permitida: «" + rel + "»");
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          String rel = relative(dir, file);
          if (!attrs.isRegularFile()) {
            issues.add("no es un archivo normal: «" + rel + "»");
          } else if (!rel.equals(SnapshotFormat.MANIFEST)) {
            present.add(rel);
          }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede recorrer la versión", e);
    }
    for (String path : present) {
      if (!declared.containsKey(path)) {
        issues.add("archivo no declarado en el manifiesto: «" + path + "»");
      }
    }
    for (String path : declared.keySet()) {
      if (!present.contains(path)) {
        issues.add("falta el archivo «" + path + "»");
      }
    }
    for (String collection : ContentContract.COLLECTIONS.keySet()) {
      if (!declared.containsKey(collection + ".json")) {
        issues.add("falta la colección «" + collection + "» en el manifiesto");
      }
    }
    if (!issues.isEmpty()) {
      return issues;
    }

    // Tamaño y hash de cada archivo, y la huella del contenido.
    Map<String, String> hashes = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : declared.entrySet()) {
      Path file = dir.resolve(entry.getKey());
      String sha = SnapshotFormat.sha256(file);
      hashes.put(entry.getKey(), sha);
      long bytes;
      try {
        bytes = Files.size(file);
      } catch (IOException e) {
        throw new UncheckedIOException("no se puede leer «" + entry.getKey() + "»", e);
      }
      if (!entry.getValue().path("bytes").canConvertToLong() || entry.getValue().get("bytes").longValue() != bytes) {
        issues.add("tamaño distinto del manifiesto: «" + entry.getKey() + "»");
      }
      if (!sha.equals(entry.getValue().path("sha256").asString(null))) {
        issues.add("hash distinto del manifiesto: «" + entry.getKey() + "»");
      }
    }
    if (!SnapshotFormat.contentSha256(hashes).equals(manifest.path("contentSha256").asString(null))) {
      issues.add("contentSha256 no coincide con los archivos");
    }
    if (!issues.isEmpty()) {
      return issues;
    }

    // Forma del contrato (la misma comprobación que la importación) y número de artículos.
    try {
      Content content = ContentSource.read(dir);
      issues.addAll(ContentSource.check(content));
      if (!manifest.path("articles").isInt() || manifest.get("articles").intValue() != content.bodies().size()) {
        issues.add("articles no coincide con los cuerpos");
      }
    } catch (ContentException e) {
      issues.add(e.getMessage());
    }
    return issues;
  }

  private static String relative(Path root, Path p) {
    return root.relativize(p).toString().replace('\\', '/');
  }
}
