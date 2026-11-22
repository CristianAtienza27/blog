package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Caducidad absoluta: 12 h desde la autenticación, aunque la sesión se siga usando (reloj controlado). */
class SessionAbsoluteExpiryTests extends SecurityTestSupport {

  /** Reloj que el test puede adelantar. */
  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-14T09:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class ClockOverride {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;

  @Test
  void caducaALasDoceHorasDeIniciarSesionAunqueSeUse() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    String cookie = client.cookie(SecurityConfig.SESSION_COOKIE);
    clock.advance(Duration.ofHours(11).plusMinutes(59));
    assertThat(client.get("/api/auth/me").status()).isEqualTo(200);
    clock.advance(Duration.ofMinutes(1));
    assertThat(client.get("/api/auth/me").status()).isEqualTo(401);
    assertThat(sessionRows(sessionId(cookie))).isZero();
  }
}
