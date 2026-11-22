package com.cristianatienza.cms.security.recovery;

import com.cristianatienza.cms.security.SecretKeys;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Códigos de recuperación (fase 6.2.3): 10 por lote, de 80 bits, en base32 de Crockford
 * ({@code XXXX-XXXX-XXXX-XXXX}). Se muestran una sola vez y la base de datos solo guarda su
 * HMAC-SHA-256 con una clave de la API. La búsqueda por HMAC no filtra nada útil por tiempos: sin
 * la clave, el HMAC no revela el código.
 *
 * <p>Uso único: el canje es un único {@code UPDATE ... WHERE used_at IS NULL ... RETURNING},
 * confirmado en su propia transacción; con peticiones simultáneas, solo una lo consigue.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RecoveryCodes {

  public static final int COUNT = 10;
  static final int LENGTH = 16;
  static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;
  private final SecretKeys keys;

  public RecoveryCodes(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.requiresNew = new TransactionTemplate(transactions);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.keys = keys;
  }

  /** Lote nuevo: invalida los códigos vigentes y devuelve los nuevos (la única vez que existen en claro). */
  public List<String> generate(UUID userId) {
    UUID batch = UUID.randomUUID();
    List<String> codes = new ArrayList<>(COUNT);
    while (codes.size() < COUNT) {
      String code = randomCode();
      if (!codes.contains(code)) {
        codes.add(code);
      }
    }
    requiresNew.executeWithoutResult(status -> {
      jdbc.update("UPDATE recovery_code SET invalidated_at = now()"
          + " WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", userId);
      for (String code : codes) {
        jdbc.update("INSERT INTO recovery_code (id, user_id, batch_id, code_hmac) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), userId, batch, keys.recoveryCode(code));
      }
    });
    return codes.stream().map(RecoveryCodes::format).toList();
  }

  /** Canjea un código ya normalizado; true solo para la única petición que lo consigue. */
  public boolean redeem(UUID userId, String normalizedCode) {
    byte[] hmac = keys.recoveryCode(normalizedCode);
    return Boolean.TRUE.equals(requiresNew.execute(status -> !jdbc.query(
        "UPDATE recovery_code SET used_at = now() WHERE user_id = ? AND code_hmac = ?"
            + " AND used_at IS NULL AND invalidated_at IS NULL RETURNING id",
        (rs, i) -> rs.getObject(1), userId, hmac).isEmpty()));
  }

  /** Fin de la recuperación: los códigos que queden dejan de valer. */
  public int invalidateRemaining(UUID userId) {
    return jdbc.update("UPDATE recovery_code SET invalidated_at = now()"
        + " WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", userId);
  }

  public int remaining(UUID userId) {
    return jdbc.queryForObject("SELECT count(*) FROM recovery_code"
        + " WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", Integer.class, userId);
  }

  /**
   * Normaliza lo que teclea el usuario: mayúsculas, sin guiones ni espacios, y las confusiones
   * habituales de Crockford (O→0, I/L→1). Un formato imposible es una solicitud no válida.
   */
  public static String normalize(String input) {
    if (input == null || input.length() > 64) {
      throw new IllegalArgumentException("código con formato no válido");
    }
    StringBuilder out = new StringBuilder(LENGTH);
    for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
      if (c == '-' || c == ' ') {
        continue;
      }
      char mapped = switch (c) {
        case 'O' -> '0';
        case 'I', 'L' -> '1';
        default -> c;
      };
      if (ALPHABET.indexOf(mapped) < 0) {
        throw new IllegalArgumentException("código con formato no válido");
      }
      out.append(mapped);
    }
    if (out.length() != LENGTH) {
      throw new IllegalArgumentException("código con formato no válido");
    }
    return out.toString();
  }

  /** 80 bits aleatorios: 16 símbolos de 5 bits. */
  static String randomCode() {
    byte[] bytes = new byte[10];
    RANDOM.nextBytes(bytes);
    StringBuilder out = new StringBuilder(LENGTH);
    int buffer = 0;
    int bits = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    return out.toString();
  }

  static String format(String code) {
    return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12) + "-" + code.substring(12);
  }
}
