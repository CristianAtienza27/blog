package com.cristianatienza.cms.content.publication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL de content_publication y de las tablas de control (V10), siempre con la conexión (y la
 * transacción protegida) de quien llama. Cada transición es un UPDATE … WHERE state = <esperado>:
 * repetirla no cambia nada, y el trigger de V10 rechaza cualquier transición fuera de la matriz.
 */
final class PublicationRepository {

  /** Una fila de content_publication. */
  record Row(int version, String state, String contentSha256, Integer files, Long bytes, Integer articles,
      String issues, Integer previousVersion, UUID requestedBy, String requestedVia, Instant createdAt,
      Instant generatedAt, Instant activatedAt, Instant updatedAt, Instant filesPurgedAt) {

    /** Se puede activar: candidata, retirada o fallida, con huella y con sus archivos (sin purgar). */
    boolean activatable() {
      return switch (state) {
        case "candidata", "retirada", "fallida" -> contentSha256 != null && filesPurgedAt == null;
        default -> false;
      };
    }
  }

  /** El resultado guardado de la última reconciliación. */
  record Check(String status, String reason, Integer pointerVersion, Instant checkedAt) {}

  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COLUMNS = "version, state, content_sha256, files, bytes, articles, issues::text,"
      + " previous_version, requested_by, requested_via, created_at, generated_at, activated_at, updated_at,"
      + " files_purged_at";

  private PublicationRepository() {}

  static List<Row> all(Connection c) throws SQLException {
    return query(c, "SELECT " + COLUMNS + " FROM content_publication ORDER BY version");
  }

  static List<Row> latest(Connection c, int limit) throws SQLException {
    return query(c, "SELECT " + COLUMNS + " FROM content_publication ORDER BY version DESC LIMIT " + limit);
  }

  static Optional<Row> find(Connection c, int version) throws SQLException {
    return query(c, "SELECT " + COLUMNS + " FROM content_publication WHERE version = " + version).stream().findFirst();
  }

  static Optional<Row> active(Connection c) throws SQLException {
    return query(c, "SELECT " + COLUMNS + " FROM content_publication WHERE state = 'activa'").stream().findFirst();
  }

  static List<Row> inState(Connection c, String state) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT " + COLUMNS + " FROM content_publication WHERE state = ? ORDER BY version")) {
      ps.setString(1, state);
      return rows(ps);
    }
  }

  static int maxVersion(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(version), 0) FROM content_publication");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  static boolean everActivated(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT exists(SELECT 1 FROM content_publication WHERE activated_at IS NOT NULL)");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  /** P1. */
  static void insertGenerating(Connection c, int version, UUID requestedBy, String via) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("INSERT INTO content_publication"
        + " (version, state, requested_by, requested_via) VALUES (?, 'generando', ?, ?)")) {
      ps.setInt(1, version);
      ps.setObject(2, requestedBy);
      ps.setString(3, via);
      ps.executeUpdate();
    }
  }

  /** P2 (o la reconciliación al adoptar una carpeta completa): generando → candidata con la huella. */
  static boolean toCandidate(Connection c, int version, String sha, int files, long bytes, int articles)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication SET state = 'candidata',"
        + " content_sha256 = ?, files = ?, bytes = ?, articles = ?, generated_at = clock_timestamp()"
        + " WHERE version = ? AND state = 'generando'")) {
      ps.setString(1, sha);
      ps.setInt(2, files);
      ps.setLong(3, bytes);
      ps.setInt(4, articles);
      ps.setInt(5, version);
      return ps.executeUpdate() == 1;
    }
  }

  /** generando o candidata → fallida, con las incidencias (la huella, si la había, se conserva). */
  static boolean toFailed(Connection c, int version, List<String> issues) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication SET state = 'fallida',"
        + " issues = ?::jsonb WHERE version = ? AND state IN ('generando', 'candidata')")) {
      ps.setString(1, JSON.writeValueAsString(issues));
      ps.setInt(2, version);
      return ps.executeUpdate() == 1;
    }
  }

  /** candidata → rechazada (la web no la valida), con las incidencias. */
  static boolean toRejected(Connection c, int version, List<String> issues) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication SET state = 'rechazada',"
        + " issues = ?::jsonb WHERE version = ? AND state = 'candidata'")) {
      ps.setString(1, JSON.writeValueAsString(issues));
      ps.setInt(2, version);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * vN pasa a activa (desde candidata, retirada o fallida con huella) y la que estaba activa, a
   * retirada. Devuelve la anterior activa si hubo transición; vacío si vN ya estaba activa o no es
   * activable (no cambia nada).
   */
  static Optional<Optional<Integer>> activate(Connection c, int version) throws SQLException {
    Optional<Row> current = active(c);
    if (current.isPresent() && current.get().version() == version) {
      return Optional.empty();
    }
    Optional<Row> target = find(c, version);
    if (target.isEmpty() || !target.get().activatable()) {
      return Optional.empty();
    }
    Integer previous = current.map(Row::version).orElse(null);
    if (previous != null) {
      try (PreparedStatement ps = c.prepareStatement(
          "UPDATE content_publication SET state = 'retirada' WHERE version = ? AND state = 'activa'")) {
        ps.setInt(1, previous);
        ps.executeUpdate();
      }
    }
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication SET state = 'activa',"
        + " activated_at = clock_timestamp(), previous_version = ? WHERE version = ?"
        + " AND state IN ('candidata', 'retirada', 'fallida')")) {
      ps.setObject(1, previous);
      ps.setInt(2, version);
      if (ps.executeUpdate() != 1) {
        throw new SQLException("la versión v" + version + " no se pudo activar");
      }
    }
    return Optional.of(Optional.ofNullable(previous));
  }

  static boolean markPurged(Connection c, int version) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication SET files_purged_at = clock_timestamp()"
        + " WHERE version = ? AND files_purged_at IS NULL AND state <> 'activa'")) {
      ps.setInt(1, version);
      return ps.executeUpdate() == 1;
    }
  }

  static Check check(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT status, detail ->> 'motivo', pointer_version, checked_at FROM content_publication_check WHERE id = 1");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return new Check(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3), instant(rs.getTimestamp(4)));
    }
  }

  static void saveCheck(Connection c, String status, String reason, Integer pointerVersion) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication_check SET status = ?,"
        + " detail = ?::jsonb, pointer_version = ?, checked_at = clock_timestamp() WHERE id = 1")) {
      ps.setString(1, status);
      ps.setString(2, JSON.writeValueAsString(reason == null ? java.util.Map.of() : java.util.Map.of("motivo", reason)));
      ps.setObject(3, pointerVersion);
      ps.executeUpdate();
    }
  }

  static long epoch(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT epoch FROM content_publication_lease WHERE id = 1");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static List<Row> query(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      return rows(ps);
    }
  }

  private static List<Row> rows(PreparedStatement ps) throws SQLException {
    List<Row> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(new Row(rs.getInt(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4),
            (Long) rs.getObject(5), (Integer) rs.getObject(6), rs.getString(7), (Integer) rs.getObject(8),
            (UUID) rs.getObject(9), rs.getString(10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
            instant(rs.getTimestamp(13)), instant(rs.getTimestamp(14)), instant(rs.getTimestamp(15))));
      }
    }
    return out;
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
