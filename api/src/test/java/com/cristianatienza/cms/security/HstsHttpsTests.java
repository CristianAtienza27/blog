package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * HSTS con una petición HTTPS REAL (G4): Tomcat con TLS y un certificado autofirmado generado al
 * vuelo (keytool del JDK). Un año, sin includeSubDomains y sin preload.
 */
class HstsHttpsTests extends SecurityTestSupport {

  private static final Path DIR;
  private static final String STORE_PASSWORD = "almacen-de-pruebas-" + UUID.randomUUID();

  static {
    try {
      DIR = Files.createTempDirectory("cms-tls-");
      Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
          "-genkeypair", "-alias", "pruebas", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
          "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
          "-keystore", DIR.resolve("tls.p12").toString(), "-storepass", STORE_PASSWORD).redirectErrorStream(true).start();
      keytool.getInputStream().readAllBytes();
      if (keytool.waitFor() != 0) {
        throw new IllegalStateException("keytool falló");
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void tls(DynamicPropertyRegistry registry) {
    registry.add("server.ssl.enabled", () -> "true");
    registry.add("server.ssl.key-store", () -> DIR.resolve("tls.p12").toUri().toString());
    registry.add("server.ssl.key-store-type", () -> "PKCS12");
    registry.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
    registry.add("server.ssl.key-alias", () -> "pruebas");
  }

  @Test
  void conHttpsRealSeEnviaHsts() throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(DIR.resolve("tls.p12").toFile())) {
      store.load(in, STORE_PASSWORD.toCharArray());
    }
    TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, trust.getTrustManagers(), null);
    HttpClient https = HttpClient.newBuilder().sslContext(ssl).build();
    HttpResponse<String> response = https.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port
        + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Strict-Transport-Security")).containsExactly("max-age=31536000");
    // También en un error.
    HttpResponse<String> error = https.send(HttpRequest.newBuilder(URI.create("https://localhost:" + port
        + "/api/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(error.statusCode()).isEqualTo(401);
    assertThat(error.headers().allValues("Strict-Transport-Security")).containsExactly("max-age=31536000");
  }
}
