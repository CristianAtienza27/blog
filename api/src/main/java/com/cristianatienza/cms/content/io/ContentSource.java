package com.cristianatienza.cms.content.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lectura del contenido en el formato de src/content: nueve colecciones y un cuerpo por
 * artículo. Solo lee (nunca abre un archivo para escribir), con los números como BigDecimal y
 * rechazando claves duplicadas, que en JSON se perderían en silencio.
 */
public final class ContentSource {

  /** Lector compartido: números exactos, claves duplicadas y basura final rechazadas. */
  static final JsonMapper JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  /** Contenido leído: colecciones (en el orden del contrato) y cuerpos por slug (ordenados). */
  public record Content(Map<String, JsonNode> collections, Map<String, JsonNode> bodies) {}

  private ContentSource() {}

  /** Lee una carpeta con la estructura de src/content. */
  public static Content read(Path root) {
    Map<String, JsonNode> collections = new LinkedHashMap<>();
    for (String name : ContentContract.COLLECTIONS.keySet()) {
      collections.put(name, readJson(root.resolve(name + ".json")));
    }
    Map<String, JsonNode> bodies = new TreeMap<>();
    Path articles = root.resolve("articles");
    try (Stream<Path> files = Files.list(articles)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) {
          throw new ContentException("articles/" + name + ": solo se admiten archivos .json");
        }
        bodies.put(name.substring(0, name.length() - ".json".length()), readJson(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + articles, e);
    }
    return new Content(collections, bodies);
  }

  static JsonNode readJson(Path file) {
    try {
      return JSON.readTree(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + file, e);
    } catch (tools.jackson.core.JacksonException e) {
      throw new ContentException(file.getFileName() + ": JSON no válido: " + e.getOriginalMessage());
    }
  }

  /**
   * Incidencias de forma del contenido (vacía si cumple el contrato): cada colección y cada
   * cuerpo según ContentContract, y la correspondencia exacta entre cuerpos y artículos.
   */
  public static List<String> check(Content content) {
    List<String> issues = new ArrayList<>();
    content.collections().forEach((name, value) -> {
      if (!value.isArray()) {
        issues.add(name + ": se esperaba una lista");
        return;
      }
      for (int i = 0; i < value.size(); i++) {
        issues.addAll(ContentContract.check(value.get(i), ContentContract.COLLECTIONS.get(name), name + "[" + i + "]"));
      }
    });
    List<String> slugs = new ArrayList<>();
    JsonNode articles = content.collections().get("articles");
    if (articles != null && articles.isArray()) {
      for (JsonNode article : articles.values()) {
        if (article.path("slug").isString()) {
          slugs.add(article.get("slug").stringValue());
        }
      }
    }
    content.bodies().forEach((file, body) -> {
      String where = "articles/" + file + ".json";
      issues.addAll(ContentContract.check(body, ContentContract.BODY, where));
      if (body.path("slug").isString() && !body.get("slug").stringValue().equals(file)) {
        issues.add(where + ": slug «" + body.get("slug").stringValue() + "» distinto del archivo");
      }
      if (!slugs.contains(file)) {
        issues.add(where + ": cuerpo sin artículo");
      }
    });
    for (String slug : slugs) {
      if (!content.bodies().containsKey(slug)) {
        issues.add("articles/" + slug + ".json: falta el cuerpo del artículo");
      }
    }
    return issues;
  }

  /** SHA-256 de cada archivo de una carpeta (rutas relativas, ordenadas): para probar que no cambia. */
  public static Map<String, String> fingerprint(Path root) {
    Map<String, String> hashes = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        hashes.put(root.relativize(file).toString().replace('\\', '/'), sha256(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + root, e);
    }
    return hashes;
  }

  static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (IOException e) {
      throw new UncheckedIOException("no se puede leer " + file, e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
