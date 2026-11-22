package com.cristianatienza.cms.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.passkey.ChallengeStore.Ceremony;
import com.cristianatienza.cms.security.passkey.ChallengeStore.Consumed;
import com.cristianatienza.cms.testsupport.ApiDatabase;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El almacén de challenges directamente (conexión real de cms_api): consumo único entre muchos
 * hilos, ligado a la sesión y a la ceremonia, sustitución por uno nuevo y consumo confirmado aunque
 * se revierta una transacción externa.
 */
@SpringBootTest
class ChallengeStoreTests {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    ApiDatabase.register(registry);
  }

  @Autowired ChallengeStore challenges;
  @Autowired PlatformTransactionManager transactions;

  static UUID createUser() throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = DriverManager.getConnection(TestDatabase.url(), "cms_security_tool",
            TestDatabase.password("cms_security_tool"));
        PreparedStatement ps = c.prepareStatement("INSERT INTO app_user (id, email, password_hash, role)"
            + " VALUES (?, ?, '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN')")) {
      ps.setObject(1, id);
      ps.setString(2, "c-" + id + "@example.test");
      ps.executeUpdate();
    }
    return id;
  }

  static int rows(UUID user) throws Exception {
    try (Connection c = DriverManager.getConnection(TestDatabase.url(), "cms_api", TestDatabase.password("cms_api"));
        PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM webauthn_challenge WHERE user_id = ?")) {
      ps.setObject(1, user);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private UUID issue(String session, UUID user, Ceremony ceremony) {
    return challenges.issue(session, user, ceremony, new byte[32], "{}", Map.of());
  }

  @Test
  void deCincuentaHilosSoloUnoConsume() throws Exception {
    UUID user = createUser();
    String session = UUID.randomUUID().toString();
    issue(session, user, Ceremony.AUTHENTICATE);
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Optional<Consumed>>> calls = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
        return challenges.consume(session, Ceremony.AUTHENTICATE);
      }));
    }
    start.countDown();
    assertThat(calls.stream().map(CompletableFuture::join).filter(Optional::isPresent).count()).isEqualTo(1);
    assertThat(rows(user)).isZero();
  }

  @Test
  void ligadoASuSesionYASuCeremonia() throws Exception {
    UUID user = createUser();
    String session = UUID.randomUUID().toString();
    UUID id = issue(session, user, Ceremony.AUTHENTICATE);
    assertThat(challenges.consume(UUID.randomUUID().toString(), Ceremony.AUTHENTICATE)).as("otra sesión").isEmpty();
    assertThat(challenges.consume(session, Ceremony.STEP_UP)).as("otra ceremonia").isEmpty();
    assertThat(challenges.consume(session, Ceremony.REGISTER)).as("otra ceremonia").isEmpty();
    assertThat(rows(user)).isEqualTo(1);
    assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).map(Consumed::id).contains(id);
    assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).as("reutilizado").isEmpty();
  }

  @Test
  void unoNuevoSustituyeAlAnteriorDeLaMismaSesionYCeremonia() throws Exception {
    UUID user = createUser();
    String session = UUID.randomUUID().toString();
    issue(session, user, Ceremony.AUTHENTICATE);
    UUID newer = issue(session, user, Ceremony.AUTHENTICATE);
    UUID otherCeremony = issue(session, user, Ceremony.STEP_UP);
    assertThat(rows(user)).isEqualTo(2);
    assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).map(Consumed::id).contains(newer);
    assertThat(challenges.consume(session, Ceremony.STEP_UP)).map(Consumed::id).contains(otherCeremony);
  }

  @Test
  void emisionesSimultaneasDeLaMismaSesionNoFallan() throws Exception {
    UUID user = createUser();
    String session = UUID.randomUUID().toString();
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<UUID>> calls = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      calls.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
        return issue(session, user, Ceremony.AUTHENTICATE);
      }));
    }
    start.countDown();
    calls.forEach(CompletableFuture::join);
    assertThat(rows(user)).as("como mucho uno vivo").isEqualTo(1);
  }

  @Test
  void unaTransaccionExternaRevertidaNoDevuelveElChallenge() throws Exception {
    UUID user = createUser();
    String session = UUID.randomUUID().toString();
    issue(session, user, Ceremony.AUTHENTICATE);
    TransactionTemplate outer = new TransactionTemplate(transactions);
    outer.executeWithoutResult(status -> {
      assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).isPresent();
      status.setRollbackOnly();
    });
    assertThat(rows(user)).isZero();
    assertThat(challenges.consume(session, Ceremony.AUTHENTICATE)).isEmpty();
  }
}
