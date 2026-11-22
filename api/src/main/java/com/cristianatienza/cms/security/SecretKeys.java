package com.cristianatienza.cms.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Claves HMAC de la API (fase 6.2.3), derivadas de un único secreto de archivo
 * (/run/secrets/cms.security.hmac-key, propiedad cms.security.hmac-key). Una clave por uso, para
 * que un valor calculado para un fin no sirva para otro:
 *
 * <ul>
 *   <li>códigos de recuperación: la base de datos solo guarda su HMAC;
 *   <li>huella de la sesión: los challenges se ligan a la sesión sin copiar su identificador;
 *   <li>claves de la limitación de intentos (IP, correo tecleado y combinaciones) y el correo
 *       tecleado en la auditoría cuando el usuario no existe (6.2.4);
 *   <li>tokens de dispositivo de confianza y huellas del origen (IP, agente de usuario) (6.2.4).
 * </ul>
 *
 * Sin el secreto (o con menos de 32 bytes) la API no arranca. Cambiarlo invalida los códigos de
 * recuperación vigentes (hay que generar otros) y los challenges en curso.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecretKeys {

  static final int MIN_SECRET_BYTES = 32;

  private final byte[] recoveryCodeKey;
  private final byte[] sessionHashKey;
  private final byte[] attemptKey;
  private final byte[] deviceKey;
  private final byte[] originKey;

  public SecretKeys(@Value("${cms.security.hmac-key:}") String secret) {
    byte[] master = secret.strip().getBytes(StandardCharsets.UTF_8);
    if (master.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException("cms.security.hmac-key: falta el secreto HMAC de la API o tiene menos de "
          + MIN_SECRET_BYTES + " bytes (infra/local/secrets/README.md)");
    }
    this.recoveryCodeKey = hmac(master, "cms/recovery-code/v1".getBytes(StandardCharsets.US_ASCII));
    this.sessionHashKey = hmac(master, "cms/session-hash/v1".getBytes(StandardCharsets.US_ASCII));
    this.attemptKey = hmac(master, "cms/attempt-key/v1".getBytes(StandardCharsets.US_ASCII));
    this.deviceKey = hmac(master, "cms/device-token/v1".getBytes(StandardCharsets.US_ASCII));
    this.originKey = hmac(master, "cms/origin/v1".getBytes(StandardCharsets.US_ASCII));
  }

  /** Clave de un contador de intentos: ámbito y valor (IP, correo normalizado, combinaciones). */
  public byte[] attemptKey(String scope, String value) {
    return hmac(attemptKey, (scope + "|" + value).getBytes(StandardCharsets.UTF_8));
  }

  /** HMAC de un token de dispositivo de confianza. */
  public byte[] deviceToken(String token) {
    return hmac(deviceKey, token.getBytes(StandardCharsets.US_ASCII));
  }

  /** Huella de un dato del origen (IP o agente de usuario). */
  public byte[] origin(String kind, String value) {
    return hmac(originKey, (kind + "|" + value).getBytes(StandardCharsets.UTF_8));
  }

  /** HMAC de un código de recuperación ya normalizado. */
  public byte[] recoveryCode(String normalizedCode) {
    return hmac(recoveryCodeKey, normalizedCode.getBytes(StandardCharsets.US_ASCII));
  }

  /** Huella de un identificador de sesión. */
  public byte[] sessionHash(String sessionId) {
    return hmac(sessionHashKey, sessionId.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA-256 no disponible", e);
    }
  }
}
