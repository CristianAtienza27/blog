package com.cristianatienza.cms.security.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cristianatienza.cms.security.AuthController;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Política de contraseñas y lectura del archivo de secreto (6.2.6): sin base de datos. */
class PasswordPolicyTests {

  static final String EMAIL = "administradora@example.test";
  static final String GOOD = "caballo-grapa-bateria-correcta";
  final PasswordPolicy policy = new PasswordPolicy();

  @TempDir Path dir;

  private void rejected(String password, String reason) {
    assertThatThrownBy(() -> policy.check(password, EMAIL)).isInstanceOf(PasswordPolicy.Violation.class)
        .hasMessageContaining(reason)
        .satisfies(e -> {
          if (!password.isEmpty()) {
            assertThat(e.getMessage()).as("nunca repite la contraseña").doesNotContain(password);
          }
        });
  }

  @Test
  void longitudEnCaracteresYLimiteDeLaApi() {
    rejected("a1b2c3d4e5f6g7h", "menos de 16");
    assertThatCode(() -> policy.check("a1b2c3d4e5f6g7h8", EMAIL)).doesNotThrowAnyException();
    // 16 caracteres fuera del plano básico (32 unidades UTF-16): cuentan como 16, no como 32.
    String emoji = "😀😃😄😁😆😅😂🤣😊😇🙂🙃😉😌😍🥰";
    assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(16);
    assertThatCode(() -> policy.check(emoji, EMAIL)).doesNotThrowAnyException();
    rejected(emoji.substring(0, emoji.length() - 2), "menos de 16");
    // El máximo es el mismo con el que la API acepta el inicio de sesión.
    assertThat(PasswordPolicy.MAX_LENGTH).isEqualTo(AuthController.MAX_PASSWORD).isEqualTo(1024);
    String max = "ab".repeat(512);
    assertThatCode(() -> policy.check(max, EMAIL)).doesNotThrowAnyException();
    rejected(max + "c", "más de 1024");
  }

  @Test
  void contenidosNoPermitidos() {
    rejected("", "vacía");
    rejected("x".repeat(40), "único carácter repetido");
    rejected("😀".repeat(20), "único carácter repetido");
    rejected("mi-clave-ADMINISTRADORA-2026", "parte local del correo");
    // Una parte local de menos de 3 caracteres no se busca (rechazaría casi todo).
    assertThatCode(() -> policy.check("ab-caballo-grapa-bateria", "ab@example.test")).doesNotThrowAnyException();
    assertThatCode(() -> policy.check(GOOD, EMAIL)).doesNotThrowAnyException();
  }

  @Test
  void listaDeContrasenasComunesSinDistinguirMayusculas() {
    // 233 entradas filtradas de la lista NCSC; dos solo se distinguen por mayúsculas.
    assertThat(policy.listSize()).isEqualTo(232);
    rejected("1q2w3e4r5t6y7u8i9o0p", "lista de contraseñas comunes");
    rejected("1Q2W3E4R5T6Y7U8I9O0P", "lista de contraseñas comunes");
    rejected("1qaz2wsx3edc4rfv", "lista de contraseñas comunes");
  }

  @Test
  void archivoDeSecreto() throws Exception {
    assertThat(PasswordPolicy.readSecretFile(write("a.txt", GOOD + "\n"))).isEqualTo(GOOD);
    assertThat(PasswordPolicy.readSecretFile(write("b.txt", GOOD + "\r\n"))).isEqualTo(GOOD);
    assertThat(PasswordPolicy.readSecretFile(write("c.txt", GOOD))).isEqualTo(GOOD);
    // Solo se ignora un salto final; los espacios se conservan (la API no recorta nada).
    assertThat(PasswordPolicy.readSecretFile(write("d.txt", " " + GOOD + " \n"))).isEqualTo(" " + GOOD + " ");
    unreadable(write("e.txt", GOOD + "\n\n"), "caracteres de control");
    unreadable(write("f.txt", "primera\nsegunda-linea-larga"), "caracteres de control");
    unreadable(write("g.txt", GOOD + "\t"), "caracteres de control");
    Path invalid = dir.resolve("h.txt");
    Files.write(invalid, new byte[] {'a', 'b', (byte) 0xC3, (byte) 0x28, 'c'});
    unreadable(invalid, "UTF-8");
    unreadable(write("i.txt", "a".repeat(PasswordPolicy.MAX_FILE_BYTES + 1)), "demasiado grande");
    unreadable(dir.resolve("no-existe.txt"), "no existe");
    unreadable(dir, "no existe");
  }

  private Path write(String name, String content) throws Exception {
    return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
  }

  private static void unreadable(Path file, String reason) {
    assertThatThrownBy(() -> PasswordPolicy.readSecretFile(file)).isInstanceOf(PasswordPolicy.Violation.class)
        .hasMessageContaining(reason)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(GOOD).doesNotContain("primera"));
  }
}
