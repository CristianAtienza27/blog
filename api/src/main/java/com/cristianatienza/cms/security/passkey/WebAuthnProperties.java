package com.cristianatienza.cms.security.passkey;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración WebAuthn (cms.security.webauthn). Se valida al arrancar: con un RP ID o un origen
 * mal formados la API no arranca. Solo cambia por entorno (local, producción); no hay ningún modo
 * que relaje la verificación de usuario ni las comprobaciones de origen.
 *
 * @param rpId RP ID: un nombre de host, sin esquema ni puerto (localhost, admin.ejemplo.com)
 * @param rpName nombre que muestra el navegador al registrar la passkey
 * @param allowedOrigins orígenes exactos (https, o http solo para localhost); su host es el RP ID
 *     o un subdominio suyo
 * @param challengeTtl vida de un challenge de un solo uso
 * @param stepUpWindow ventana de la verificación reciente con passkey
 * @param maxFailuresPerSession fallos por sesión (passkey, código o step-up) antes de cerrarla
 */
@ConfigurationProperties("cms.security.webauthn")
public record WebAuthnProperties(String rpId, String rpName, List<String> allowedOrigins, Duration challengeTtl,
    Duration stepUpWindow, int maxFailuresPerSession) {

  public WebAuthnProperties {
    if (rpId == null || rpId.isBlank() || !rpId.equals(rpId.toLowerCase(Locale.ROOT))
        || !rpId.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*")) {
      throw new IllegalArgumentException("cms.security.webauthn.rp-id no es un nombre de host válido: " + rpId);
    }
    if (rpName == null || rpName.isBlank()) {
      throw new IllegalArgumentException("cms.security.webauthn.rp-name es obligatorio");
    }
    if (allowedOrigins == null || allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("cms.security.webauthn.allowed-origins es obligatorio");
    }
    for (String origin : allowedOrigins) {
      checkOrigin(rpId, origin);
    }
    allowedOrigins = List.copyOf(allowedOrigins);
    if (challengeTtl == null || challengeTtl.isNegative() || challengeTtl.isZero() || challengeTtl.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException("cms.security.webauthn.challenge-ttl debe estar entre 0 y 10 min");
    }
    if (stepUpWindow == null || stepUpWindow.isNegative() || stepUpWindow.isZero() || stepUpWindow.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("cms.security.webauthn.step-up-window debe estar entre 0 y 15 min");
    }
    if (maxFailuresPerSession < 1 || maxFailuresPerSession > 20) {
      throw new IllegalArgumentException("cms.security.webauthn.max-failures-per-session debe estar entre 1 y 20");
    }
  }

  private static void checkOrigin(String rpId, String origin) {
    URI uri;
    try {
      uri = URI.create(origin);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("origen WebAuthn no válido: " + origin, e);
    }
    String host = uri.getHost();
    boolean local = "localhost".equals(host);
    boolean schemeOk = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local);
    boolean bare = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
        && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
    if (host == null || !schemeOk || !bare || origin.contains("*") || !origin.equals(origin.toLowerCase(Locale.ROOT))
        || !(host.equals(rpId) || host.endsWith("." + rpId))) {
      throw new IllegalArgumentException("origen WebAuthn no permitido para el RP ID " + rpId + ": " + origin
          + " (https, o http solo en localhost; sin ruta ni comodines; host igual al RP ID o subdominio)");
    }
  }
}
