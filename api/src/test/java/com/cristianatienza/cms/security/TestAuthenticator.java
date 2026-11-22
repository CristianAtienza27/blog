package com.cristianatienza.cms.security;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Autenticador de pruebas (detalle f del plan): una clave EC P-256 y una credencial, con control
 * total sobre lo que firma: origen, RP ID, flags (UP, UV, BE, BS), contador, tipo, challenge,
 * crossOrigin y la propia firma. Produce el JSON de PublicKeyCredential.toJSON() de los navegadores,
 * con atestación «none». Se construye con los conversores de webauthn4j (dependencia de la API).
 */
final class TestAuthenticator {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ObjectConverter CONVERTER = new ObjectConverter();

  final KeyPair keys;
  final byte[] credentialId;
  byte[] userHandle;

  String origin = "http://localhost:4200";
  String rpId = "localhost";
  boolean up = true;
  boolean uv = true;
  boolean backupEligible = false;
  boolean backupState = false;
  Boolean crossOrigin;
  String typeOverride;
  /** Contador de firmas: 0 = sin contador (como muchas passkeys sincronizadas). */
  long counter;
  /** Si es true, cada aserción sube el contador en 1 antes de firmar. */
  boolean countUp;
  boolean tamperSignature;
  byte[] challengeOverride;
  boolean sendUserHandle = true;

  TestAuthenticator() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      this.keys = generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    this.credentialId = new byte[32];
    RANDOM.nextBytes(credentialId);
  }

  private TestAuthenticator(TestAuthenticator original) {
    this.keys = original.keys;
    this.credentialId = original.credentialId.clone();
    this.userHandle = original.userHandle;
    this.origin = original.origin;
    this.rpId = original.rpId;
    this.backupEligible = original.backupEligible;
    this.backupState = original.backupState;
    this.counter = original.counter;
    this.countUp = original.countUp;
  }

  /** Un clon con la misma clave privada (para simular una credencial copiada). */
  TestAuthenticator cloneKey() {
    return new TestAuthenticator(this);
  }

  static TestAuthenticator withCounter(long counter) {
    TestAuthenticator a = new TestAuthenticator();
    a.counter = counter;
    a.countUp = true;
    return a;
  }

  /** Cuerpo de POST /api/auth/passkeys/registration a partir de las opciones del servidor. */
  String register(String optionsJson, String label) {
    JsonNode options = JSON.readTree(optionsJson);
    userHandle = B64D.decode(options.path("user").path("id").asString());
    byte[] clientData = clientData("webauthn.create", challenge(options));
    byte[] authData = authenticatorData(true);
    Map<String, Object> attestation = new LinkedHashMap<>();
    attestation.put("fmt", "none");
    attestation.put("attStmt", Map.of());
    attestation.put("authData", authData);
    byte[] attestationObject = CONVERTER.getCborConverter().writeValueAsBytes(attestation);
    ObjectNode response = JSON.createObjectNode();
    response.put("clientDataJSON", B64.encodeToString(clientData));
    response.put("attestationObject", B64.encodeToString(attestationObject));
    response.putArray("transports").add("internal");
    ObjectNode body = JSON.createObjectNode();
    body.set("credential", credential(response));
    if (label != null) {
      body.put("label", label);
    }
    return JSON.writeValueAsString(body);
  }

  /** Cuerpo de una aserción (autenticación o step-up) a partir de las opciones del servidor. */
  String assertion(String optionsJson) {
    JsonNode options = JSON.readTree(optionsJson);
    return assertionFor(challenge(options));
  }

  /** Firma ya con un challenge concreto: permite firmar en un orden y verificar en otro. */
  String assertionFor(byte[] challenge) {
    if (countUp) {
      counter++;
    }
    byte[] clientData = clientData("webauthn.get", challenge);
    byte[] authData = authenticatorData(false);
    byte[] signature = sign(authData, clientData);
    ObjectNode response = JSON.createObjectNode();
    response.put("clientDataJSON", B64.encodeToString(clientData));
    response.put("authenticatorData", B64.encodeToString(authData));
    response.put("signature", B64.encodeToString(signature));
    if (sendUserHandle && userHandle != null) {
      response.put("userHandle", B64.encodeToString(userHandle));
    }
    ObjectNode body = JSON.createObjectNode();
    body.set("credential", credential(response));
    return JSON.writeValueAsString(body);
  }

  /** El challenge de unas opciones del servidor. */
  static byte[] challengeOf(String optionsJson) {
    return B64D.decode(JSON.readTree(optionsJson).path("challenge").asString());
  }

  private byte[] challenge(JsonNode options) {
    return challengeOverride != null ? challengeOverride : B64D.decode(options.path("challenge").asString());
  }

  private ObjectNode credential(ObjectNode response) {
    ObjectNode credential = JSON.createObjectNode();
    credential.put("id", B64.encodeToString(credentialId));
    credential.put("rawId", B64.encodeToString(credentialId));
    credential.put("type", "public-key");
    credential.set("response", response);
    credential.putObject("clientExtensionResults");
    credential.put("authenticatorAttachment", "platform");
    return credential;
  }

  private byte[] clientData(String type, byte[] challenge) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", typeOverride != null ? typeOverride : type);
    data.put("challenge", B64.encodeToString(challenge));
    data.put("origin", origin);
    if (crossOrigin != null) {
      data.put("crossOrigin", crossOrigin);
    }
    return JSON.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
  }

  private byte[] authenticatorData(boolean attested) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)));
      int flags = (up ? 0x01 : 0) | (uv ? 0x04 : 0) | (backupEligible ? 0x08 : 0) | (backupState ? 0x10 : 0)
          | (attested ? 0x40 : 0);
      out.write(flags);
      out.write(ByteBuffer.allocate(4).putInt((int) counter).array());
      if (attested) {
        out.write(new byte[16]); // AAGUID: ceros, como las passkeys con atestación «none»
        out.write(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        out.write(credentialId);
        EC2COSEKey key = EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256);
        out.write(CONVERTER.getCborConverter().writeValueAsBytes(key));
      }
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] sign(byte[] authData, byte[] clientData) {
    try {
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keys.getPrivate());
      signer.update(authData);
      signer.update(MessageDigest.getInstance("SHA-256").digest(clientData));
      byte[] signature = signer.sign();
      if (tamperSignature) {
        signature[signature.length - 1] ^= 0x01;
      }
      return signature;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
