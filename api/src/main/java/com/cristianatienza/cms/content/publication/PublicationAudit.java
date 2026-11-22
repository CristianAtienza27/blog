package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.security.audit.AuditLog;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Eventos de la publicación (fase 6.3.4), SIEMPRE en la transacción del cambio de estado: se
 * confirman o se deshacen juntos (R5). Con una petición HTTP, con sus datos (sesión, IP, agente,
 * correlación) a través de {@link AuditLog}; desde la herramienta o la reconciliación, sin ellos.
 * Nunca contenido: versiones, épocas, huellas, canal e incidencias acotadas.
 */
class PublicationAudit {

  static final String PUBLISHED = "content.published";
  static final String ACTIVATED = "content.activated";
  static final String PUBLISH_REJECTED = "content.publish_rejected";
  static final String ACTIVATION_REJECTED = "content.activation_rejected";
  static final String PUBLISH_FAILED = "content.publish_failed";
  static final String RECONCILED = "content.reconciled";
  static final String RECONCILIATION_FAILED = "content.reconciliation_failed";
  static final String WEB_EPOCH_RECOVERED = "content.web_epoch_recovered";
  static final String SNAPSHOT_PURGED = "content.snapshot_purged";

  private static final int MAX_DETAIL = 500;

  private final ObjectProvider<AuditLog> auditLog;

  PublicationAudit(ObjectProvider<AuditLog> auditLog) {
    this.auditLog = auditLog;
  }

  void record(Connection connection, String event, Actor actor, Map<String, String> details) throws SQLException {
    Map<String, String> all = new LinkedHashMap<>();
    details.forEach((key, value) -> all.put(key, value == null ? null
        : value.length() > MAX_DETAIL ? value.substring(0, MAX_DETAIL) : value));
    all.put("via", actor.via());
    all.putAll(actor.facts());
    AuditLog log = auditLog.getIfAvailable();
    if (log != null) {
      log.recordIn(connection, event, actor.userId(), all);
    } else {
      AuditLog.insertIn(connection, event, AuditLog.outcomeOf(event), actor.userId(), null, null,
          PublicationRepository.JSON.writeValueAsString(all), null);
    }
  }
}
