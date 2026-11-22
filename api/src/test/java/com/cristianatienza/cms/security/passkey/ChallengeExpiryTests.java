package com.cristianatienza.cms.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.passkey.ChallengeStore.Ceremony;
import com.cristianatienza.cms.testsupport.ApiDatabase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Caducidad de los challenges con el reloj de PostgreSQL (vida de 1 s en este test). */
@SpringBootTest(properties = "cms.security.webauthn.challenge-ttl=1s")
class ChallengeExpiryTests {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    ApiDatabase.register(registry);
  }

  @Autowired ChallengeStore challenges;

  @Test
  void unChallengeCaducadoNoSeConsumeYLaLimpiezaLoBorra() throws Exception {
    UUID user = ChallengeStoreTests.createUser();
    String session = UUID.randomUUID().toString();
    challenges.issue(session, user, Ceremony.AUTHENTICATE, new byte[32], "{}", Map.of());
    Thread.sleep(1300);
    assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).isEmpty();
    assertThat(ChallengeStoreTests.rows(user)).as("sigue hasta la limpieza").isEqualTo(1);
    challenges.purgeExpired();
    assertThat(ChallengeStoreTests.rows(user)).isZero();
  }
}
