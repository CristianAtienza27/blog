package com.cristianatienza.cms.content.snapshot;

import com.cristianatienza.cms.content.io.ContentContract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Formato de una instantánea publicada (fase 6.3): {@code <raíz>/v{N}/} con el contrato exacto de
 * src/content (las nueve colecciones y {@code articles/<slug>.json}) y un {@code manifest.json}:
 *
 * <pre>
 * {
 *   "schemaVersion": 1,             // versión del contrato; la web rechaza la que no conoce
 *   "version": "v12",               // igual que el nombre de la carpeta
 *   "createdAt": "2026-11-18T10:00:00Z",
 *   "generator": "cms-api/snapshot-1",
 *   "contentSha256": "…",           // huella del contenido: mismo estado, misma huella
 *   "articles": 12,
 *   "files": [{ "path": "articles.json", "bytes": 1234, "sha256": "…" }, …]  // ordenados por ruta
 * }
 * </pre>
 *
 * <p>{@code contentSha256} es el SHA-256 de las líneas {@code "<ruta> <sha256>\n"} de los archivos de
 * contenido, ordenadas por ruta (sin el manifiesto). No depende de la versión ni de la fecha, así
 * que dos instantáneas del mismo estado tienen la misma huella aunque sus manifiestos difieran.
 */
public final class SnapshotFormat {

  public static final int SCHEMA_VERSION = 1;
  public static final String GENERATOR = "cms-api/snapshot-" + SCHEMA_VERSION;
  public static final String MANIFEST = "manifest.json";
  public static final String ARTICLES_DIR = "articles";

  /** {@code v1} … {@code v999999999}: sin ceros a la izquierda, sin separadores ni rutas. */
  public static final Pattern VERSION = Pattern.compile("v[1-9][0-9]{0,8}");
  /** Slug de un artículo: el mismo formato que el contrato (minúsculas, cifras y guiones simples). */
  public static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  /** Carpetas temporales de generación: nunca se leen como versión. */
  static final String TEMP_PREFIX = ".tmp-";

  private SnapshotFormat() {}

  public static boolean isVersion(String name) {
    return name != null && VERSION.matcher(name).matches();
  }

  public static long number(String version) {
    if (!isVersion(version)) {
      throw new IllegalArgumentException("versión no válida");
    }
    return Long.parseLong(version.substring(1));
  }

  /** Ruta relativa de un archivo de contenido permitido (colección del contrato o cuerpo de artículo). */
  static boolean isContentPath(String path) {
    if (path.startsWith(ARTICLES_DIR + "/")) {
      String file = path.substring(ARTICLES_DIR.length() + 1);
      return file.endsWith(".json") && SLUG.matcher(file.substring(0, file.length() - ".json".length())).matches();
    }
    return path.endsWith(".json")
        && ContentContract.COLLECTIONS.containsKey(path.substring(0, path.length() - ".json".length()));
  }

  /** Huella del contenido a partir del hash de cada archivo (ordenados por ruta). */
  static String contentSha256(Map<String, String> sortedHashes) {
    StringBuilder lines = new StringBuilder();
    sortedHashes.forEach((path, sha) -> lines.append(path).append(' ').append(sha).append('\n'));
    return sha256(lines.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(Path file) {
    try {
      return sha256(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + file.getFileName(), e);
    }
  }
}
