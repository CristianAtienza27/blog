package com.cristianatienza.cms.security.passkey;

import java.util.UUID;

/**
 * Puntos de observación de una ceremonia, para comprobar en los tests el orden de los pasos (el
 * consumo ya confirmado antes de la verificación) y provocar fallos en el paso del contador. En
 * producción no hace nada.
 */
public interface CeremonyObserver {

  /** Después de confirmar el consumo del challenge y antes de la verificación criptográfica. */
  default void challengeConsumed(UUID challengeId, String ceremony) {}

  /** Dentro de la transacción del contador, con la credencial bloqueada. */
  default void counterStep(UUID credentialId) {}

  CeremonyObserver NONE = new CeremonyObserver() {};
}
