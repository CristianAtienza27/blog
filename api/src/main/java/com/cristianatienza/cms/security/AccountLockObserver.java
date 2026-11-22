package com.cristianatienza.cms.security;

import java.util.UUID;

/**
 * Puntos de observación del bloqueo por usuario (H7), para que los tests fijen el orden frente a
 * la recuperación de emergencia: antes de bloquear, con el bloqueo tomado y la operación ya hecha,
 * y después de soltarlo (antes de que Spring Session guarde la sesión). En producción no hace nada.
 */
public interface AccountLockObserver {

  /** Antes de pedir el bloqueo (la petición ya pasó el filtro del sello). */
  default void beforeLock(UUID userId, String operation) {}

  /** Con el bloqueo tomado, el sello comprobado y la operación completada, justo antes de soltarlo. */
  default void beforeRelease(UUID userId, String operation) {}

  /** Bloqueo ya soltado; Spring Session aún no ha guardado la sesión (lo hace al confirmar la respuesta). */
  default void afterRelease(UUID userId, String operation) {}

  AccountLockObserver NONE = new AccountLockObserver() {};
}
