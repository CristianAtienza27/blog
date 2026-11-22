package com.cristianatienza.cms.content.publication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lectura de estado/activa.json (lo escribe la web; la API solo lo lee). Mismo formato que valida la
 * web (6.3.3), con el campo {@code epoch} opcional de la 6.3.4. Sin seguir enlaces y con un tamaño
 * máximo: un archivo dañado o extraño se trata como inválido, nunca se inventa una versión.
 */
final class ActivePointer {

  static final String FILE = "activa.json";
  private static final int MAX_BYTES = 4096;
  private static final Pattern VERSION = Pattern.compile("v([1-9][0-9]{0,8})");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Resultado de la lectura. */
  sealed interface Read permits Missing, Invalid, Valid {}

  record Missing() implements Read {}

  record Invalid(String reason) implements Read {}

  record Valid(int version, String contentSha256, Integer previous, long epoch) implements Read {}

  private ActivePointer() {}

  static Read read(Path stateDir) {
    Path file = stateDir.resolve(FILE);
    byte[] bytes;
    try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      if (channel.size() > MAX_BYTES) {
        return new Invalid("demasiado grande");
      }
      ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
        // leer entero
      }
      bytes = buffer.array();
    } catch (NoSuchFileException e) {
      return new Missing();
    } catch (IOException e) {
      return new Invalid("no se puede leer (" + e.getClass().getSimpleName() + ")");
    }
    JsonNode value;
    try {
      value = JSON.readTree(bytes);
    } catch (JacksonException e) {
      return new Invalid("JSON no válido");
    }
    if (!value.isObject() || !value.path("schemaVersion").isInt() || value.get("schemaVersion").intValue() != 1) {
      return new Invalid("formato no válido");
    }
    Integer version = number(value.path("version"));
    String sha = value.path("contentSha256").isString() ? value.get("contentSha256").stringValue() : null;
    JsonNode previous = value.path("previous");
    Integer previousVersion = previous.isNull() ? null : number(previous);
    JsonNode epoch = value.path("epoch");
    boolean epochOk = epoch.isMissingNode() || (epoch.isIntegralNumber() && epoch.canConvertToLong() && epoch.longValue() >= 0);
    if (version == null || sha == null || !SHA256.matcher(sha).matches() || !value.path("activatedAt").isString()
        || (!previous.isNull() && previousVersion == null) || !epochOk) {
      return new Invalid("formato no válido");
    }
    return new Valid(version, sha, previousVersion, epoch.isMissingNode() ? 0 : epoch.longValue());
  }

  /** «vN» → N, o null si no es una versión válida. */
  private static Integer number(JsonNode node) {
    if (!node.isString()) {
      return null;
    }
    var matcher = VERSION.matcher(node.stringValue());
    return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
  }

  /** La época de activa.json si es válido (mínimo para la época nueva), o 0. */
  static long epochFloor(Read read) {
    return read instanceof Valid valid ? valid.epoch() : 0;
  }
}
