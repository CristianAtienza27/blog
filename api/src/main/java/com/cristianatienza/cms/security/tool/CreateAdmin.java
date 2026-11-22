package com.cristianatienza.cms.security.tool;

import com.cristianatienza.cms.security.PasswordHashing;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * {@code crear-admin} (fase 6.2.6): el primer administrador, sin registro público. Lee la
 * contraseña de un archivo (nunca de un argumento), aplica la política, guarda el hash Argon2id y
 * deja la cuenta en registro pendiente: el primer acceso es P1 (solo registrar una passkey). Solo
 * ADMIN (H5). Inserción y auditoría en una única transacción, como cms_security_tool.
 */
final class CreateAdmin {

  static final String TOOL = "crear-admin";
  /** La misma forma que exige app_user (V7). */
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
  private static final String UNIQUE_VIOLATION = "23505";

  record Result(UUID userId) {}

  private final DataSource dataSource;
  private final PasswordHashing hashing;
  private final PasswordPolicy policy;
  private final ToolAudit audit;

  CreateAdmin(DataSource dataSource, PasswordHashing hashing, PasswordPolicy policy, ToolAudit audit) {
    this.dataSource = dataSource;
    this.hashing = hashing;
    this.policy = policy;
    this.audit = audit;
  }

  Result run(String rawEmail, Path passwordFile) throws SQLException {
    String email = validEmail(rawEmail);
    String password = PasswordPolicy.readSecretFile(passwordFile);
    policy.check(password, email);
    String hash = hashing.encode(password);
    UUID id = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM app_user WHERE lower(email) = lower(?)")) {
          ps.setString(1, email);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              throw new ToolFailure(ToolFailure.USER, "ya existe un usuario con ese correo; no se ha cambiado nada");
            }
          }
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO app_user (id, email, password_hash, role,"
            + " enabled, enroll_required) VALUES (?, ?, ?, 'ADMIN', true, true)")) {
          ps.setObject(1, id);
          ps.setString(2, email);
          ps.setString(3, hash);
          ps.executeUpdate();
        }
        audit.record(connection, "user.created", TOOL, id, Map.of("role", "ADMIN"));
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
          // Otra ejecución simultánea creó el mismo correo.
          throw new ToolFailure(ToolFailure.USER, "ya existe un usuario con ese correo; no se ha cambiado nada");
        }
        throw e;
      } catch (RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
    return new Result(id);
  }

  static String validEmail(String raw) {
    String email = raw == null ? "" : raw.strip();
    if (email.isEmpty()) {
      throw new ToolFailure(ToolFailure.INVALID, "falta --cms.usuario=<correo>");
    }
    if (email.length() < 3 || email.length() > 254 || !EMAIL.matcher(email).matches()
        || email.codePoints().anyMatch(Character::isISOControl)) {
      throw new ToolFailure(ToolFailure.INVALID, "el correo no es válido");
    }
    return email;
  }
}
