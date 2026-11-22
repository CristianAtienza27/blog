package com.cristianatienza.cms.security.passkey;

import com.cristianatienza.cms.security.SecretKeys;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Challenges WebAuthn de un solo uso en PostgreSQL (tabla webauthn_challenge, V8).
 *
 * <ul>
 *   <li><b>Emitir:</b> sustituye el challenge anterior de esa sesión y ceremonia (como mucho uno
 *       vivo, UNIQUE en la tabla). Caduca con el reloj de PostgreSQL. Su id no se guarda en la
 *       sesión: la fila ya está ligada a ella por session_hash, y así una ceremonia no escribe en
 *       la sesión (peticiones simultáneas de la misma sesión, 6.2.3).
 *   <li><b>Consumir:</b> un único {@code DELETE ... RETURNING} en su propia transacción
 *       (REQUIRES_NEW), confirmada antes de devolver nada. Con peticiones simultáneas, el bloqueo
 *       de fila hace que solo una obtenga el challenge; un fallo posterior (firma, excepción,
 *       reversión de otra transacción) no puede devolverlo. Vale igual con varias instancias.
 * </ul>
 */
public class ChallengeStore {

  enum Ceremony {
    REGISTER("register"), AUTHENTICATE("authenticate"), STEP_UP("step_up");

    final String db;

    Ceremony(String db) {
      this.db = db;
    }
  }

  /** Lo que devuelve el consumo: sobrevive al borrado de la fila (procedencia del contador). */
  record Consumed(UUID id, UUID userId, byte[] challenge, Instant issuedAt, Map<UUID, Long> countersAtIssue) {}

  private static final TypeReference<LinkedHashMap<String, Long>> COUNTERS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final SecretKeys keys;
  private final WebAuthnProperties properties;
  private final CeremonyObserver observer;
  private final JsonMapper json = JsonMapper.builder().build();

  ChallengeStore(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys,
      WebAuthnProperties properties, CeremonyObserver observer) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.keys = keys;
    this.properties = properties;
    this.observer = observer;
  }

  /** Emite un challenge (sustituye el anterior de la sesión y ceremonia) y devuelve su id. */
  UUID issue(String sessionId, UUID userId, Ceremony ceremony, byte[] challenge, String optionsJson,
      Map<UUID, Long> countersAtIssue) {
    byte[] sessionHash = keys.sessionHash(sessionId);
    Map<String, Long> counters = new LinkedHashMap<>();
    countersAtIssue.forEach((id, count) -> counters.put(id.toString(), count));
    String countersJson = json.writeValueAsString(counters);
    UUID id = UUID.randomUUID();
    requiresNew.executeWithoutResult(status -> {
      // Emisiones simultáneas de la misma sesión y ceremonia: un bloqueo consultivo de la
      // transacción las serializa (sin él, dos DELETE + INSERT chocan en la restricción UNIQUE).
      jdbc.query("SELECT pg_catalog.pg_advisory_xact_lock(?)", rs -> null, lockKey(sessionHash, ceremony));
      jdbc.update("DELETE FROM webauthn_challenge WHERE session_hash = ? AND ceremony = ?", sessionHash, ceremony.db);
      jdbc.update("INSERT INTO webauthn_challenge (id, session_hash, user_id, ceremony, challenge, options,"
          + " counters_at_issue, expires_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,"
          + " clock_timestamp() + ? * interval '1 millisecond')",
          id, sessionHash, userId, ceremony.db, challenge, optionsJson, countersJson, properties.challengeTtl().toMillis());
    });
    return id;
  }

  /** Clave del bloqueo consultivo: 8 bytes de la huella de la sesión combinados con la ceremonia. */
  private static long lockKey(byte[] sessionHash, Ceremony ceremony) {
    return java.nio.ByteBuffer.wrap(sessionHash, 0, 8).getLong() ^ (0x5157_0000L + ceremony.ordinal());
  }

  /**
   * Consume el challenge vivo de esta sesión y ceremonia, si no ha caducado. La transacción del
   * borrado ya está confirmada cuando este método devuelve (también si está vacío).
   */
  Optional<Consumed> consume(String sessionId, Ceremony ceremony) {
    byte[] sessionHash = keys.sessionHash(sessionId);
    Optional<Consumed> consumed = requiresNew.execute(status -> jdbc.query(
            "DELETE FROM webauthn_challenge WHERE session_hash = ? AND ceremony = ?"
                + " AND expires_at > clock_timestamp()"
                + " RETURNING id, user_id, challenge, created_at, counters_at_issue::text AS counters",
            (rs, i) -> new Consumed(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getBytes("challenge"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                counters(rs.getString("counters"))),
            sessionHash, ceremony.db)
        .stream()
        .findFirst());
    consumed.ifPresent(c -> observer.challengeConsumed(c.id(), ceremony.db));
    return consumed;
  }

  /** Al cerrar todas las sesiones de un usuario. */
  public void deleteForUser(UUID userId) {
    jdbc.update("DELETE FROM webauthn_challenge WHERE user_id = ?", userId);
  }

  /** Limpieza periódica de los caducados (también con varias instancias: es idempotente). */
  @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
  void purgeExpired() {
    jdbc.update("DELETE FROM webauthn_challenge WHERE expires_at <= clock_timestamp()");
  }

  private Map<UUID, Long> counters(String text) {
    Map<UUID, Long> out = new LinkedHashMap<>();
    json.readValue(text, COUNTERS).forEach((id, count) -> out.put(UUID.fromString(id), count));
    return out;
  }
}
