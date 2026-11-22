package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.security.devices.TrustedDevices;
import com.cristianatienza.cms.security.tool.ToolTestAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code recuperacion-emergencia} (6.2.6): con la API real y un usuario con todo lo que la
 * recuperación debe revocar (passkey, códigos, dispositivo y sesiones en F y en P2). La
 * herramienta completa, como cms_security_tool; sin secretos en la salida.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmergencyRecoveryTests extends SecurityTestSupport {

  static final String NEW_PASSWORD = "nueva-frase-de-paso-tras-la-emergencia";

  @TempDir Path dir;

  /** Usuario en F con códigos de recuperación y dispositivo de confianza, y otra sesión en P2. */
  record Victim(Account full, ApiClient p2, UUID userId) {}

  Victim victim() throws Exception {
    Account full = fullAccount();
    assertThat(full.client().post("/api/auth/recovery-codes", null).status()).isEqualTo(201);
    assertThat(full.client().cookie(TrustedDevices.COOKIE)).as("dispositivo de confianza emitido").isNotNull();
    return new Victim(full, passwordSession(full.email()), userIdOf(full.email()));
  }

  Path file(String password) throws Exception {
    return Files.writeString(dir.resolve("nueva-" + UUID.randomUUID()), password + "\n");
  }

  int recover(String email, Path file) {
    return ToolTestAccess.run("cms_security_tool", "--cms.tool=recuperacion-emergencia", "--cms.usuario=" + email,
        "--cms.nueva-contrasena-desde=" + file);
  }

  static int count(String sql, Object... params) throws Exception {
    return Integer.parseInt(query("cms_security_tool", sql, params).getFirst());
  }

  /** Lo que una recuperación deja en la cuenta (consultado como cms_security_tool). */
  static void assertRevoked(UUID userId) throws Exception {
    assertThat(count("SELECT count(*) FROM passkey_credential WHERE user_id = ?", userId)).as("passkeys").isZero();
    assertThat(count("SELECT count(*) FROM webauthn_user_handle WHERE user_id = ?", userId)).as("identificador").isZero();
    assertThat(count("SELECT count(*) FROM recovery_code WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL",
        userId)).as("códigos vigentes").isZero();
    assertThat(count("SELECT count(*) FROM trusted_device WHERE user_id = ? AND state IN ('ACTIVE', 'PENDING')", userId))
        .as("dispositivos vigentes").isZero();
    assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", userId.toString())).as("sesiones")
        .isZero();
    assertThat(query("cms_security_tool", "SELECT enroll_required FROM app_user WHERE id = ?", userId))
        .containsExactly("t");
  }

  @Test
  void revocaTodoYElSiguienteAccesoEsP1(CapturedOutput output) throws Exception {
    Victim v = victim();
    String email = v.full().email();
    int codes = count("SELECT count(*) FROM recovery_code WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL",
        v.userId());
    assertThat(codes).isEqualTo(10);

    assertThat(recover(email, file(NEW_PASSWORD))).isZero();
    assertThat(output.getOut()).contains("Recuperación de emergencia completada para el usuario " + v.userId()
        + ": passkeys borradas 1, identificador WebAuthn borrado 1, códigos invalidados 10, dispositivos revocados 1,"
        + " sesiones cerradas 2.");

    // Las sesiones anteriores (F y P2) ya no valen.
    assertThat(v.full().client().get("/api/auth/me").status()).isEqualTo(401);
    assertThat(v.p2().get("/api/auth/me").status()).isEqualTo(401);
    assertRevoked(v.userId());
    // cms_security_tool no puede leer revoked_reason (V9): basta el estado.
    assertThat(count("SELECT count(*) FROM trusted_device WHERE user_id = ? AND state = 'REVOKED'", v.userId())).isOne();
    // Auditoría: recuentos, máquina y operador declarado; sin sesión.
    assertThat(query("cms_security_tool", "SELECT concat_ws('|', outcome, session_hash IS NULL, details->>'tool',"
        + " details->>'declaredOperator', details->>'passkeys', details->>'recoveryCodes', details->>'devices',"
        + " details->>'sessions') FROM audit_event WHERE event = 'user.emergency_recovery' AND user_id = ?", v.userId()))
        .containsExactly("success|t|recuperacion-emergencia|" + ToolTestAccess.OPERATOR + "|1|10|1|2");

    // La contraseña anterior no vale; la nueva da P1 (aunque el navegador presente el dispositivo
    // revocado), y la passkey anterior ya no existe.
    ApiClient oldPassword = client();
    assertThat(oldPassword.login(email, PASSWORD).status()).isEqualTo(401);
    ApiClient next = v.full().client();
    assertThat(next.login(email, NEW_PASSWORD).body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
    assertThat(next.post("/api/auth/passkeys/authentication/options", null).status()).isEqualTo(403);
    // Una passkey nueva devuelve el acceso completo.
    TestAuthenticator replacement = enroll(next);
    assertThat(authenticate(next, replacement).body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");

    assertThat(output.getAll()).doesNotContain(NEW_PASSWORD).doesNotContain(PASSWORD).doesNotContain(email);
  }

  @Test
  void usuarioInexistenteOContrasenaDebilNoCambianNada(CapturedOutput output) throws Exception {
    Victim v = victim();
    assertThat(recover("nadie-" + UUID.randomUUID() + "@example.test", file(NEW_PASSWORD))).isEqualTo(3);
    assertThat(recover(v.full().email(), file("1qaz2wsx3edc4rfv"))).isEqualTo(2);
    assertThat(recover(v.full().email(), dir.resolve("no-existe"))).isEqualTo(2);
    assertThat(ToolTestAccess.run("cms_security_tool", "--cms.tool=recuperacion-emergencia",
        "--cms.usuario=" + v.full().email(), "--cms.contrasena-desde=" + file(NEW_PASSWORD)))
        .as("la recuperación exige --cms.nueva-contrasena-desde").isEqualTo(2);
    assertThat(ToolTestAccess.run("cms_tool", "--cms.tool=recuperacion-emergencia", "--cms.usuario=" + v.full().email(),
        "--cms.nueva-contrasena-desde=" + file(NEW_PASSWORD))).isEqualTo(1);
    assertIntact(v);
    assertThat(output.getAll()).doesNotContain(NEW_PASSWORD).doesNotContain(v.full().email());
  }

  @Test
  void siNoSePuedeAuditarNoCambiaNada() throws Exception {
    Victim v = victim();
    assertThatThrownBy(() -> ToolTestAccess.recoverWithFailingAudit(v.full().email(), file(NEW_PASSWORD)))
        .isInstanceOf(SQLException.class);
    assertIntact(v);
    assertThat(count("SELECT count(*) FROM audit_event WHERE event = 'user.emergency_recovery' AND user_id = ?",
        v.userId())).isZero();
  }

  /** Nada a medias: sesiones, passkey, códigos, dispositivo y contraseña siguen como estaban. */
  void assertIntact(Victim v) throws Exception {
    assertThat(v.full().client().get("/api/auth/me").body()).startsWith("{\"state\":\"AUTHENTICATED\"");
    assertThat(v.p2().get("/api/auth/me").status()).isEqualTo(200);
    assertThat(count("SELECT count(*) FROM passkey_credential WHERE user_id = ?", v.userId())).isOne();
    assertThat(count("SELECT count(*) FROM webauthn_user_handle WHERE user_id = ?", v.userId())).isOne();
    assertThat(count("SELECT count(*) FROM recovery_code WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL",
        v.userId())).isEqualTo(10);
    assertThat(count("SELECT count(*) FROM trusted_device WHERE user_id = ? AND state IN ('ACTIVE', 'PENDING')",
        v.userId())).isOne();
    assertThat(query("cms_security_tool", "SELECT enroll_required FROM app_user WHERE id = ?", v.userId()))
        .containsExactly("f");
    List<String> login = List.of(client().login(v.full().email(), PASSWORD).body());
    assertThat(login).containsExactly("{\"state\":\"PASSKEY_REQUIRED\"}");
  }
}
