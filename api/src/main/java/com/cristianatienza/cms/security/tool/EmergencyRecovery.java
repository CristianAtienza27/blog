package com.cristianatienza.cms.security.tool;

import com.cristianatienza.cms.security.PasswordHashing;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@code recuperacion-emergencia} (fase 6.2.6, revisión 3 §4): acceso perdido sin passkey ni
 * códigos. Solo por línea de comandos, como cms_security_tool. En UNA transacción: bloquea la
 * cuenta, pone la contraseña nueva (y con ella un sello de seguridad nuevo), borra las passkeys y el
 * identificador WebAuthn, invalida los códigos, revoca los dispositivos, cierra las sesiones, deja
 * la cuenta en registro pendiente (P1) y lo audita. Si algo falla, incluida la auditoría, no cambia
 * nada.
 *
 * <p><b>Sincronización con la API (H7).</b> Lo primero es {@code SELECT … FOR UPDATE} sobre la fila
 * del usuario: choca con el {@code FOR KEY SHARE} que la API mantiene durante cada operación que
 * concede un estado ({@code AccountLock}), así que la recuperación espera a que terminen las que
 * estén en curso y revoca lo que hayan creado, y las que lleguen después esperan a que termine y
 * ven el sello nuevo. Hace falta {@code FOR UPDATE} explícito: el UPDATE de columnas que no son
 * clave solo toma {@code FOR NO KEY UPDATE}, que no choca con {@code FOR KEY SHARE}.
 *
 * <p>Los challenges pendientes no se borran (cms_security_tool no tiene permiso, decisión de V8):
 * están ligados a la huella de sesiones que se cierran aquí y caducan en 5 minutos como mucho. Los
 * contadores de intentos tampoco (H4).
 */
final class EmergencyRecovery {

  static final String TOOL = "recuperacion-emergencia";
  static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  record Result(UUID userId, int passkeys, int userHandles, int codes, int devices, int sessions) {}

  private final DataSource dataSource;
  private final PasswordHashing hashing;
  private final PasswordPolicy policy;
  private final ToolAudit audit;

  EmergencyRecovery(DataSource dataSource, PasswordHashing hashing, PasswordPolicy policy, ToolAudit audit) {
    this.dataSource = dataSource;
    this.hashing = hashing;
    this.policy = policy;
    this.audit = audit;
  }

  Result run(String rawEmail, Path passwordFile) throws SQLException {
    String email = rawEmail == null ? "" : rawEmail.strip();
    if (email.isEmpty()) {
      throw new ToolFailure(ToolFailure.INVALID, "falta --cms.usuario=<correo>");
    }
    String password = PasswordPolicy.readSecretFile(passwordFile);
    policy.check(password, email);
    // Argon2id antes de la transacción: el bloqueo dura solo lo imprescindible.
    String hash = hashing.encode(password);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Result result = recover(connection, email, hash);
        connection.commit();
        return result;
      } catch (SQLException e) {
        connection.rollback();
        if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
          throw new ToolFailure(1, "la cuenta sigue ocupada por otra operación tras " + LOCK_TIMEOUT.toSeconds()
              + " s; no se ha cambiado nada (vuelve a intentarlo)");
        }
        throw e;
      } catch (RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  private Result recover(Connection connection, String email, String hash) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT.toMillis() + "ms'");
    }
    UUID userId;
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT id FROM app_user WHERE lower(email) = lower(?) FOR UPDATE")) {
      ps.setString(1, email);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new ToolFailure(ToolFailure.USER, "no existe ningún usuario con ese correo; no se ha cambiado nada");
        }
        userId = rs.getObject(1, UUID.class);
      }
    }
    // Contraseña nueva, sello nuevo (siempre distinto del anterior) y registro pendiente (P1).
    update(connection, "UPDATE app_user SET password_hash = ?, enroll_required = true,"
        + " password_changed_at = greatest(now(), password_changed_at + interval '1 microsecond'), updated_at = now()"
        + " WHERE id = ?", hash, userId);
    int passkeys = update(connection, "DELETE FROM passkey_credential WHERE user_id = ?", userId);
    int handles = update(connection, "DELETE FROM webauthn_user_handle WHERE user_id = ?", userId);
    int codes = update(connection, "UPDATE recovery_code SET invalidated_at = now()"
        + " WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", userId);
    int devices = update(connection, "UPDATE trusted_device SET state = 'REVOKED', revoked_at = now(),"
        + " revoked_reason = 'emergency_recovery' WHERE user_id = ? AND state <> 'REVOKED'", userId);
    int sessions = update(connection, "DELETE FROM spring_session WHERE principal_name = ?", userId.toString());
    Map<String, String> details = new LinkedHashMap<>();
    details.put("passkeys", Integer.toString(passkeys));
    details.put("userHandles", Integer.toString(handles));
    details.put("recoveryCodes", Integer.toString(codes));
    details.put("devices", Integer.toString(devices));
    details.put("sessions", Integer.toString(sessions));
    audit.record(connection, "user.emergency_recovery", TOOL, userId, details);
    return new Result(userId, passkeys, handles, codes, devices, sessions);
  }

  private static int update(Connection connection, String sql, Object... params) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      return ps.executeUpdate();
    }
  }
}
