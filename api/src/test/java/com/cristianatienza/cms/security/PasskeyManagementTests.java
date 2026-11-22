package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gestión de las passkeys propias (F; borrar exige verificación reciente). */
class PasskeyManagementTests extends SecurityTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void laListaNoMuestraMaterialCriptografico() throws Exception {
    Account account = fullAccount();
    var list = account.client().get("/api/auth/passkeys");
    assertThat(list.status()).isEqualTo(200);
    JsonNode passkeys = JSON.readTree(list.body());
    assertThat(passkeys).hasSize(1);
    JsonNode passkey = passkeys.get(0);
    assertThat(passkey.propertyNames()).containsExactlyInAnyOrder("id", "label", "createdAt", "lastUsedAt",
        "suspended", "backupEligible", "backupState", "transports");
    assertThat(passkey.path("label").asString()).isEqualTo("pruebas");
    assertThat(passkey.path("suspended").asBoolean()).isFalse();
  }

  @Test
  void nuncaSeBorraLaUltimaUtilizable() throws Exception {
    Account account = fullAccount();
    String id = JSON.readTree(account.client().get("/api/auth/passkeys").body()).get(0).path("id").asString();
    assertThat(account.client().delete("/api/auth/passkeys/" + id).status()).isEqualTo(409);
    enroll(account.client());
    assertThat(account.client().delete("/api/auth/passkeys/" + id).status()).isEqualTo(204);
    assertThat(JSON.readTree(account.client().get("/api/auth/passkeys").body())).hasSize(1);
  }

  @Test
  void lasDeOtroUsuarioNoExisten() throws Exception {
    Account other = fullAccount();
    String foreign = JSON.readTree(other.client().get("/api/auth/passkeys").body()).get(0).path("id").asString();
    Account account = fullAccount();
    enroll(account.client());
    assertThat(account.client().delete("/api/auth/passkeys/" + foreign).status()).isEqualTo(404);
    assertThat(account.client().delete("/api/auth/passkeys/" + UUID.randomUUID()).status()).isEqualTo(404);
    assertThat(JSON.readTree(other.client().get("/api/auth/passkeys").body())).hasSize(1);
  }
}
