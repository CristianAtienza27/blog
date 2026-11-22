package com.cristianatienza.cms.security.passkey;

import java.time.Instant;
import java.util.Optional;

/**
 * Política del contador de firmas (WebAuthn §6.1.1, paso 22, y api/docs/seguridad.md). Lógica pura:
 * la evidencia la obtiene PasskeyStore del registro de aserciones.
 *
 * <p>Una anomalía (contador recibido n ≤ guardado s, salvo 0 y 0) solo se trata como benigna si el
 * registro DEMUESTRA que otra aserción B, aceptada, subió el contador mientras A estaba en curso:
 * {@code A.issued < B.verified < A.verified}, {@code n_B > n_A} y {@code n_A > A.counterAtIssue}.
 * Se demuestra el solapamiento de los challenges y el orden de los contadores, no el instante
 * físico de la interacción con el autenticador. Sin esa prueba, la proximidad en el tiempo no
 * interviene.
 */
final class CounterPolicy {

  enum Classification {
    NO_COUNTER("no_counter", true),
    INCREASING("increasing", true),
    OVERLAPPED("overlapped", false),
    DELAYED("delayed", false),
    WEAK_ANOMALY("weak_anomaly", false),
    STRONG_ANOMALY("strong_anomaly", false);

    final String db;
    final boolean accepted;

    Classification(String db, boolean accepted) {
      this.db = db;
      this.accepted = accepted;
    }
  }

  /** Anomalías débiles que suspenden la credencial dentro de la ventana. */
  static final int WEAK_ANOMALIES_TO_SUSPEND = 2;
  static final java.time.Duration WEAK_ANOMALY_WINDOW = java.time.Duration.ofDays(30);

  private CounterPolicy() {}

  /** ¿Hace falta buscar evidencia? Solo si es una anomalía que podría estar demostrada. */
  static boolean isAnomaly(long received, long stored) {
    return !(received > stored || (received == 0 && stored == 0));
  }

  /**
   * @param evidenceIssuedAt emisión de la aserción B que demuestra la concurrencia, si existe
   *     (PasskeyStore solo la busca cuando {@code received > counterAtIssue})
   */
  static Classification classify(long received, long stored, Instant issuedAt, Optional<Instant> evidenceIssuedAt) {
    if (received == 0 && stored == 0) {
      return Classification.NO_COUNTER;
    }
    if (received > stored) {
      return Classification.INCREASING;
    }
    if (evidenceIssuedAt.isPresent()) {
      return evidenceIssuedAt.get().isAfter(issuedAt) ? Classification.DELAYED : Classification.OVERLAPPED;
    }
    return received == stored ? Classification.WEAK_ANOMALY : Classification.STRONG_ANOMALY;
  }
}
