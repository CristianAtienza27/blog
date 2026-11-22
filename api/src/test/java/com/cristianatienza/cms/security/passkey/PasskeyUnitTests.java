package com.cristianatienza.cms.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.security.SecretKeys;
import com.cristianatienza.cms.security.passkey.CounterPolicy.Classification;
import com.cristianatienza.cms.security.recovery.RecoveryCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Lógica pura: política del contador, configuración WebAuthn, claves HMAC y formato de los códigos. */
class PasskeyUnitTests {

  private static final Instant ISSUED = Instant.parse("2026-11-14T10:00:00Z");

  @Test
  void politicaDelContador() {
    assertThat(CounterPolicy.classify(0, 0, ISSUED, Optional.empty())).isEqualTo(Classification.NO_COUNTER);
    assertThat(CounterPolicy.classify(1, 0, ISSUED, Optional.empty())).isEqualTo(Classification.INCREASING);
    assertThat(CounterPolicy.classify(8, 7, ISSUED, Optional.empty())).isEqualTo(Classification.INCREASING);
    assertThat(CounterPolicy.classify(7, 7, ISSUED, Optional.empty())).isEqualTo(Classification.WEAK_ANOMALY);
    assertThat(CounterPolicy.classify(6, 7, ISSUED, Optional.empty())).isEqualTo(Classification.STRONG_ANOMALY);
    assertThat(CounterPolicy.classify(0, 7, ISSUED, Optional.empty())).isEqualTo(Classification.STRONG_ANOMALY);
    // Con evidencia: emitida antes o a la vez (solapada) o después (retrasada).
    assertThat(CounterPolicy.classify(6, 7, ISSUED, Optional.of(ISSUED.minusSeconds(1)))).isEqualTo(Classification.OVERLAPPED);
    assertThat(CounterPolicy.classify(6, 7, ISSUED, Optional.of(ISSUED))).isEqualTo(Classification.OVERLAPPED);
    assertThat(CounterPolicy.classify(6, 7, ISSUED, Optional.of(ISSUED.plusSeconds(1)))).isEqualTo(Classification.DELAYED);
    // La evidencia no convierte en benigno lo que no es una anomalía.
    assertThat(CounterPolicy.classify(8, 7, ISSUED, Optional.of(ISSUED))).isEqualTo(Classification.INCREASING);
    assertThat(CounterPolicy.isAnomaly(0, 0)).isFalse();
    assertThat(CounterPolicy.isAnomaly(5, 5)).isTrue();
  }

  private static WebAuthnProperties properties(String rpId, String... origins) {
    return new WebAuthnProperties(rpId, "CMS", List.of(origins), Duration.ofMinutes(5), Duration.ofMinutes(5), 5);
  }

  @Test
  void configuracionWebAuthnValidaYNoValida() {
    properties("localhost", "http://localhost:4200");
    properties("admin.example.com", "https://admin.example.com");
    properties("example.com", "https://admin.example.com");
    for (String[] bad : new String[][] {
        {"localhost", "https://otro.example"}, {"admin.example.com", "http://admin.example.com"},
        {"admin.example.com", "https://admin.example.com/ruta"}, {"admin.example.com", "https://*.example.com"},
        {"admin.example.com", "https://notadmin.example.com"}, {"https://admin.example.com", "https://admin.example.com"},
        {"Admin.example.com", "https://admin.example.com"}, {"localhost:8080", "http://localhost:8080"}}) {
      assertThatThrownBy(() -> properties(bad[0], bad[1])).as(String.join(" ", bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new WebAuthnProperties("localhost", "CMS", List.of("http://localhost"),
        Duration.ofMinutes(11), Duration.ofMinutes(5), 5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebAuthnProperties("localhost", "CMS", List.of("http://localhost"),
        Duration.ofMinutes(5), Duration.ofMinutes(5), 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clavesHmac() {
    assertThatThrownBy(() -> new SecretKeys("")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SecretKeys("corta")).isInstanceOf(IllegalStateException.class);
    SecretKeys keys = new SecretKeys("x".repeat(32));
    SecretKeys other = new SecretKeys("y".repeat(32));
    assertThat(keys.recoveryCode("ABC")).hasSize(32).isEqualTo(keys.recoveryCode("ABC"))
        .isNotEqualTo(other.recoveryCode("ABC"));
    // Una clave por uso: el mismo valor da HMAC distintos para códigos y sesiones.
    assertThat(keys.recoveryCode("ABC")).isNotEqualTo(keys.sessionHash("ABC"));
  }

  @Test
  void formatoDeLosCodigos() {
    assertThat(RecoveryCodes.normalize("abcd-efgh-jkmn-pqrs")).isEqualTo("ABCDEFGHJKMNPQRS");
    assertThat(RecoveryCodes.normalize(" o1il 0000 1111 2222 ")).isEqualTo("0111000011112222");
    for (String bad : new String[] {null, "", "ABCD-EFGH-JKMN-PQR", "ABCD-EFGH-JKMN-PQRSX", "ABCD-EFGH-JKMN-PQRU",
        "x".repeat(65)}) {
      assertThatThrownBy(() -> RecoveryCodes.normalize(bad)).as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
