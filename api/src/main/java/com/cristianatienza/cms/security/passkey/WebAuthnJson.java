package com.cristianatienza.cms.security.passkey;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON de las ceremonias en el formato de los navegadores (PublicKeyCredential.toJSON() y
 * parseCreationOptionsFromJSON / parseRequestOptionsFromJSON), con los módulos Jackson de
 * spring-security-webauthn. Un mapeador propio, para no cambiar el JSON del resto de la API.
 */
final class WebAuthnJson {

  /** Límite del cuerpo de una petición de ceremonia: una respuesta WebAuthn real ocupa pocos KB. */
  static final int MAX_BODY_BYTES = 64 * 1024;

  private final JsonMapper mapper = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
  private final JavaType attestation = mapper.getTypeFactory()
      .constructParametricType(PublicKeyCredential.class, AuthenticatorAttestationResponse.class);
  private final JavaType assertion = mapper.getTypeFactory()
      .constructParametricType(PublicKeyCredential.class, AuthenticatorAssertionResponse.class);

  String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  /** Lee un cuerpo JSON acotado; cualquier fallo es una solicitud no válida. */
  JsonNode readBody(InputStream body) {
    try {
      byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length > MAX_BODY_BYTES) {
        throw new IllegalArgumentException("cuerpo demasiado grande");
      }
      JsonNode node = mapper.readTree(bytes);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("se esperaba un objeto JSON");
      }
      return node;
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("JSON no válido", e);
    }
  }

  PublicKeyCredential<AuthenticatorAttestationResponse> attestation(JsonNode credential) {
    return read(credential, attestation);
  }

  PublicKeyCredential<AuthenticatorAssertionResponse> assertion(JsonNode credential) {
    return read(credential, assertion);
  }

  private <T> T read(JsonNode node, JavaType type) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("falta la credencial");
    }
    try {
      T value = mapper.readerFor(type).readValue(node);
      if (value == null) {
        throw new IllegalArgumentException("credencial vacía");
      }
      return value;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("credencial no válida", e);
    }
  }
}
