package com.cristianatienza.cms.security.tool;

import com.cristianatienza.cms.security.AuthController;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Política de contraseñas de la herramienta de seguridad (fase 6.2.6): de 16 a 1024 caracteres, que
 * no contenga la parte local del correo, que no sea un único carácter repetido y que no figure en
 * la lista de contraseñas comunes (H1, {@value #LIST}). Los mensajes nunca repiten la contraseña.
 *
 * <p>Longitud: el mínimo se cuenta en puntos de código Unicode (lo que el usuario percibe como
 * caracteres); el máximo, en unidades UTF-16, el mismo límite con el que la API acepta el inicio de
 * sesión ({@link AuthController#MAX_PASSWORD}). La contraseña no se normaliza: la API la compara tal
 * cual llega.
 */
public final class PasswordPolicy {

  public static final int MIN_CODE_POINTS = 16;
  public static final int MAX_LENGTH = AuthController.MAX_PASSWORD;
  /** Hasta 4 bytes por carácter en UTF-8, más el salto de línea final. */
  static final int MAX_FILE_BYTES = 4 * MAX_LENGTH + 2;
  /** Una parte local más corta no se busca dentro de la contraseña (rechazaría casi cualquiera). */
  static final int MIN_LOCAL_PART = 3;
  static final String LIST = "security/common-passwords-16.txt";

  /** La contraseña o su archivo no cumplen la política (código de salida 2). */
  public static final class Violation extends RuntimeException {
    Violation(String message) {
      super(message, null, false, false);
    }
  }

  private final Set<String> common;

  public PasswordPolicy() {
    this.common = loadList();
  }

  int listSize() {
    return common.size();
  }

  /**
   * Lee la contraseña de un archivo de secreto: tamaño limitado, UTF-8 estricto, se ignora un único
   * salto de línea final (LF o CRLF) y no se admiten otros saltos ni caracteres de control. El
   * contenido nunca se repite en ningún mensaje.
   */
  public static String readSecretFile(Path file) {
    byte[] bytes;
    try {
      if (!Files.isRegularFile(file)) {
        throw new Violation("el archivo de la contraseña no existe o no es un archivo");
      }
      if (Files.size(file) > MAX_FILE_BYTES) {
        throw new Violation("el archivo de la contraseña es demasiado grande");
      }
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new Violation("no se puede leer el archivo de la contraseña");
    }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new Violation("el archivo de la contraseña no está en UTF-8 válido");
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
    if (text.endsWith("\r\n")) {
      text = text.substring(0, text.length() - 2);
    } else if (text.endsWith("\n")) {
      text = text.substring(0, text.length() - 1);
    }
    if (text.codePoints().anyMatch(Character::isISOControl)) {
      throw new Violation("la contraseña contiene saltos de línea u otros caracteres de control");
    }
    return text;
  }

  /** Comprueba la contraseña de la cuenta {@code email}. */
  public void check(String password, String email) {
    if (password.isEmpty()) {
      throw new Violation("la contraseña está vacía");
    }
    if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS) {
      throw new Violation("la contraseña tiene menos de " + MIN_CODE_POINTS + " caracteres");
    }
    if (password.length() > MAX_LENGTH) {
      throw new Violation("la contraseña tiene más de " + MAX_LENGTH + " caracteres");
    }
    if (password.codePoints().distinct().count() == 1) {
      throw new Violation("la contraseña es un único carácter repetido");
    }
    String lower = password.toLowerCase(Locale.ROOT);
    int at = email == null ? -1 : email.lastIndexOf('@');
    String local = at > 0 ? email.substring(0, at).toLowerCase(Locale.ROOT) : "";
    if (local.length() >= MIN_LOCAL_PART && lower.contains(local)) {
      throw new Violation("la contraseña contiene la parte local del correo");
    }
    if (common.contains(lower)) {
      throw new Violation("la contraseña figura en la lista de contraseñas comunes");
    }
  }

  private static Set<String> loadList() {
    Set<String> entries = new HashSet<>();
    try (InputStream in = PasswordPolicy.class.getClassLoader().getResourceAsStream(LIST)) {
      if (in == null) {
        throw new IllegalStateException("falta la lista de contraseñas comunes: " + LIST);
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty() && !line.startsWith("#")) {
          entries.add(line.toLowerCase(Locale.ROOT));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("no se puede leer la lista de contraseñas comunes", e);
    }
    if (entries.isEmpty()) {
      throw new IllegalStateException("la lista de contraseñas comunes está vacía");
    }
    return Set.copyOf(entries);
  }
}
