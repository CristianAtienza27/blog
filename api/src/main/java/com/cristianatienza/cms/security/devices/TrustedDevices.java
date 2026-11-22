package com.cristianatienza.cms.security.devices;

import com.cristianatienza.cms.security.SecretKeys;
import com.cristianatienza.cms.security.SecurityEvents;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispositivos de confianza (fase 6.2.4, reglas aprobadas). {@code __Host-DEVICE} es una señal de
 * confianza del navegador, NUNCA un factor de autenticación: solo atenúa los límites de intentos
 * de su cuenta y no interviene en ninguna decisión de autorización. Sin contraseña y passkey no da
 * acceso a nada, y revocarlo solo quita el alivio, nunca el acceso.
 *
 * <p>Estados: ACTIVE (vigente y confirmado), PENDING (sucesor emitido, sin presentar; como mucho
 * uno por familia), RETIRED (predecesor de un sucesor confirmado), SUPERSEDED (pendiente sustituido
 * antes de confirmarse) y REVOKED. La rotación ocurre al completar F, como mucho una vez por inicio
 * de sesión; el token anterior solo se retira cuando el navegador presenta el nuevo. Toda operación
 * sobre una familia la bloquea (FOR UPDATE): las peticiones simultáneas se serializan.
 *
 * <p>Tolerancia ({@code cms.security.device.grace}, 2 min): un RETIRED o SUPERSEDED conserva la
 * confianza durante ese tiempo desde su retirada. No es una garantía absoluta sobre la entrega de
 * respuestas HTTP. Fuera de ella el token es CADUCADO: sin confianza, sin revocar la familia por una
 * sola presentación. Se revoca con evidencia suficiente (reglas de seguridad.md). No es posible
 * distinguir con certeza una cookie antigua legítima de una copia robada.
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class TrustedDevices {

  public static final String COOKIE = "__Host-DEVICE";

  /** Qué ocurrió al presentar el token. */
  public enum Presentation { NONE, ACTIVE, CONFIRMED, OUT_OF_ORDER, GRACE, STALE, INVALID }

  /** Confianza del navegador para la cuenta: solo cuenta en los límites de intentos. */
  public record Trust(Presentation presentation, UUID familyId, UUID deviceId) {
    public boolean trusted() {
      return presentation == Presentation.ACTIVE || presentation == Presentation.CONFIRMED
          || presentation == Presentation.OUT_OF_ORDER || presentation == Presentation.GRACE;
    }

    public boolean stale() {
      return presentation == Presentation.STALE;
    }

    /** La familia para los límites: solo si hay confianza. */
    public UUID trustedFamily() {
      return trusted() ? familyId : null;
    }

    static Trust none(Presentation presentation) {
      return new Trust(presentation, null, null);
    }
  }

  /** Un token nuevo para el navegador, con su caducidad. */
  public record Issued(String token, UUID familyId, Instant expiresAt) {}

  /** Lo que ve el usuario de un dispositivo (una familia): sin tokens ni huellas. */
  public record DeviceView(UUID id, Instant createdAt, Instant lastUsedAt, Instant expiresAt, boolean current) {}

  private record Row(UUID id, UUID userId, UUID familyId, String state, UUID supersededBy, Instant createdAt,
      Instant retiredAt, Instant expiresAt) {}

  private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getObject("id", UUID.class),
      rs.getObject("user_id", UUID.class), rs.getObject("family_id", UUID.class), rs.getString("state"),
      rs.getObject("superseded_by", UUID.class), instant(rs.getObject("created_at", OffsetDateTime.class)),
      instant(rs.getObject("retired_at", OffsetDateTime.class)), instant(rs.getObject("expires_at", OffsetDateTime.class)));
  private static final String COLUMNS = "id, user_id, family_id, state, superseded_by, created_at, retired_at, expires_at";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final SecretKeys keys;
  private final SecurityEvents events;
  private final Clock clock;
  private final Duration grace;
  private final Duration lifetime;
  private final Duration alternationWindow = Duration.ofMinutes(15);
  private final int staleLimit = 3;

  public TrustedDevices(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys,
      SecurityEvents events, Clock clock, @Value("${cms.security.device.grace:2m}") Duration grace,
      @Value("${cms.security.device.lifetime:90d}") Duration lifetime) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.keys = keys;
    this.events = events;
    this.clock = clock;
    this.grace = grace;
    this.lifetime = lifetime;
  }

  // --- Presentación (inicio de sesión y segundo factor) -------------------------------------------

  /** Evalúa el token presentado para esa cuenta y aplica las transiciones de su presentación. */
  public Trust evaluate(String token, UUID userId, String ip, String userAgent) {
    if (token == null || token.isBlank() || userId == null) {
      return Trust.none(Presentation.NONE);
    }
    byte[] hmac = keys.deviceToken(token);
    Instant now = clock.instant();
    Optional<Row> found = jdbc.query("SELECT " + COLUMNS + " FROM trusted_device WHERE token_hmac = ?", ROW, hmac)
        .stream().findFirst();
    if (found.isEmpty() || !found.get().userId().equals(userId) || found.get().state().equals("REVOKED")
        || !found.get().expiresAt().isAfter(now)) {
      events.recordBestEffort("device.token_rejected", userId);
      return Trust.none(Presentation.INVALID);
    }
    UUID familyId = found.get().familyId();
    byte[] ipHash = keys.origin("ip", ip == null ? "" : ip);
    byte[] agentHash = keys.origin("agent", userAgent == null ? "" : userAgent);
    Trust trust = requiresNew.execute(status -> {
      List<Row> family = lockFamily(familyId);
      Row row = family.stream().filter(r -> r.id().equals(found.get().id())).findFirst().orElseThrow();
      return switch (row.state()) {
        case "ACTIVE" -> {
          present(row, "active", ipHash, agentHash, now);
          yield new Trust(Presentation.ACTIVE, familyId, row.id());
        }
        case "PENDING" -> {
          confirm(family, row, now);
          present(row, "active", ipHash, agentHash, now);
          yield new Trust(Presentation.CONFIRMED, familyId, row.id());
        }
        case "SUPERSEDED", "RETIRED" -> {
          Row replacement = row.supersededBy() == null ? null
              : family.stream().filter(r -> r.id().equals(row.supersededBy())).findFirst().orElse(null);
          if (row.state().equals("SUPERSEDED") && replacement != null && replacement.state().equals("PENDING")) {
            // Respuestas desordenadas: el otro linaje no se confirmó; se confirma este.
            jdbc.update("UPDATE trusted_device SET state = 'SUPERSEDED', retired_at = ?, superseded_by = ? WHERE id = ?",
                ts(now), row.id(), replacement.id());
            confirm(family, row, now);
            present(row, "active", ipHash, agentHash, now);
            yield new Trust(Presentation.OUT_OF_ORDER, familyId, row.id());
          }
          if (row.retiredAt() != null && !row.retiredAt().plus(grace).isBefore(now)) {
            present(row, "active", ipHash, agentHash, now);
            yield new Trust(Presentation.GRACE, familyId, row.id());
          }
          present(row, "stale", ipHash, agentHash, now);
          yield new Trust(Presentation.STALE, familyId, row.id());
        }
        default -> Trust.none(Presentation.INVALID);
      };
    });
    switch (trust.presentation()) {
      case OUT_OF_ORDER -> events.recordBestEffort("device.token_out_of_order", userId);
      case GRACE -> events.recordBestEffort("device.token_grace_use", userId);
      case STALE -> events.recordBestEffort("device.token_stale", userId);
      default -> {}
    }
    revokeOnEvidence(userId, familyId, ipHash, agentHash, now);
    return trust;
  }

  /**
   * Acceso completo (F): emite o rota el token, como mucho una vez. Devuelve el token nuevo para la
   * cookie, o vacío si no hay que enviar ninguno (rotación ya en curso o token en su tolerancia).
   */
  public Optional<Issued> onFullAccess(Trust trust, UUID userId, String ip, String userAgent) {
    Instant now = clock.instant();
    byte[] ipHash = keys.origin("ip", ip == null ? "" : ip);
    byte[] agentHash = keys.origin("agent", userAgent == null ? "" : userAgent);
    Optional<Issued> issued = requiresNew.execute(status -> {
      // Primero el bloqueo de la familia: todo lo que se toca de ella (también sus presentaciones)
      // queda serializado bajo un único bloqueo. Actualizar las presentaciones antes provocaba un
      // interbloqueo entre accesos completos simultáneos (detectado en DeviceRotationTests).
      List<Row> family = trust.familyId() == null ? List.of() : lockFamily(trust.familyId());
      if (trust.familyId() != null) {
        jdbc.update("UPDATE trusted_device_presentation SET followed_by_full = true WHERE family_id = ?"
            + " AND ip_hash = ? AND agent_hash = ? AND presented_at > ?", trust.familyId(), ipHash, agentHash,
            ts(now.minus(Duration.ofHours(24))));
      }
      if (trust.presentation() == Presentation.GRACE) {
        return Optional.empty();
      }
      if (trust.familyId() != null && (trust.trusted() || trust.stale())) {
        Row active = family.stream().filter(r -> r.state().equals("ACTIVE")).findFirst().orElse(null);
        if (active != null && active.expiresAt().isAfter(now)) {
          Row pending = family.stream().filter(r -> r.state().equals("PENDING")).findFirst().orElse(null);
          if (pending != null && pending.createdAt().plus(grace).isAfter(now)) {
            return Optional.empty(); // otra petición simultánea ya rotó: sin Set-Cookie
          }
          UUID next = UUID.randomUUID();
          if (pending != null) {
            jdbc.update("UPDATE trusted_device SET state = 'SUPERSEDED', retired_at = ?, superseded_by = ? WHERE id = ?",
                ts(now), next, pending.id());
          }
          return Optional.of(insert(next, userId, trust.familyId(), "PENDING", active.id(), now, active.expiresAt()));
        }
      }
      // Sin familia utilizable: familia nueva.
      return Optional.of(insert(UUID.randomUUID(), userId, UUID.randomUUID(), "PENDING", null, now, now.plus(lifetime)));
    });
    issued.ifPresent(i -> events.recordBestEffort("device.token_issued", userId));
    return issued;
  }

  // --- Revocación ---------------------------------------------------------------------------------

  /** Revoca una familia (dispositivo) del usuario; devuelve si existía. Nunca se deshace. */
  public boolean revokeFamily(UUID userId, UUID familyId, String reason) {
    int revoked = requiresNew.execute(status -> jdbc.update("UPDATE trusted_device SET state = 'REVOKED',"
        + " revoked_at = ?, revoked_reason = ? WHERE user_id = ? AND family_id = ? AND state <> 'REVOKED'",
        ts(clock.instant()), reason, userId, familyId));
    if (revoked > 0) {
      events.recordBestEffort("device.revoked", userId, Map.of("reason", reason));
    }
    return revoked > 0;
  }

  /** Revoca todos los dispositivos del usuario (cerrar todas las sesiones, emergencia). */
  public int revokeAll(UUID userId, String reason) {
    int revoked = requiresNew.execute(status -> jdbc.update("UPDATE trusted_device SET state = 'REVOKED',"
        + " revoked_at = ?, revoked_reason = ? WHERE user_id = ? AND state <> 'REVOKED'",
        ts(clock.instant()), reason, userId));
    if (revoked > 0) {
      events.recordBestEffort("device.revoked_all", userId, Map.of("reason", reason, "tokens", Integer.toString(revoked)));
    }
    return revoked;
  }

  /** Dispositivos vigentes del usuario (uno por familia). */
  public List<DeviceView> list(UUID userId, UUID currentFamily) {
    return jdbc.query("SELECT family_id, min(created_at) AS created, max(last_used_at) AS used, max(expires_at) AS expires"
            + " FROM trusted_device WHERE user_id = ? AND family_id IN (SELECT family_id FROM trusted_device"
            + "   WHERE user_id = ? AND state IN ('ACTIVE', 'PENDING') AND expires_at > ?)"
            + " GROUP BY family_id ORDER BY created",
        (rs, i) -> new DeviceView(rs.getObject("family_id", UUID.class), instant(rs.getObject("created", OffsetDateTime.class)),
            instant(rs.getObject("used", OffsetDateTime.class)), instant(rs.getObject("expires", OffsetDateTime.class)),
            rs.getObject("family_id", UUID.class).equals(currentFamily)),
        userId, userId, ts(clock.instant()));
  }

  /** Limpieza: presentaciones de más de 24 h (la evidencia solo mira 24 h). */
  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
  public void purgePresentations() {
    jdbc.update("DELETE FROM trusted_device_presentation WHERE presented_at < ?",
        ts(clock.instant().minus(Duration.ofHours(24))));
  }

  // --- Auxiliares ---------------------------------------------------------------------------------

  /**
   * Revocación proporcionada, con evidencia suficiente:
   * 1. el token caducado y el ACTIVE de la familia, alternados desde orígenes distintos en 15 min;
   * 2. 3 presentaciones caducadas en 24 h desde un origen sin ningún acceso completo después.
   * (La 3, fallos con un token caducado hasta el límite, la aplica quien cuenta los fallos.)
   */
  private void revokeOnEvidence(UUID userId, UUID familyId, byte[] ipHash, byte[] agentHash, Instant now) {
    Boolean alternated = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM trusted_device_presentation a"
        + " JOIN trusted_device_presentation s ON s.family_id = a.family_id"
        + " WHERE a.family_id = ? AND a.kind = 'active' AND s.kind = 'stale'"
        + " AND a.presented_at > ? AND s.presented_at > ?"
        + " AND (a.ip_hash <> s.ip_hash OR a.agent_hash <> s.agent_hash))",
        Boolean.class, familyId, ts(now.minus(alternationWindow)), ts(now.minus(alternationWindow)));
    if (Boolean.TRUE.equals(alternated)) {
      revokeFamily(userId, familyId, "alternated_origins");
      return;
    }
    Integer stale = jdbc.queryForObject("SELECT count(*) FROM trusted_device_presentation WHERE family_id = ?"
        + " AND kind = 'stale' AND ip_hash = ? AND agent_hash = ? AND presented_at > ? AND NOT followed_by_full",
        Integer.class, familyId, ipHash, agentHash, ts(now.minus(Duration.ofHours(24))));
    if (stale != null && stale >= staleLimit) {
      revokeFamily(userId, familyId, "repeated_stale");
    }
  }

  /**
   * Bloquea la familia y la vuelve a leer. El SELECT ... FOR UPDATE devuelve, tras esperar el
   * bloqueo, la última versión de las filas que ya veía, pero no las que la transacción ganadora
   * acaba de insertar (su sucesor PENDING): sin releer, una segunda petición simultánea intentaría
   * emitir otro (lo impedía el índice único, con un 500; detectado en DeviceRotationTests). La
   * segunda lectura, en READ COMMITTED, ve todo lo confirmado.
   */
  private List<Row> lockFamily(UUID familyId) {
    jdbc.query("SELECT id FROM trusted_device WHERE family_id = ? ORDER BY created_at, id FOR UPDATE",
        (rs, i) -> rs.getObject(1), familyId);
    return jdbc.query("SELECT " + COLUMNS + " FROM trusted_device WHERE family_id = ? ORDER BY created_at, id",
        ROW, familyId);
  }

  /** Primera presentación de un sucesor: pasa a ACTIVE y el ACTIVE anterior, a RETIRED. */
  private void confirm(List<Row> family, Row row, Instant now) {
    family.stream().filter(r -> r.state().equals("ACTIVE") && !r.id().equals(row.id())).forEach(active ->
        jdbc.update("UPDATE trusted_device SET state = 'RETIRED', retired_at = ? WHERE id = ?", ts(now), active.id()));
    jdbc.update("UPDATE trusted_device SET state = 'ACTIVE', confirmed_at = ?, last_used_at = ? WHERE id = ?",
        ts(now), ts(now), row.id());
  }

  private void present(Row row, String kind, byte[] ipHash, byte[] agentHash, Instant now) {
    jdbc.update("INSERT INTO trusted_device_presentation (device_id, family_id, kind, ip_hash, agent_hash, presented_at)"
        + " VALUES (?, ?, ?, ?, ?, ?)", row.id(), row.familyId(), kind, ipHash, agentHash, ts(now));
    if (kind.equals("active")) {
      jdbc.update("UPDATE trusted_device SET last_used_at = ? WHERE id = ?", ts(now), row.id());
    }
  }

  private Issued insert(UUID id, UUID userId, UUID familyId, String state, UUID predecessor, Instant now,
      Instant expiresAt) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    jdbc.update("INSERT INTO trusted_device (id, user_id, family_id, token_hmac, state, predecessor_id, created_at,"
        + " expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, familyId, keys.deviceToken(token), state,
        predecessor, ts(now), ts(expiresAt));
    return new Issued(token, familyId, expiresAt);
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
