package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.tool.ToolTestAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code crear-admin} (6.2.6): la herramienta completa, como en su contenedor, conectada como
 * cms_security_tool, frente a la API real. La contraseña llega de un archivo y nunca aparece en la
 * salida ni en los registros (capturados), tampoco el correo.
 */
@ExtendWith(OutputCaptureExtension.class)
class CreateAdminTests extends SecurityTestSupport {

  static final String ADMIN_PASSWORD = "piedra-papel-tijera-lagarto-spock";

  @TempDir Path dir;

  private Path passwordFile(String password) throws Exception {
    return Files.writeString(dir.resolve("cms_admin_password-" + UUID.randomUUID()), password + "\n");
  }

  private static String newEmail() {
    return "admin-" + UUID.randomUUID() + "@example.test";
  }

  private static int createAdmin(String login, String email, Path file) {
    return ToolTestAccess.run(login, "--cms.tool=crear-admin", "--cms.usuario=" + email,
        "--cms.contrasena-desde=" + file);
  }

  private static int users(String email) throws Exception {
    return Integer.parseInt(query("cms_security_tool", "SELECT count(*) FROM app_user WHERE lower(email) = lower(?)",
        email).getFirst());
  }

  @Test
  void creaUnAdministradorQueEmpiezaEnP1(CapturedOutput output) throws Exception {
    String email = newEmail();
    assertThat(createAdmin("cms_security_tool", email, passwordFile(ADMIN_PASSWORD))).isZero();
    UUID id = userIdOf(email);
    assertThat(output.getOut()).contains("Administrador creado: " + id);

    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement(
            "SELECT role, enabled, enroll_required, password_hash FROM app_user WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getString(1)).isEqualTo("ADMIN");
        assertThat(rs.getBoolean(2)).isTrue();
        assertThat(rs.getBoolean(3)).as("registro pendiente").isTrue();
        assertThat(rs.getString(4)).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hashing.matches(ADMIN_PASSWORD, rs.getString(4))).isTrue();
      }
    }
    // Auditoría en la misma transacción: sin sesión, con la máquina y el operador declarado.
    List<String> audit = query("cms_security_tool", "SELECT concat_ws('|', outcome, session_hash IS NULL,"
        + " details->>'tool', details->>'declaredOperator', details->>'host' IS NOT NULL, details->>'role')"
        + " FROM audit_event WHERE event = 'user.created' AND user_id = ?", id);
    assertThat(audit).containsExactly("success|t|crear-admin|" + ToolTestAccess.OPERATOR + "|t|ADMIN");

    // Primer acceso: P1, y a F solo después de registrar una passkey y autenticarse con ella.
    ApiClient client = client();
    var login = client.login(email, ADMIN_PASSWORD);
    assertThat(login.body()).isEqualTo("{\"state\":\"PASSKEY_ENROLLMENT_REQUIRED\"}");
    assertThat(client.get("/api/auth/passkeys").status()).isEqualTo(403);
    TestAuthenticator authenticator = enroll(client);
    assertThat(client.get("/api/auth/me").body()).startsWith("{\"state\":\"PASSKEY_REQUIRED\"");
    assertThat(authenticate(client, authenticator).body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");

    assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD).doesNotContain(email);
  }

  @Test
  void correoExistenteSinDistinguirMayusculas(CapturedOutput output) throws Exception {
    String email = newEmail();
    assertThat(createAdmin("cms_security_tool", email, passwordFile(ADMIN_PASSWORD))).isZero();
    String hashBefore = query("cms_security_tool", "SELECT password_hash FROM app_user WHERE email = ?", email).getFirst();
    assertThat(createAdmin("cms_security_tool", email.toUpperCase(), passwordFile("otra-" + ADMIN_PASSWORD))).isEqualTo(3);
    assertThat(users(email)).isOne();
    assertThat(query("cms_security_tool", "SELECT password_hash FROM app_user WHERE email = ?", email))
        .containsExactly(hashBefore);
    assertThat(output.getOut()).contains("ya existe un usuario con ese correo");
    assertThat(output.getAll()).doesNotContain(ADMIN_PASSWORD).doesNotContainIgnoringCase(email);
  }

  @Test
  void contrasenaDebilOArgumentosNoValidos(CapturedOutput output) throws Exception {
    String email = newEmail();
    String local = email.substring(0, email.indexOf('@'));
    for (String weak : new String[] {"corta-15-chars!", "1q2w3e4r5t6y7u8i9o0p", "z".repeat(30), "x-" + local + "-x"}) {
      assertThat(createAdmin("cms_security_tool", email, passwordFile(weak))).as(weak).isEqualTo(2);
      assertThat(output.getAll()).doesNotContain(weak);
    }
    assertThat(createAdmin("cms_security_tool", email, dir.resolve("no-existe"))).isEqualTo(2);
    assertThat(ToolTestAccess.run("cms_security_tool", "--cms.tool=crear-admin", "--cms.usuario=" + email)).isEqualTo(2);
    assertThat(ToolTestAccess.run("cms_security_tool", "--cms.tool=crear-admin",
        "--cms.contrasena-desde=" + passwordFile(ADMIN_PASSWORD))).isEqualTo(2);
    assertThat(createAdmin("cms_security_tool", "sin-arroba", passwordFile(ADMIN_PASSWORD))).isEqualTo(2);
    // Una contraseña en un argumento se rechaza sin repetirla (y sin crear nada).
    assertThat(ToolTestAccess.run("cms_security_tool", "--cms.tool=crear-admin", "--cms.usuario=" + email,
        "--cms.contrasena=" + ADMIN_PASSWORD)).isEqualTo(2);
    assertThat(output.getOut()).contains("la contraseña no se acepta como argumento");
    assertThat(users(email)).isZero();
    assertThat(output.getOut()).doesNotContain(ADMIN_PASSWORD).doesNotContain(email);
  }

  @Test
  void soloConCmsSecurityTool(CapturedOutput output) throws Exception {
    String email = newEmail();
    for (String login : new String[] {"cms_tool", "cms_api"}) {
      assertThat(createAdmin(login, email, passwordFile(ADMIN_PASSWORD))).as(login).isEqualTo(1);
    }
    assertThat(output.getOut()).contains("debe conectarse como cms_security_tool");
    assertThat(users(email)).isZero();
  }
}
