package com.cristianatienza.cms.security;

import java.util.Map;
import java.util.UUID;

/**
 * Eventos de seguridad (D6). Desde la 6.2.4 los guarda AuditLog en audit_event (V9).
 *
 * <p>{@link #record} puede fallar (lanza): los caminos que conceden acceso lo llaman antes de
 * concederlo. {@link #recordBestEffort} no lanza: para acciones protectoras ya completadas (E2).
 *
 * <p>Los detalles nunca contienen secretos: ni contraseñas, ni códigos, ni challenges, ni material
 * de credenciales, ni identificadores de sesión.
 */
public interface SecurityEvents {

  void record(String type, UUID userId, Map<String, String> details);

  default void record(String type, UUID userId) {
    record(type, userId, Map.of());
  }

  /** Como record, pero un fallo de escritura no se propaga (la acción ya está completada). */
  default void recordBestEffort(String type, UUID userId, Map<String, String> details) {
    try {
      record(type, userId, details);
    } catch (RuntimeException e) {
      // la implementación persistente lo registra sin datos
    }
  }

  default void recordBestEffort(String type, UUID userId) {
    recordBestEffort(type, userId, Map.of());
  }

  /** No guarda nada (solo para pruebas unitarias sin base de datos). */
  SecurityEvents NONE = (type, userId, details) -> {};
}
