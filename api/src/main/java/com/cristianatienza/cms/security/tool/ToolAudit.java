package com.cristianatienza.cms.security.tool;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auditoría de la herramienta de seguridad (H3): inserta en audit_event dentro de la transacción de
 * la operación (si no se puede escribir, la operación no se hace). Sin clave HMAC de la API: no hay
 * sesión, así que session_hash queda vacío, y tampoco hay IP ni agente de usuario.
 *
 * <p>Quién y dónde: el nombre de la máquina (el del contenedor) y el operador que declara el
 * procedimiento en {@code CMS_OPERATOR}. Es información declarada, no una identidad verificada:
 * se guarda como {@code declaredOperator}, o «no indicado» si falta. Nunca secretos. No es final
 * solo para que los tests simulen un fallo de escritura.
 */
class ToolAudit {

  static final String NOT_GIVEN = "no indicado";
  static final int MAX_FIELD = 100;

  private final String host;
  private final String operator;
  private final JsonMapper json = JsonMapper.builder().build();

  ToolAudit(String host, String operator) {
    this.host = clean(host);
    this.operator = clean(operator);
  }

  /** El operador declarado (variable de entorno CMS_OPERATOR) y el nombre de esta máquina. */
  static ToolAudit of(String declaredOperator) {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      host = System.getenv("HOSTNAME");
    }
    return new ToolAudit(host, declaredOperator);
  }

  String host() {
    return host;
  }

  String operator() {
    return operator;
  }

  void record(Connection connection, String event, String tool, UUID userId, Map<String, String> details)
      throws SQLException {
    Map<String, String> all = new LinkedHashMap<>();
    all.put("tool", tool);
    all.put("host", host);
    all.put("declaredOperator", operator);
    all.putAll(details);
    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO audit_event (id, event, outcome, user_id,"
        + " details) VALUES (?, ?, 'success', ?, ?::jsonb)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, event);
      ps.setObject(3, userId);
      ps.setString(4, json.writeValueAsString(all));
      ps.executeUpdate();
    }
  }

  /** Sin caracteres de control y con longitud acotada; vacío o ausente, «no indicado». */
  private static String clean(String value) {
    if (value == null) {
      return NOT_GIVEN;
    }
    StringBuilder out = new StringBuilder();
    value.strip().codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
    String cleaned = out.length() > MAX_FIELD ? out.substring(0, MAX_FIELD) : out.toString();
    return cleaned.isBlank() ? NOT_GIVEN : cleaned;
  }
}
