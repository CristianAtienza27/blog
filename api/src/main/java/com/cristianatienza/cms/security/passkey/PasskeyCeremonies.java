package com.cristianatienza.cms.security.passkey;

import com.cristianatienza.cms.security.passkey.ChallengeStore.Ceremony;
import com.cristianatienza.cms.security.passkey.ChallengeStore.Consumed;
import com.cristianatienza.cms.security.passkey.CounterPolicy.Classification;
import com.cristianatienza.cms.security.passkey.PasskeyStore.Credential;
import com.cristianatienza.cms.security.passkey.PasskeyStore.CounterResult;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import tools.jackson.databind.JsonNode;

/**
 * Ceremonias WebAuthn propias (D1) sobre webauthn4j: opciones, verificación criptográfica y
 * política del contador. No conoce la sesión HTTP: recibe su identificador y devuelve el resultado.
 *
 * <p>Verificación de una aserción, en tres pasos (diseño aprobado):
 * <ol>
 *   <li>consumo del challenge, confirmado en su propia transacción;
 *   <li>verificación criptográfica, sin ninguna transacción abierta: tipo, challenge, origen,
 *       hash del RP ID, UP, UV (obligatorio en el servidor) y firma;
 *   <li>contador y procedencia, en otra transacción con la credencial bloqueada.
 * </ol>
 */
class PasskeyCeremonies {

  /** Fallo de una ceremonia: el cliente solo recibe un mensaje genérico. */
  static final class CeremonyFailure extends RuntimeException {
    final Reason reason;

    CeremonyFailure(Reason reason) {
      super(reason.name(), null, false, false);
      this.reason = reason;
    }
  }

  enum Reason {
    MALFORMED, CHALLENGE, VERIFICATION, UNKNOWN_CREDENTIAL, FOREIGN_CREDENTIAL, SUSPENDED, COUNTER, DUPLICATE
  }

  /** Resultado de una aserción aceptada. */
  record Verified(Credential credential, Classification classification) {}

