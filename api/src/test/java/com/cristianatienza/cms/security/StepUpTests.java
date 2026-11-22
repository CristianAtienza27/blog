package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.SessionAbsoluteExpiryTests.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Verificación reciente (step-up) desde F, con el reloj controlado: no cambia de usuario ni degrada
 * F, caduca a los 5 min, cierra la sesión tras 5 fallos y la exigen las acciones aprobadas.
 */
@Import(SessionAbsoluteExpiryTests.ClockOverride.class)
class StepUpTests extends SecurityTestSupport {

  @Autowired MutableClock clock;

  @Test
  void laVerificacionRecienteCaducaYSeRenuevaSinCambiarElEstado() throws Exception {
    Account account = fullAccount();
    ApiClient client = account.client();
    // Recién llegado a F: verificación reciente.
    assertThat(client.post("/api/auth/recovery-codes", null).status()).isEqualTo(201);
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));
    assertThat(client.post("/api/auth/recovery-codes", null).status()).isEqualTo(403);
    assertThat(client.delete("/api/auth/passkeys/" + java.util.UUID.randomUUID()).status()).isEqualTo(403);
    assertThat(client.post("/api/auth/passkeys/registration/options", null).status()).isEqualTo(403);
    // Lo protector no la exige.
    String before = client.cookie(SecurityConfig.SESSION_COOKIE);
    var stepUp = stepUp(client, account.authenticator());
    assertThat(stepUp.status()).isEqualTo(204);
    assertThat(client.cookie(SecurityConfig.SESSION_COOKIE)).as("sesión nueva").isNotEqualTo(before);
    assertThat(sessionRows(sessionId(before))).isZero();
    assertThat(client.get("/api/auth/me").body()).startsWith("{\"state\":\"AUTHENTICATED\"");
    assertThat(client.post("/api/auth/recovery-codes", null).status()).isEqualTo(201);
    assertThat(client.post("/api/auth/passkeys/registration/options", null).status()).isEqualTo(200);
  }

  @Test
  void cerrarTodasLasSesionesNoExigeVerificacionReciente() throws Exception {
    Account account = fullAccount();
    clock.advance(Duration.ofMinutes(10));
    assertThat(account.client().post("/api/auth/sessions/revoke-all", null).status()).isEqualTo(204);
  }

  @Test
  void conLaPasskeyDeOtroUsuarioNoSeRenuevaNiCambiaDeUsuario() throws Exception {
    Account other = fullAccount();
    Account account = fullAccount();
    clock.advance(Duration.ofMinutes(6));
    var options = account.client().post("/api/auth/step-up/options", null);
    var response = account.client().post("/api/auth/step-up", other.authenticator().assertion(options.body()));
    assertThat(response.status()).isEqualTo(401);
    // Sigue en F (no se degrada), sin verificación reciente.
    assertThat(account.client().get("/api/auth/me").body()).startsWith("{\"state\":\"AUTHENTICATED\"");
    assertThat(account.client().post("/api/auth/recovery-codes", null).status()).isEqualTo(403);
  }

  @Test
  void cincoFallosDeStepUpCierranLaSesion() throws Exception {
    Account account = fullAccount();
    account.authenticator().tamperSignature = true;
    for (int i = 1; i <= 4; i++) {
      assertThat(stepUp(account.client(), account.authenticator()).status()).isEqualTo(401);
      assertThat(account.client().get("/api/auth/me").status()).as("fallo " + i).isEqualTo(200);
    }
    assertThat(stepUp(account.client(), account.authenticator()).status()).isEqualTo(401);
    assertThat(account.client().get("/api/auth/me").status()).isEqualTo(401);
  }

  @Test
  void soloDesdeF() throws Exception {
    String email = createUser(true, true);
    ApiClient client = client();
    client.login(email, PASSWORD);
    assertThat(client.post("/api/auth/step-up/options", null).status()).isEqualTo(403);
    enroll(client);
    assertThat(client.post("/api/auth/step-up/options", null).status()).as("P2").isEqualTo(403);
  }
}
