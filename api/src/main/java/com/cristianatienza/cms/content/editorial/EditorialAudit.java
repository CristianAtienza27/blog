package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.security.audit.AuditLog;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Eventos de auditoría de las escrituras editoriales (fase 6.4), SIEMPRE con la conexión de la
 * transacción en curso: el cambio y su evento se confirman o se deshacen juntos. Con una petición
 * HTTP, con sus datos (sesión, IP, agente, correlación) a través de {@link AuditLog}. Nunca
 * contenido: ids, números y nombres de campos.
 */
@Component
class EditorialAudit {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DataSource dataSource;
  private final ObjectProvider<AuditLog> auditLog;

  EditorialAudit(DataSource dataSource, ObjectProvider<AuditLog> auditLog) {
    this.dataSource = dataSource;
    this.auditLog = auditLog;
  }

  void record(String event, UUID userId, Map<String, String> details) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      AuditLog log = auditLog.getIfAvailable();
      if (log != null) {
        log.recordIn(connection, event, userId, details);
      } else {
        AuditLog.insertIn(connection, event, AuditLog.outcomeOf(event), userId, null, null,
            JSON.writeValueAsString(details), null);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("auditoría no registrada: " + event, e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
