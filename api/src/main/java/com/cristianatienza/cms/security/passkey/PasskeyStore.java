package com.cristianatienza.cms.security.passkey;

import com.cristianatienza.cms.security.passkey.CounterPolicy.Classification;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Passkeys, identificador WebAuthn y procedencia del contador (V8), con la conexión de cms_api. */
public class PasskeyStore {

  /** Una passkey tal como se guarda. La clave pública no es secreta, pero no sale de este paquete. */
  record Credential(UUID id, UUID userId, byte[] credentialId, byte[] publicKeyCose, UUID aaguid, long signCount,
      boolean uvInitialized, boolean backupEligible, boolean backupState, List<String> transports, String label,
      Instant createdAt, Instant lastUsedAt, Instant suspendedAt) {

    boolean usable() {
      return suspendedAt == null;
    }

    @Override
    public String toString() {
      return "Credential[id=" + id + ", user=" + userId + ", suspended=" + (suspendedAt != null) + "]";
    }
  }

  /** Resultado del paso del contador: la clasificación y el contador guardado después. */
  record CounterResult(Classification classification, long storedBefore, long storedAfter, boolean suspended) {}

  private static final String COLUMNS = "id, user_id, credential_id, public_key_cose, aaguid, sign_count,"
      + " uv_initialized, backup_eligible, backup_state, transports, label, created_at, last_used_at, suspended_at";
  private static final RowMapper<Credential> CREDENTIAL = PasskeyStore::credential;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final CeremonyObserver observer;