  /** Algoritmos admitidos: ES256, EdDSA y RS256 (los que ofrece Spring Security por defecto). */
  private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
      PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.RS256);
  private static final List<com.webauthn4j.data.PublicKeyCredentialParameters> WA4J_ALGORITHMS = List.of(
      new com.webauthn4j.data.PublicKeyCredentialParameters(com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
          COSEAlgorithmIdentifier.ES256),
      new com.webauthn4j.data.PublicKeyCredentialParameters(com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
          COSEAlgorithmIdentifier.EdDSA),
      new com.webauthn4j.data.PublicKeyCredentialParameters(com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
          COSEAlgorithmIdentifier.RS256));
  private static final int MAX_LABEL = 64;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final WebAuthnProperties properties;
  private final ChallengeStore challenges;
  private final PasskeyStore passkeys;
  private final WebAuthnJson json;
  private final Set<Origin> origins;
  private final ObjectConverter converter = new ObjectConverter();
  private final WebAuthnManager webAuthn;

  PasskeyCeremonies(WebAuthnProperties properties, ChallengeStore challenges, PasskeyStore passkeys, WebAuthnJson json) {
    this.properties = properties;
    this.challenges = challenges;
    this.passkeys = passkeys;
    this.json = json;
    this.origins = properties.allowedOrigins().stream().map(Origin::new).collect(Collectors.toUnmodifiableSet());
    // Atestación no estricta: se pide «none» y no se usa para ninguna decisión de confianza.
    this.webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(converter);
    // webauthn4j no decide sobre el contador: lo hace nuestra política, con la credencial
    // bloqueada y la evidencia del registro (paso 3). Sin esto lanzaría una excepción propia.
    this.webAuthn.getAuthenticationDataVerifier().setMaliciousCounterValueHandler(authenticationObject -> {});
  }

  // --- Registro -----------------------------------------------------------------------------------

  /** Opciones de registro: guarda el challenge y devuelve el JSON para el navegador. */
  Issued registrationOptions(String sessionId, UUID userId, String email) {
    byte[] handle = passkeys.userHandle(userId);
    byte[] challenge = randomChallenge();
    List<PublicKeyCredentialDescriptor> exclude = passkeys.forUser(userId).stream()
        .map(c -> descriptor(c)).toList();
    PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.builder()
        .rp(PublicKeyCredentialRpEntity.builder().id(properties.rpId()).name(properties.rpName()).build())
        .user(ImmutablePublicKeyCredentialUserEntity.builder().id(new Bytes(handle)).name(email).displayName(email).build())
        .challenge(new Bytes(challenge))
        .pubKeyCredParams(ALGORITHMS)
        .timeout(properties.challengeTtl())
        .excludeCredentials(exclude)
        .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
            .residentKey(ResidentKeyRequirement.PREFERRED)
            .userVerification(UserVerificationRequirement.REQUIRED)
            .build())
        .attestation(AttestationConveyancePreference.NONE)
        .build();
    String optionsJson = json.write(options);
    UUID id = challenges.issue(sessionId, userId, Ceremony.REGISTER, challenge, optionsJson, Map.of());
    return new Issued(id, optionsJson);
  }

  /** Registra la passkey. El challenge se consume (y confirma) antes de verificar nada. */
  Credential register(String sessionId, UUID userId, JsonNode body) {
    PublicKeyCredential<AuthenticatorAttestationResponse> credential = parse(() -> json.attestation(body.get("credential")));
    String label = label(body.get("label"));
    Consumed consumed = consume(sessionId, Ceremony.REGISTER, userId);
    AuthenticatorAttestationResponse response = credential.getResponse();
    Set<String> transports = response.getTransports() == null ? Set.of()
        : response.getTransports().stream().map(AuthenticatorTransport::getValue).collect(Collectors.toSet());
    RegistrationData data;
    try {
      data = webAuthn.verify(
          new RegistrationRequest(response.getAttestationObject().getBytes(), response.getClientDataJSON().getBytes(), transports),
          new RegistrationParameters(new ServerProperty(origins, properties.rpId(), new DefaultChallenge(consumed.challenge())),
              WA4J_ALGORITHMS, true, true));
    } catch (RuntimeException e) {
      throw new CeremonyFailure(Reason.VERIFICATION);
    }
    AuthenticatorData<?> authData = Objects.requireNonNull(data.getAttestationObject()).getAuthenticatorData();
    AttestedCredentialData attested = authData.getAttestedCredentialData();
    if (attested == null || !authData.isFlagUP() || !authData.isFlagUV()
        || !Arrays.equals(attested.getCredentialId(), credential.getRawId().getBytes())) {
      throw new CeremonyFailure(Reason.VERIFICATION);
    }
    if (passkeys.credentialIdExists(attested.getCredentialId())) {
      throw new CeremonyFailure(Reason.DUPLICATE);
    }
    Credential stored = new Credential(UUID.randomUUID(), userId, attested.getCredentialId(),
        converter.getCborConverter().writeValueAsBytes(attested.getCOSEKey()),
        Objects.requireNonNullElse(attested.getAaguid().getValue(), new UUID(0, 0)), authData.getSignCount(),
        authData.isFlagUV(), authData.isFlagBE(), authData.isFlagBS(), List.copyOf(transports), label, null, null, null);
    try {
      passkeys.insert(stored);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new CeremonyFailure(Reason.DUPLICATE);
    }
    return stored;
  }

  // --- Autenticación y step-up ------------------------------------------------------------------

  /** Opciones de autenticación: solo las passkeys utilizables del usuario de la sesión. */
  Issued assertionOptions(String sessionId, UUID userId, Ceremony ceremony) {
    List<Credential> usable = passkeys.forUser(userId).stream().filter(Credential::usable).toList();
    if (usable.isEmpty()) {
      throw new CeremonyFailure(Reason.UNKNOWN_CREDENTIAL);
    }
    byte[] challenge = randomChallenge();
    Map<UUID, Long> counters = new LinkedHashMap<>();
    usable.forEach(c -> counters.put(c.id(), c.signCount()));
    PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
        .challenge(new Bytes(challenge))
        .rpId(properties.rpId())
        .timeout(properties.challengeTtl())
        .allowCredentials(usable.stream().map(PasskeyCeremonies::descriptor).toList())
        .userVerification(UserVerificationRequirement.REQUIRED)
        .build();
    String optionsJson = json.write(options);
    UUID id = challenges.issue(sessionId, userId, ceremony, challenge, optionsJson, counters);
    return new Issued(id, optionsJson);
  }

  /**
   * Verifica una aserción del usuario de la sesión. La credencial debe ser suya, utilizable y estar
   * entre las permitidas al emitir el challenge; el userHandle, si llega, debe ser el suyo.
   */
  Verified authenticate(String sessionId, UUID userId, Ceremony ceremony, JsonNode body) {
    PublicKeyCredential<AuthenticatorAssertionResponse> credential = parse(() -> json.assertion(body.get("credential")));
    // Paso 1: el consumo queda confirmado aunque todo lo demás falle.
    Consumed consumed = consume(sessionId, ceremony, userId);
    Credential stored = passkeys.byCredentialId(credential.getRawId().getBytes())
        .orElseThrow(() -> new CeremonyFailure(Reason.UNKNOWN_CREDENTIAL));
    if (!stored.userId().equals(userId)) {
      throw new CeremonyFailure(Reason.FOREIGN_CREDENTIAL);
    }
    if (!stored.usable()) {
      throw new CeremonyFailure(Reason.SUSPENDED);
    }
    if (!consumed.countersAtIssue().containsKey(stored.id())) {
      throw new CeremonyFailure(Reason.UNKNOWN_CREDENTIAL);
    }
    AuthenticatorAssertionResponse response = credential.getResponse();
    if (response.getUserHandle() != null) {
      byte[] handle = passkeys.existingUserHandle(userId).orElse(null);
      if (handle == null || !Arrays.equals(handle, response.getUserHandle().getBytes())) {
        throw new CeremonyFailure(Reason.FOREIGN_CREDENTIAL);
      }
    }
    // Paso 2: criptografía, sin transacción.
    AuthenticationData data;
    try {
      COSEKey key = converter.getCborConverter().readValue(stored.publicKeyCose(), COSEKey.class);
      AttestedCredentialData attested = new AttestedCredentialData(new AAGUID(stored.aaguid()), stored.credentialId(),
          Objects.requireNonNull(key));
      CredentialRecordImpl record = new CredentialRecordImpl(null, stored.uvInitialized(), stored.backupEligible(),
          stored.backupState(), stored.signCount(), attested, null, null, null, null);
      data = webAuthn.verify(
          new AuthenticationRequest(stored.credentialId(), response.getAuthenticatorData().getBytes(),
              response.getClientDataJSON().getBytes(), response.getSignature().getBytes()),
          new AuthenticationParameters(new ServerProperty(origins, properties.rpId(), new DefaultChallenge(consumed.challenge())),
              record, List.of(stored.credentialId()), true, true));
    } catch (RuntimeException e) {
      throw new CeremonyFailure(Reason.VERIFICATION);
    }
    AuthenticatorData<?> authData = data.getAuthenticatorData();
    if (authData == null || !authData.isFlagUP() || !authData.isFlagUV() || authData.isFlagBE() != stored.backupEligible()) {
      throw new CeremonyFailure(Reason.VERIFICATION);
    }
    // Paso 3: contador y procedencia, en otra transacción.
    CounterResult result = passkeys.counterStep(stored.id(), userId, ceremony.db, consumed, authData.getSignCount(),
        authData.isFlagBS());
    if (!result.classification().accepted) {
      throw new CounterFailure(stored, result);
    }
    return new Verified(stored, result.classification());
  }

  /** Rechazo por el contador: lleva la clasificación para los eventos. */
  static final class CounterFailure extends RuntimeException {
    final Credential credential;
    final CounterResult result;

    CounterFailure(Credential credential, CounterResult result) {
      super(result.classification().name(), null, false, false);
      this.credential = credential;
      this.result = result;
    }
  }

  record Issued(UUID challengeId, String optionsJson) {}

  Duration challengeTtl() {
    return properties.challengeTtl();
  }

  // --- Auxiliares ---------------------------------------------------------------------------------

  private Consumed consume(String sessionId, Ceremony ceremony, UUID userId) {
    Consumed consumed = challenges.consume(sessionId, ceremony)
        .orElseThrow(() -> new CeremonyFailure(Reason.CHALLENGE));
    if (!consumed.userId().equals(userId)) {
      throw new CeremonyFailure(Reason.CHALLENGE);
    }
    return consumed;
  }

  private static <T> T parse(java.util.function.Supplier<T> reader) {
    try {
      return reader.get();
    } catch (IllegalArgumentException e) {
      throw new CeremonyFailure(Reason.MALFORMED);
    }
  }

  private static String label(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new CeremonyFailure(Reason.MALFORMED);
    }
    String value = node.asString().strip();
    if (value.isEmpty()) {
      return null;
    }
    if (value.length() > MAX_LABEL) {
      throw new CeremonyFailure(Reason.MALFORMED);
    }
    return value;
  }

  private static byte[] randomChallenge() {
    byte[] challenge = new byte[32];
    RANDOM.nextBytes(challenge);
    return challenge;
  }

  private static PublicKeyCredentialDescriptor descriptor(Credential c) {
    return PublicKeyCredentialDescriptor.builder()
        .type(PublicKeyCredentialType.PUBLIC_KEY)
        .id(new Bytes(c.credentialId()))
        .transports(c.transports().stream().map(AuthenticatorTransport::valueOf).collect(Collectors.toSet()))
        .build();
  }
}
