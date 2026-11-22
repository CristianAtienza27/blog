package com.cristianatienza.cms.security.limits;

import com.cristianatienza.cms.security.SecretKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Limitación de intentos (fase 6.2.4, diseño aprobado):
 *
 * <pre>
 *   Ámbito             sin dispositivo de confianza       con dispositivo de confianza
 *   cuenta + dispositivo  —                               10 fallos / 15 min → bloqueo creciente
 *   cuenta + IP        5 fallos / 15 min → bloqueo creciente   10 fallos / 15 min
 *   IP                 20 fallos / 15 min → 429 durante 15 min (igual)
 *   modo de ataque     más de 50 fallos / hora en la cuenta → 1 intento / 15 min por IP (solo sin dispositivo)
 *   dispositivo, 24 h  —                               20 fallos → se revoca su confianza
 * </pre>
 *
 * La cuenta nunca se bloquea globalmente. Las claves son HMAC del correo TECLEADO (normalizado) y
 * de la IP: un correo inexistente se comporta exactamente igual que uno existente. Cada incremento
 * es un único INSERT ... ON CONFLICT DO UPDATE ... RETURNING, en su propia transacción confirmada
 * antes de seguir (E2): un fallo posterior de la auditoría no lo deshace. El tiempo lo da el reloj
 * de la API (E4), como parámetro de la consulta.
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class LoginLimiter {

  /** Claves de un intento: IP, correo tecleado y, si lo hay, la familia del dispositivo de confianza. */
  public record Keys(String ip, byte[] ipKey, byte[] accountKey, byte[] accountIpKey, UUID deviceFamily,
      byte[] deviceKey, byte[] deviceDayKey) {

    public boolean trusted() {
      return deviceFamily != null;
    }
  }

  /** Resultado de la comprobación previa: permitido, o bloqueado hasta un instante. */
  public record Decision(boolean allowed, long retryAfterSeconds, String scope) {
    static Decision allow() {
      return new Decision(true, 0, null);
    }
  }

  /** Lo que ha provocado un fallo: bloqueos nuevos y si el dispositivo superó su límite diario. */
  public record FailureResult(boolean blockedNow, boolean attackModeEntered, boolean deviceDayExceeded) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final SecretKeys keys;
  private final Clock clock;

  private final int pairLimit;
  private final int trustedPairLimit;
  private final int deviceLimit;
  private final int deviceDayLimit;
  private final int ipLimit;
  private final int attackThreshold;
  private final Duration window;
  private final Duration blockBase;
  private final Duration blockMax;
  private final Duration ipBlock;
  private final Duration attackWindow;
  private final Duration attackDuration;
  private final Duration attackInterval;
  private final Duration retention;

  public LoginLimiter(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys, Clock clock,
      @Value("${cms.security.limits.pair-failures:5}") int pairLimit,
      @Value("${cms.security.limits.trusted-pair-failures:10}") int trustedPairLimit,
      @Value("${cms.security.limits.device-failures:10}") int deviceLimit,
      @Value("${cms.security.limits.device-day-failures:20}") int deviceDayLimit,
      @Value("${cms.security.limits.ip-failures:20}") int ipLimit,
      @Value("${cms.security.limits.attack-threshold:50}") int attackThreshold,
      @Value("${cms.security.limits.window:15m}") Duration window,
      @Value("${cms.security.limits.block:15m}") Duration blockBase,
      @Value("${cms.security.limits.block-max:1h}") Duration blockMax,
      @Value("${cms.security.limits.ip-block:15m}") Duration ipBlock,
      @Value("${cms.security.limits.attack-window:1h}") Duration attackWindow,
      @Value("${cms.security.limits.attack-duration:1h}") Duration attackDuration,
      @Value("${cms.security.limits.attack-interval:15m}") Duration attackInterval) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.keys = keys;
    this.clock = clock;
    this.pairLimit = pairLimit;
    this.trustedPairLimit = trustedPairLimit;
    this.deviceLimit = deviceLimit;
    this.deviceDayLimit = deviceDayLimit;
    this.ipLimit = ipLimit;
    this.attackThreshold = attackThreshold;
    this.window = window;
    this.blockBase = blockBase;
    this.blockMax = blockMax;
    this.ipBlock = ipBlock;
    this.attackWindow = attackWindow;
    this.attackDuration = attackDuration;
    this.attackInterval = attackInterval;
    this.retention = Duration.ofHours(24);
  }

  /** Normalización del correo tecleado para las claves (igual que la búsqueda del usuario). */
  public static String normalizeEmail(String email) {
    return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
  }

  public Keys keys(String ip, String typedEmail, UUID deviceFamily) {
    String email = normalizeEmail(typedEmail);
    String address = ip == null ? "" : ip;
    return new Keys(address, keys.attemptKey("ip", address), keys.attemptKey("account", email),
        keys.attemptKey("account_ip", email + "|" + address),
        deviceFamily, deviceFamily == null ? null : keys.attemptKey("account_device", email + "|" + deviceFamily),
        deviceFamily == null ? null : keys.attemptKey("device_day", deviceFamily.toString()));
  }

  /** Comprobación previa (antes de Argon2id): IP, pareja o dispositivo y modo de ataque. */
  public Decision check(Keys k) {
    Instant now = clock.instant();
    Optional<Long> ip = blockedFor(k.ipKey(), "ip", now);
    if (ip.isPresent()) {
      return new Decision(false, ip.get(), "ip");
    }
    if (k.trusted()) {
      Optional<Long> device = blockedFor(k.deviceKey(), "account_device", now);
      if (device.isPresent()) {
        return new Decision(false, device.get(), "account_device");
      }
    }
    Optional<Long> pair = blockedFor(k.accountIpKey(), "account_ip", now);
    if (pair.isPresent()) {
      return new Decision(false, pair.get(), "account_ip");
    }
    if (!k.trusted() && attackMode(k.accountKey(), now)) {
      // Modo de ataque, navegador sin confianza: un intento cada 15 min por IP en esta cuenta.
      Optional<Instant> last = lastFailure(k.accountIpKey(), now);
      if (last.isPresent() && last.get().plus(attackInterval).isAfter(now)) {
        return new Decision(false, seconds(last.get().plus(attackInterval), now), "attack_mode");
      }
    }
    return Decision.allow();
  }

  /** Solo la IP (emisión de opciones WebAuthn, E1). */
  public Decision checkIp(String ip) {
    Optional<Long> blocked = blockedFor(keys.attemptKey("ip", ip == null ? "" : ip), "ip", clock.instant());
    return blocked.map(s -> new Decision(false, s, "ip")).orElse(Decision.allow());
  }

  /** Un fallo de autenticación (contraseña, passkey o código, E1), confirmado en su transacción. */
  public FailureResult recordFailure(Keys k) {
    Instant now = clock.instant();
    return requiresNew.execute(status -> {
      boolean blocked = increment("ip", k.ipKey(), ipLimit, window, ipBlock, ipBlock, now);
      boolean pairBlocked = increment("account_ip", k.accountIpKey(), k.trusted() ? trustedPairLimit : pairLimit,
          window, blockBase, blockMax, now);
      boolean deviceBlocked = false;
      boolean deviceDay = false;
      if (k.trusted()) {
        deviceBlocked = increment("account_device", k.deviceKey(), deviceLimit, window, blockBase, blockMax, now);
        deviceDay = countReaches("device_day", k.deviceDayKey(), deviceDayLimit, Duration.ofHours(24), now);
      }
      boolean attack = recordAccountFailure(k.accountKey(), now);
      return new FailureResult(blocked || pairBlocked || deviceBlocked, attack, deviceDay);
    });
  }

  /** Emitir opciones WebAuthn cuenta en el ámbito de la IP (E1). */
  public void recordOptionsIssued(String ip) {
    Instant now = clock.instant();
    requiresNew.executeWithoutResult(status ->
        increment("ip", keys.attemptKey("ip", ip == null ? "" : ip), ipLimit, window, ipBlock, ipBlock, now));
  }

  /** Acceso completo (F): se reinician la pareja y el dispositivo (E3). La IP y la cuenta, no. */
  public void resetOnFullAccess(Keys k) {
    requiresNew.executeWithoutResult(status -> {
      delete("account_ip", k.accountIpKey());
      if (k.trusted()) {
        delete("account_device", k.deviceKey());
      }
    });
  }

  /** Limpieza de contadores caducados (idempotente con varias instancias). */
  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
  public void purgeExpired() {
    jdbc.update("DELETE FROM login_attempt WHERE expires_at <= :now", Map.of("now", ts(clock.instant())));
  }

  // --- SQL ----------------------------------------------------------------------------------------

  /**
   * Incrementa un contador con ventana fija; al llegar al límite bloquea con una espera que crece
   * (base · 2^nivel, como mucho max) y empieza una ventana nueva. Devuelve si acaba de bloquear.
   */
  private boolean increment(String type, byte[] key, int limit, Duration window, Duration base, Duration max,
      Instant now) {
    MapSqlParameterSource p = params(type, key, now).addValue("limit", limit).addValue("window", window.toMillis())
        .addValue("base", base.toMillis()).addValue("max", max.toMillis());
    String failures = "(CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now THEN 1"
        + " ELSE a.failures + 1 END)";
    String reaches = "(" + failures + " >= :limit)";
    Boolean blocked = jdbc.queryForObject("INSERT INTO login_attempt AS a (key_type, key_hash, window_start, failures,"
        + " last_failure_at, block_level, blocked_until, expires_at)"
        + " VALUES (:type, :key, :now, CASE WHEN 1 >= :limit THEN 0 ELSE 1 END, :now,"
        + "   CASE WHEN 1 >= :limit THEN 1 ELSE 0 END,"
        + "   CASE WHEN 1 >= :limit THEN :now + :base * interval '1 millisecond' END, :expires)"
        + " ON CONFLICT (key_type, key_hash) DO UPDATE SET"
        + "   window_start = CASE WHEN " + reaches + " OR a.window_start + :window * interval '1 millisecond' <= :now"
        + "     THEN :now ELSE a.window_start END,"
        + "   failures = CASE WHEN " + reaches + " THEN 0 ELSE " + failures + " END,"
        + "   last_failure_at = :now,"
        + "   blocked_until = CASE WHEN " + reaches + " THEN :now + least(:base * power(2, a.block_level), :max)"
        + "     * interval '1 millisecond' ELSE a.blocked_until END,"
        + "   block_level = CASE WHEN " + reaches + " THEN least(a.block_level + 1, 10) ELSE a.block_level END,"
        + "   expires_at = greatest(:expires, coalesce(a.blocked_until, :expires))"
        + " RETURNING blocked_until IS NOT NULL AND blocked_until > :now AND last_failure_at = :now"
        + "   AND failures = 0", p, Boolean.class);
    return Boolean.TRUE.equals(blocked);
  }

  /** Cuenta en una ventana y dice si se alcanzó el límite (sin bloquear: dispositivo en 24 h). */
  private boolean countReaches(String type, byte[] key, int limit, Duration window, Instant now) {
    MapSqlParameterSource p = params(type, key, now).addValue("window", window.toMillis());
    Integer failures = jdbc.queryForObject("INSERT INTO login_attempt AS a (key_type, key_hash, window_start, failures,"
        + " last_failure_at, expires_at) VALUES (:type, :key, :now, 1, :now, :expires)"
        + " ON CONFLICT (key_type, key_hash) DO UPDATE SET"
        + "   window_start = CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now THEN :now"
        + "     ELSE a.window_start END,"
        + "   failures = CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now THEN 1"
        + "     ELSE a.failures + 1 END,"
        + "   last_failure_at = :now, expires_at = :expires"
        + " RETURNING failures", p, Integer.class);
    return failures != null && failures >= limit;
  }

  /** Fallos de la cuenta en una hora; por encima del umbral, modo de ataque durante una hora más. */
  private boolean recordAccountFailure(byte[] key, Instant now) {
    MapSqlParameterSource p = params("account", key, now).addValue("window", attackWindow.toMillis())
        .addValue("threshold", attackThreshold).addValue("duration", attackDuration.toMillis());
    Boolean entered = jdbc.queryForObject("WITH before AS (SELECT attack_mode_until FROM login_attempt"
        + "   WHERE key_type = :type AND key_hash = :key)"
        + " INSERT INTO login_attempt AS a (key_type, key_hash, window_start, failures, last_failure_at, expires_at)"
        + " VALUES (:type, :key, :now, 1, :now, :expires)"
        + " ON CONFLICT (key_type, key_hash) DO UPDATE SET"
        + "   window_start = CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now THEN :now"
        + "     ELSE a.window_start END,"
        + "   failures = CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now THEN 1"
        + "     ELSE a.failures + 1 END,"
        + "   last_failure_at = :now,"
        + "   attack_mode_until = CASE WHEN (CASE WHEN a.window_start + :window * interval '1 millisecond' <= :now"
        + "     THEN 1 ELSE a.failures + 1 END) > :threshold THEN :now + :duration * interval '1 millisecond'"
        + "     ELSE a.attack_mode_until END,"
        + "   expires_at = greatest(:expires, coalesce(a.attack_mode_until, :expires))"
        + " RETURNING attack_mode_until IS NOT NULL AND attack_mode_until > :now"
        + "   AND NOT coalesce((SELECT attack_mode_until > :now FROM before), false)", p, Boolean.class);
    return Boolean.TRUE.equals(entered);
  }

  private Optional<Long> blockedFor(byte[] key, String type, Instant now) {
    return jdbc.query("SELECT blocked_until FROM login_attempt WHERE key_type = :type AND key_hash = :key"
            + " AND blocked_until > :now", params(type, key, now),
        (rs, i) -> seconds(rs.getObject(1, OffsetDateTime.class).toInstant(), now)).stream().findFirst();
  }

  private boolean attackMode(byte[] key, Instant now) {
    return !jdbc.query("SELECT 1 FROM login_attempt WHERE key_type = 'account' AND key_hash = :key"
        + " AND attack_mode_until > :now", params("account", key, now), (rs, i) -> 1).isEmpty();
  }

  private Optional<Instant> lastFailure(byte[] key, Instant now) {
    return jdbc.query("SELECT last_failure_at FROM login_attempt WHERE key_type = 'account_ip' AND key_hash = :key"
            + " AND last_failure_at IS NOT NULL", params("account_ip", key, now),
        (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant()).stream().findFirst();
  }

  private void delete(String type, byte[] key) {
    jdbc.update("DELETE FROM login_attempt WHERE key_type = :type AND key_hash = :key",
        new MapSqlParameterSource("type", type).addValue("key", key));
  }

  private MapSqlParameterSource params(String type, byte[] key, Instant now) {
    return new MapSqlParameterSource("type", type).addValue("key", key).addValue("now", ts(now))
        .addValue("expires", ts(now.plus(retention)));
  }

  private static OffsetDateTime ts(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static long seconds(Instant until, Instant now) {
    return Math.max(1, Duration.between(now, until).toSeconds() + 1);
  }
}