  PasskeyStore(DataSource dataSource, PlatformTransactionManager transactions, CeremonyObserver observer) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.observer = observer;
  }

  /** Identificador WebAuthn del usuario (user.id): 32 bytes aleatorios, creado la primera vez. */
  byte[] userHandle(UUID userId) {
    byte[] handle = new byte[32];
    RANDOM.nextBytes(handle);
    jdbc.update("INSERT INTO webauthn_user_handle (user_id, handle) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING",
        userId, handle);
    return jdbc.queryForObject("SELECT handle FROM webauthn_user_handle WHERE user_id = ?", byte[].class, userId);
  }

  Optional<byte[]> existingUserHandle(UUID userId) {
    return jdbc.query("SELECT handle FROM webauthn_user_handle WHERE user_id = ?", (rs, i) -> rs.getBytes(1), userId)
        .stream().findFirst();
  }

  List<Credential> forUser(UUID userId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM passkey_credential WHERE user_id = ? ORDER BY created_at, id",
        CREDENTIAL, userId);
  }

  /** Passkeys utilizables y suspendidas (lo que muestra /api/auth/me en P2 y F). */
  public record Summary(int usable, int suspended) {}

  public Summary summary(UUID userId) {
    return jdbc.queryForObject("SELECT count(*) FILTER (WHERE suspended_at IS NULL),"
        + " count(*) FILTER (WHERE suspended_at IS NOT NULL) FROM passkey_credential WHERE user_id = ?",
        (rs, i) -> new Summary(rs.getInt(1), rs.getInt(2)), userId);
  }

  public int count(UUID userId) {
    return jdbc.queryForObject("SELECT count(*) FROM passkey_credential WHERE user_id = ?", Integer.class, userId);
  }

  Optional<Credential> byCredentialId(byte[] credentialId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM passkey_credential WHERE credential_id = ?", CREDENTIAL, credentialId)
        .stream().findFirst();
  }

  boolean credentialIdExists(byte[] credentialId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM passkey_credential WHERE credential_id = ?)", Boolean.class, credentialId));
  }

  void insert(Credential c) {
    jdbc.update(con -> {
      var ps = con.prepareStatement("INSERT INTO passkey_credential (id, user_id, credential_id, public_key_cose,"
          + " aaguid, sign_count, uv_initialized, backup_eligible, backup_state, transports, label)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
      ps.setObject(1, c.id());
      ps.setObject(2, c.userId());
      ps.setBytes(3, c.credentialId());
      ps.setBytes(4, c.publicKeyCose());
      ps.setObject(5, c.aaguid());
      ps.setLong(6, c.signCount());
      ps.setBoolean(7, c.uvInitialized());
      ps.setBoolean(8, c.backupEligible());
      ps.setBoolean(9, c.backupState());
      ps.setArray(10, con.createArrayOf("text", c.transports().toArray()));
      ps.setString(11, c.label());
      return ps;
    });
  }

  enum DeleteResult { DELETED, NOT_FOUND, LAST_USABLE }

  /**
   * Borra una passkey del usuario, salvo que sea la última utilizable. Bloquea sus passkeys para
   * que dos borrados simultáneos no dejen al usuario sin ninguna.
   */
  DeleteResult delete(UUID userId, UUID id) {
    return requiresNew.execute(status -> {
      List<Credential> all = jdbc.query("SELECT " + COLUMNS + " FROM passkey_credential WHERE user_id = ?"
          + " ORDER BY id FOR UPDATE", CREDENTIAL, userId);
      Credential target = all.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
      if (target == null) {
        return DeleteResult.NOT_FOUND;
      }
      long usableAfter = all.stream().filter(c -> c.usable() && !c.id().equals(id)).count();
      if (target.usable() && usableAfter == 0) {
        return DeleteResult.LAST_USABLE;
      }
      jdbc.update("DELETE FROM passkey_credential WHERE id = ? AND user_id = ?", id, userId);
      return DeleteResult.DELETED;
    });
  }

  /** Fin controlado del registro (D5): la única forma que tiene la API de cambiar enroll_required. */
  boolean completeEnrollment(UUID userId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT complete_passkey_enrollment(?)", Boolean.class, userId));
  }

  /**
   * Paso 3 de la autenticación, en su propia transacción: bloquea la credencial, clasifica el
   * contador recibido con la evidencia del registro, actualiza la credencial y deja la procedencia
   * en webauthn_assertion. Se ejecuta tanto si la aserción se acepta como si se rechaza.
   */
  CounterResult counterStep(UUID credentialId, UUID userId, String ceremony, ChallengeStore.Consumed challenge,
      long received, boolean backupState) {
    return requiresNew.execute(status -> {
      long stored = jdbc.queryForObject("SELECT sign_count FROM passkey_credential WHERE id = ? FOR UPDATE",
          Long.class, credentialId);
      observer.counterStep(credentialId);
      Instant verifiedAt = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
      long counterAtIssue = challenge.countersAtIssue().getOrDefault(credentialId, stored);
      Optional<Instant> evidence = Optional.empty();
      if (CounterPolicy.isAnomaly(received, stored) && received > counterAtIssue) {
        evidence = jdbc.query("SELECT challenge_issued_at FROM webauthn_assertion WHERE credential_id = ?"
                + " AND outcome = 'accepted' AND verified_at > ? AND verified_at < ? AND received_counter > ?"
                + " ORDER BY challenge_issued_at LIMIT 1",
            (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
            credentialId, OffsetDateTime.ofInstant(challenge.issuedAt(), java.time.ZoneOffset.UTC),
            OffsetDateTime.ofInstant(verifiedAt, java.time.ZoneOffset.UTC), received).stream().findFirst();
      }
      Classification classification = CounterPolicy.classify(received, stored, challenge.issuedAt(), evidence);
      long after = stored;
      boolean suspended = false;
      switch (classification) {
        case INCREASING -> {
          after = received;
          jdbc.update("UPDATE passkey_credential SET sign_count = ?, last_used_at = now(), backup_state = ?"
              + " WHERE id = ?", received, backupState, credentialId);
        }
        case NO_COUNTER -> jdbc.update("UPDATE passkey_credential SET last_used_at = now(), backup_state = ?"
            + " WHERE id = ?", backupState, credentialId);
        case WEAK_ANOMALY -> {
          int recent = jdbc.queryForObject("SELECT count(*) FROM webauthn_assertion WHERE credential_id = ?"
              + " AND classification = 'weak_anomaly' AND verified_at > ?", Integer.class, credentialId,
              OffsetDateTime.ofInstant(verifiedAt.minus(CounterPolicy.WEAK_ANOMALY_WINDOW), java.time.ZoneOffset.UTC));
          suspended = recent + 1 >= CounterPolicy.WEAK_ANOMALIES_TO_SUSPEND;
          jdbc.update("UPDATE passkey_credential SET anomaly_count = anomaly_count + 1, last_anomaly_at = now(),"
              + " suspended_at = CASE WHEN ? THEN coalesce(suspended_at, now()) ELSE suspended_at END WHERE id = ?",
              suspended, credentialId);
        }
        case STRONG_ANOMALY -> {
          suspended = true;
          jdbc.update("UPDATE passkey_credential SET anomaly_count = anomaly_count + 1, last_anomaly_at = now(),"
              + " suspended_at = coalesce(suspended_at, now()) WHERE id = ?", credentialId);
        }
        case OVERLAPPED, DELAYED -> {
          // Demostrada: se rechaza por obsoleta, sin contar para la suspensión.
        }
      }
      jdbc.update("INSERT INTO webauthn_assertion (credential_id, user_id, ceremony, challenge_id, challenge_issued_at,"
              + " counter_at_issue, verified_at, received_counter, stored_before, stored_after, outcome, classification)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          credentialId, userId, ceremony, challenge.id(),
          OffsetDateTime.ofInstant(challenge.issuedAt(), java.time.ZoneOffset.UTC), counterAtIssue,
          OffsetDateTime.ofInstant(verifiedAt, java.time.ZoneOffset.UTC), received, stored, after,
          classification.accepted ? "accepted" : "rejected", classification.db);
      return new CounterResult(classification, stored, after, suspended);
    });
  }

  private static Credential credential(ResultSet rs, int row) throws SQLException {
    Array transports = rs.getArray("transports");
    List<String> list = transports == null ? List.of() : Arrays.asList((String[]) transports.getArray());
    return new Credential(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
        rs.getBytes("credential_id"), rs.getBytes("public_key_cose"), rs.getObject("aaguid", UUID.class),
        rs.getLong("sign_count"), rs.getBoolean("uv_initialized"), rs.getBoolean("backup_eligible"),
        rs.getBoolean("backup_state"), List.copyOf(list), rs.getString("label"), instant(rs, "created_at"),
        instant(rs, "last_used_at"), instant(rs, "suspended_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
