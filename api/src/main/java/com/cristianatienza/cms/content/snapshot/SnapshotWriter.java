package com.cristianatienza.cms.content.snapshot;

import com.cristianatienza.cms.content.io.ContentContract;
import com.cristianatienza.cms.content.io.ContentException;
import com.cristianatienza.cms.content.io.ContentExporter.JsonText;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Escribe instantáneas inmutables en una carpeta raíz (fase 6.3.1), con el formato de
 * {@link SnapshotFormat}.
 *
 * <p>Cada versión se escribe entera en una carpeta temporal ({@code .tmp-vN-…}): cada archivo con
 * {@code fsync}, el manifiesto al final, se comprueba con {@link SnapshotVerifier} y solo entonces
 * se renombra de forma atómica a {@code vN}. Así una versión es visible completa o no existe: un
 * error deja borrada la temporal y una caída la deja como {@code .tmp-*}, que nunca se lee como
 * versión ({@link #versions()}) y se elimina con {@link #removeTemporaries()}. Una versión que ya
 * existe nunca se sobrescribe.
 *
 * <p>Este escritor no coordina a dos generadores a la vez: el servicio de publicación (6.3.4) lo
 * llama dentro de su bloqueo.
 */
public final class SnapshotWriter {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Punto de interrupción para los tests: se llama tras cada paso con la carpeta temporal. */
  @FunctionalInterface
  interface Step {
    void after(String step, Path temp) throws IOException;
  }

  /** Resultado de una generación. */
  public record Snapshot(String version, Path dir, String contentSha256, int files, long bytes, int articles) {}

  private final Path root;
  private final Clock clock;
  private final Step step;

  private SnapshotWriter(Path root, Clock clock, Step step) {
    this.root = root;
    this.clock = clock;
    this.step = step;
  }

  /** La carpeta raíz debe estar fuera de cualquier repositorio Git; se crea si no existe. */
  public static SnapshotWriter at(Path root) {
    return at(root, Clock.systemUTC(), (s, t) -> {});
  }

  static SnapshotWriter at(Path root, Clock clock, Step step) {
    return new SnapshotWriter(prepareRoot(root), clock, step);
  }

  public Path root() {
    return root;
  }

  // --- Versiones existentes ----------------------------------------------------------------------

  /** Versiones publicadas (carpetas {@code vN}), de la más antigua a la más reciente. */
  public List<String> versions() {
    try (Stream<Path> list = Files.list(root)) {
      return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
          .map(p -> p.getFileName().toString())
          .filter(SnapshotFormat::isVersion)
          .sorted(Comparator.comparingLong(SnapshotFormat::number))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer la carpeta de instantáneas", e);
    }
  }

  /** La siguiente a la más alta existente ({@code v1} si no hay ninguna). */
  public String nextVersion() {
    List<String> versions = versions();
    return "v" + (versions.isEmpty() ? 1 : SnapshotFormat.number(versions.getLast()) + 1);
  }

  /** Borra las carpetas temporales que dejó una generación interrumpida. Devuelve cuántas. */
  public int removeTemporaries() {
    int removed = 0;
    try (Stream<Path> list = Files.list(root)) {
      for (Path p : list.toList()) {
        if (p.getFileName().toString().startsWith(SnapshotFormat.TEMP_PREFIX)) {
          deleteTree(p);
          removed++;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("no se pueden borrar las carpetas temporales", e);
    }
    return removed;
  }

  // --- Generar -----------------------------------------------------------------------------------

  /** Una versión escrita y comprobada en su carpeta temporal, pendiente del renombrado. */
  public record Prepared(String version, Path temp, Path target, Snapshot snapshot) {}

  /** Escribe el contenido como la versión dada. Falla si ya existe o si el resultado no es correcto. */
  public Snapshot write(Content content, String version) {
    Prepared prepared = prepare(content, version);
    try {
      return commit(prepared);
    } catch (RuntimeException e) {
      discard(prepared);
      throw e;
    }
  }

  /**
   * Paso 1 (6.3.4): escribe la versión en una carpeta temporal y la comprueba, sin hacerla visible.
   * El servicio de publicación registra la huella en PostgreSQL y renombra ({@link #commit}) dentro
   * de la misma transacción protegida; si algo falla, {@link #discard}.
   */
  public Prepared prepare(Content content, String version) {
    if (!SnapshotFormat.isVersion(version)) {
      throw new ContentException("versión no válida (se espera v1, v2…)");
    }
    checkNames(content);
    Path target = root.resolve(version);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ContentException("la versión " + version + " ya existe: las instantáneas no se sobrescriben");
    }
    Path temp = root.resolve(SnapshotFormat.TEMP_PREFIX + version + "-" + HexFormat.of().formatHex(randomBytes()));
    try {
      Files.createDirectory(temp);
      Snapshot snapshot = writeInto(temp, content, version, target);
      List<String> issues = SnapshotVerifier.verify(temp, version);
      if (!issues.isEmpty()) {
        throw new ContentException("la instantánea generada no es correcta: " + String.join("; ", issues));
      }
      step.after("verified", temp);
      return new Prepared(version, temp, target, snapshot);
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new ContentException("no se puede escribir la instantánea " + version + ": " + e.getClass().getSimpleName());
    } catch (RuntimeException e) {
      deleteQuietly(temp);
      throw e;
    }
  }

  /** Paso 2: renombrado atómico de la temporal a vN y fsync de la carpeta raíz. Nunca sobrescribe. */
  public Snapshot commit(Prepared prepared) {
    try {
      if (Files.exists(prepared.target(), LinkOption.NOFOLLOW_LINKS)) {
        throw new ContentException("la versión " + prepared.version() + " ya existe: las instantáneas no se sobrescriben");
      }
      Files.move(prepared.temp(), prepared.target(), StandardCopyOption.ATOMIC_MOVE);
      syncDirectory(root);
      step.after("published", prepared.target());
      return prepared.snapshot();
    } catch (IOException e) {
      throw new ContentException("no se puede renombrar la instantánea " + prepared.version() + ": "
          + e.getClass().getSimpleName());
    }
  }

  /** Descarta una versión preparada que no llegó a renombrarse (si ya se renombró, no hace nada). */
  public void discard(Prepared prepared) {
    deleteQuietly(prepared.temp());
  }

  /** La huella que tendría este contenido, sin escribir nada (mismos bytes que {@link #write}). */
  public static String contentSha256(Content content) {
    checkNames(content);
    Map<String, String> hashes = new TreeMap<>();
    render(content).forEach((path, bytes) -> hashes.put(path, SnapshotFormat.sha256(bytes)));
    return SnapshotFormat.contentSha256(hashes);
  }

  /** Los archivos de contenido en orden canónico, con su ruta relativa. */
  private static Map<String, byte[]> render(Content content) {
    Map<String, byte[]> files = new TreeMap<>();
    content.collections().forEach((name, value) -> files.put(name + ".json", bytes(canonicalList(name, value))));
    content.bodies().forEach((slug, body) -> files.put(SnapshotFormat.ARTICLES_DIR + "/" + slug + ".json",
        bytes(canonical(body, ContentContract.BODY))));
    return files;
  }

  private Snapshot writeInto(Path temp, Content content, String version, Path target) throws IOException {
    Files.createDirectory(temp.resolve(SnapshotFormat.ARTICLES_DIR));
    setDirectoryPermissions(temp.resolve(SnapshotFormat.ARTICLES_DIR));
    Map<String, byte[]> files = render(content);

    Map<String, String> hashes = new TreeMap<>();
    ArrayNode entries = NODES.arrayNode();
    long total = 0;
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      Path path = temp.resolve(file.getKey()).normalize();
      if (!path.startsWith(temp)) {
        throw new ContentException("ruta fuera de la instantánea");
      }
      writeSynced(path, file.getValue());
      String sha = SnapshotFormat.sha256(file.getValue());
      hashes.put(file.getKey(), sha);
      entries.add(NODES.objectNode().put("path", file.getKey()).put("bytes", file.getValue().length).put("sha256", sha));
      total += file.getValue().length;
      step.after("file:" + file.getKey(), temp);
    }
    syncDirectory(temp.resolve(SnapshotFormat.ARTICLES_DIR));

    String contentSha256 = SnapshotFormat.contentSha256(hashes);
    ObjectNode manifest = NODES.objectNode()
        .put("schemaVersion", SnapshotFormat.SCHEMA_VERSION)
        .put("version", version)
        .put("createdAt", Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString())
        .put("generator", SnapshotFormat.GENERATOR)
        .put("contentSha256", contentSha256)
        .put("articles", content.bodies().size());
    manifest.set("files", entries);
    writeSynced(temp.resolve(SnapshotFormat.MANIFEST), bytes(manifest));
    step.after("manifest", temp);
    setDirectoryPermissions(temp);
    syncDirectory(temp);
    return new Snapshot(version, target, contentSha256, files.size(), total, content.bodies().size());
  }

  /** Solo las colecciones del contrato y cuerpos con un slug válido: nunca una ruta libre. */
  private static void checkNames(Content content) {
    if (!content.collections().keySet().equals(ContentContract.COLLECTIONS.keySet())) {
      throw new ContentException("las colecciones no son exactamente las del contrato");
    }
    for (String slug : content.bodies().keySet()) {
      if (!SnapshotFormat.SLUG.matcher(slug).matches()) {
        throw new ContentException("slug de artículo no válido para un nombre de archivo");
      }
    }
  }

  /**
   * Orden canónico del contrato (el del exportador): la huella depende solo del contenido, no del
   * orden de las claves de quien lo entregue.
   */
  private static JsonNode canonicalList(String collection, JsonNode list) {
    if (!list.isArray()) {
      throw new ContentException(collection + ": se esperaba una lista");
    }
    ArrayNode out = NODES.arrayNode();
    for (JsonNode item : list.values()) {
      out.add(canonical(item, ContentContract.COLLECTIONS.get(collection)));
    }
    return out;
  }

  private static JsonNode canonical(JsonNode value, ContentContract.Shape shape) {
    try {
      return ContentContract.canonical(value, shape);
    } catch (IllegalArgumentException e) {
      throw new ContentException("el contenido no cumple el contrato: " + e.getMessage());
    }
  }

  private static byte[] bytes(JsonNode value) {
    return JsonText.write(value).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  // --- Sistema de archivos -----------------------------------------------------------------------

  private static Path prepareRoot(Path root) {
    Path dir = root.toAbsolutePath().normalize();
    try {
      Path existing = dir;
      while (existing != null && !Files.exists(existing)) {
        existing = existing.getParent();
      }
      for (Path p = existing == null ? null : existing.toRealPath(); p != null; p = p.getParent()) {
        if (Files.exists(p.resolve(".git"))) {
          throw new ContentException("la carpeta de instantáneas no puede estar dentro de un repositorio Git");
        }
      }
      Files.createDirectories(dir);
      if (!Files.isDirectory(dir)) {
        throw new ContentException("la carpeta de instantáneas no es una carpeta");
      }
      return dir.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede preparar la carpeta de instantáneas", e);
    }
  }

  /** Crea el archivo (nunca sobrescribe), escribe todo y fuerza los datos al disco. */
  private static void writeSynced(Path file, byte[] bytes) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    setFilePermissions(file);
  }

  /**
   * fsync de una carpeta, para que el renombrado o los archivos nuevos sobrevivan a un corte de
   * luz. Funciona en Linux (el contenedor); Windows no permite abrir una carpeta así y se omite.
   */
  private static void syncDirectory(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      // Sin soporte en este sistema (Windows): el renombrado sigue siendo atómico.
    }
  }

  /** Permisos explícitos donde hay POSIX (0644 archivos, 0755 carpetas), sin depender del umask. */
  private static void setFilePermissions(Path file) throws IOException {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    }
  }

  private static void setDirectoryPermissions(Path dir) throws IOException {
    if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  /** Borra un árbol sin seguir enlaces (un enlace se borra a sí mismo, nunca su destino). */
  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(dir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        Files.delete(d);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void deleteQuietly(Path dir) {
    try {
      deleteTree(dir);
    } catch (IOException | UncheckedIOException e) {
      // Queda como .tmp-*: nunca se lee como versión y removeTemporaries() la elimina.
    }
  }
}
