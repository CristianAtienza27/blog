package com.cristianatienza.cms.security;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Usuarios para el inicio de sesión. La API tiene SELECT en app_user y UPDATE solo en la columna
 * last_login_at (V7).
 */
@Repository
public class AppUserRepository {

  /**
   * Usuario tal como se guarda; el hash nunca sale de este paquete ni se registra. El sello de
   * seguridad (password_changed_at) se lee en la misma fila que el hash.
   */
  public record AppUser(UUID id, String email, String passwordHash, String role, boolean enabled, boolean enrollRequired,
      Instant securityStamp) {
    @Override
    public String toString() {
      return "AppUser[id=" + id + ", role=" + role + ", enabled=" + enabled + ", enrollRequired=" + enrollRequired + "]";
    }
  }

  private static final String COLUMNS = "id, email, password_hash, role, enabled, enroll_required, password_changed_at";
  private static final RowMapper<AppUser> USER = (rs, i) -> new AppUser(rs.getObject("id", UUID.class),
      rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled"),
      rs.getBoolean("enroll_required"), rs.getObject("password_changed_at", OffsetDateTime.class).toInstant());

  private final JdbcTemplate jdbc;

  public AppUserRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  public Optional<AppUser> findById(UUID id) {
    return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?", USER, id)
        .stream()
        .findFirst();
  }

  public Optional<AppUser> findByEmail(String email) {
    return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE lower(email) = lower(?)", USER, email)
        .stream()
        .findFirst();
  }

  /** Sello de seguridad actual (H7): lectura por clave primaria, en cada petición con sesión. */
  public Optional<Instant> securityStamp(UUID id) {
    return jdbc.query("SELECT password_changed_at FROM app_user WHERE id = ?",
        (rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant(), id).stream().findFirst();
  }

  /** Registra el último acceso: es lo único que la API puede escribir en app_user. */
  public void recordLogin(UUID id, Instant at) {
    jdbc.update("UPDATE app_user SET last_login_at = ? WHERE id = ?", OffsetDateTime.ofInstant(at, ZoneOffset.UTC), id);
  }
}
