package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Ataque distribuido contra una cuenta (criterio 13): más de 50 fallos por hora desde muchas IP
 * activan el modo de ataque, pero la cuenta nunca se bloquea para un dispositivo de confianza, y un
 * navegador nuevo conserva un intento cada 15 min por IP. Una cookie falsa o caducada no exime.
 */
class DistributedAttackTests extends LimitsTestSupport {

  private void attack(String email) throws Exception {
    for (int i = 0; i < 51; i++) {
      assertThat(clientFrom(freshIp()).login(email, "contraseña-del-atacante").status()).isEqualTo(401);
    }
  }

  @Test
  void elDispositivoDeConfianzaNoSeVeAfectadoYElNavegadorNuevoTieneUnIntentoCada15Min() throws Exception {
    Account account = fullAccountFrom(freshIp());
    assertThat(account.client().cookie("__Host-DEVICE")).as("cookie emitida al completar F").isNotBlank();
    attack(account.email());

    // Navegador de confianza desde una IP limpia: falla una vez y entra igualmente (sin modo de ataque).
    ApiClient trusted = sameDevice(account.client(), freshIp());
    assertThat(trusted.login(account.email(), "tecleo-mal").status()).isEqualTo(401);
    assertThat(trusted.login(account.email(), PASSWORD).status()).isEqualTo(200);
    assertThat(authenticate(trusted, account.authenticator()).status()).isEqualTo(200);

    // Navegador nuevo: un intento por IP y 15 min.
    String ip = freshIp();
    assertThat(clientFrom(ip).login(account.email(), "tecleo-mal").status()).isEqualTo(401);
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).as("segundo intento").isEqualTo(429);
    clock.advance(Duration.ofMinutes(15).plusSeconds(1));
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).isEqualTo(200);
    // El primer intento de un navegador nuevo desde otra IP limpia, si es correcto, entra.
    assertThat(clientFrom(freshIp()).login(account.email(), PASSWORD).status()).isEqualTo(200);
  }

  @Test
  void unaCookieFalsaOCaducadaNoExime() throws Exception {
    Account account = fullAccountFrom(freshIp());
    attack(account.email());
    String ip = freshIp();
    ApiClient forged = clientFrom(ip);
    forged.cookies().put("__Host-DEVICE", "token-inventado-por-el-atacante");
    assertThat(forged.login(account.email(), "mal").status()).isEqualTo(401);
    ApiClient again = clientFrom(ip);
    again.cookies().put("__Host-DEVICE", "token-inventado-por-el-atacante");
    assertThat(again.login(account.email(), PASSWORD).status()).as("sin alivio").isEqualTo(429);

    // Caducada (90 días): sin confianza.
    clock.advance(Duration.ofDays(91));
    String ip2 = freshIp();
    attack(account.email());
    assertThat(sameDevice(account.client(), ip2).login(account.email(), "mal").status()).isEqualTo(401);
    assertThat(sameDevice(account.client(), ip2).login(account.email(), PASSWORD).status()).isEqualTo(429);
  }

  @Test
  void elModoDeAtaqueTerminaTrasUnaHoraPorDebajoDelUmbral() throws Exception {
    Account account = fullAccountFrom(freshIp());
    attack(account.email());
    String ip = freshIp();
    clientFrom(ip).login(account.email(), "mal");
    assertThat(clientFrom(ip).login(account.email(), PASSWORD).status()).isEqualTo(429);
    clock.advance(Duration.ofHours(1).plusMinutes(1));
    String other = freshIp();
    clientFrom(other).login(account.email(), "mal");
    assertThat(clientFrom(other).login(account.email(), PASSWORD).status()).as("fuera del modo de ataque")
        .isEqualTo(200);
  }
}
