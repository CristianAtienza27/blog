package com.cristianatienza.cms.security.audit;

import com.cristianatienza.cms.security.SecretKeys;
import com.cristianatienza.cms.security.SecurityEvents;
import com.cristianatienza.cms.web.ApiResponseFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auditoría persistente (fase 6.2.4): audit_event y audit_event_ip (V9), de solo inserción.
 *
 * <p><b>Transacciones (E2).</b> Cada evento se escribe en su propia transacción (REQUIRES_NEW): no
 * comparte transacción con la operación que audita.
 * <ul>
 *   <li>{@link #record}: si no se puede escribir, lanza {@link AuditWriteException}. Los caminos que
 *       conceden acceso lo llaman ANTES de conceder nada: sin auditoría no hay acceso.
 *   <li>{@link #recordBestEffort}: para las acciones protectoras (cerrar sesión, revocar), que ya
 *       están confirmadas: un fallo de auditoría se registra sin datos y la acción no se deshace.
 * </ul>
 *
 * <p>Nunca guarda secretos: los detalles los construye cada llamada sin contraseñas, códigos,
 * challenges, tokens ni identificadores de sesión. Del contexto de la petición toma la IP (tabla
 * aparte), el agente de usuario truncado y la huella HMAC de la sesión.
 */
public class AuditLog implements SecurityEvents {

  /** No se pudo escribir la auditoría: la operación que la exigía no se completa. */
  public static final class AuditWriteException extends RuntimeException {
    AuditWriteException(Throwable cause) {
      super("no se pudo registrar el evento de auditoría", cause, false, false);
    }
  }

  static final int MAX_USER_AGENT = 200;
  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final SecretKeys keys;
  private final JsonMapper json = JsonMapper.builder().build();

  public AuditLog(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.keys = keys;
  }

  @Override
  public void record(String type, UUID userId, Map<String, String> details) {
    HttpServletRequest request = currentRequest();
    // El identificador de correlación de la petición (6.2.5), para cruzar la auditoría con los
    // registros y con la respuesta de error.
    String correlationId = ApiResponseFilter.correlationId(request);
    if (correlationId != null) {
      Map<String, String> withCorrelation = new java.util.LinkedHashMap<>(details == null ? Map.of() : details);
      withCorrelation.put("correlationId", correlationId);
      details = withCorrelation;
    }
    write(type, outcome(type), userId, details, request == null ? null : request.getRemoteAddr(),
        request == null ? null : request.getHeader("User-Agent"), request == null ? null : sessionHash(request));
  }

  @Override
  public void recordBestEffort(String type, UUID userId, Map<String, String> details) {
    try {
      record(type, userId, details);
    } catch (RuntimeException e) {
      // Sin datos del evento ni de la petición: solo el tipo, para poder investigar.
      log.error("auditoría no registrada (acción ya completada): {}", type);
    }
  }

  /**
   * Registra el evento DENTRO de la transacción de la conexión dada (fase 6.3.4): el cambio de estado
   * de una publicación y su evento se confirman o se deshacen juntos, así que repetir una
   * reconciliación nunca duplica eventos. Con los mismos datos de la petición que {@link #record}.
   */
  public void recordIn(java.sql.Connection connection, String type, UUID userId, Map<String, String> details)
      throws java.sql.SQLException {
    HttpServletRequest request = currentRequest();
    Map<String, String> all = new java.util.LinkedHashMap<>(details == null ? Map.of() : details);
    String correlationId = ApiResponseFilter.correlationId(request);
    if (correlationId != null) {
      all.put("correlationId", correlationId);
    }
    String agent = request == null ? null : request.getHeader("User-Agent");
    if (agent != null && agent.length() > MAX_USER_AGENT) {
      agent = agent.substring(0, MAX_USER_AGENT);
    }
    insertIn(connection, type, outcome(type), userId, request == null ? null : sessionHash(request), agent,
        json.writeValueAsString(all), request == null ? null : request.getRemoteAddr());
  }

  /** INSERT en audit_event (y audit_event_ip) con la conexión y la transacción de quien llama. */
  public static void insertIn(java.sql.Connection connection, String type, String outcome, UUID userId,
      byte[] sessionHash, String userAgent, String detailsJson, String ip) throws java.sql.SQLException {
    UUID id = UUID.randomUUID();
    try (var ps = connection.prepareStatement("INSERT INTO audit_event (id, event, outcome, user_id, session_hash,"
        + " user_agent, details) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)")) {
      ps.setObject(1, id);
      ps.setString(2, type);
      ps.setString(3, outcome);
      ps.setObject(4, userId);
      ps.setBytes(5, sessionHash);
      ps.setString(6, userAgent);
      ps.setString(7, detailsJson);
      ps.executeUpdate();
    }
    if (ip != null && !ip.isBlank()) {
      try (var ps = connection.prepareStatement("INSERT INTO audit_event_ip (event_id, ip) VALUES (?, ?::inet)")) {
        ps.setObject(1, id);
        ps.setString(2, ip);
        ps.executeUpdate();
      }
    }
  }

  /** Resultado según la convención de nombres (para quien inserta con {@link #insertIn}). */
  public static String outcomeOf(String type) {
    return outcome(type);
  }

  /** Escritura directa (también fuera de una petición, como la agregación CSRF). */
  void write(String type, String outcome, UUID userId, Map<String, String> details, String ip, String userAgent,
      byte[] sessionHash) {
    String detailsJson = json.writeValueAsString(details == null ? Map.of() : details);
    String agent = userAgent == null ? null
        : userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent;
    try {
      requiresNew.executeWithoutResult(status -> {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_event (id, event, outcome, user_id, session_hash, user_agent, details)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", id, type, outcome, userId, sessionHash, agent, detailsJson);
        if (ip != null && !ip.isBlank()) {
          jdbc.update("INSERT INTO audit_event_ip (event_id, ip) VALUES (?, ?::inet)", id, ip);
        }
      });
    } catch (RuntimeException e) {
      throw new AuditWriteException(e);
    }
  }

  /** Resultado del evento a partir de su nombre (convención de los tipos de evento). */
  static String outcome(String type) {
    if (type.matches(".*(fail|reject|anomaly|blocked|closed_after|stale|revoked|limited|csrf).*")) {
      return "failure";
    }
    if (type.matches(".*(grace_use|out_of_order|attack_mode|retention|overlapped|delayed).*")) {
      return "info";
    }
    return "success";
  }

  private byte[] sessionHash(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    try {
      return session == null ? null : keys.sessionHash(session.getId());
    } catch (IllegalStateException invalidated) {
      return null;
    }
  }

  private static HttpServletRequest currentRequest() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
        ? attributes.getRequest() : null;
  }
}
