package com.cristianatienza.cms.content.publication;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Quién pide una operación de publicación (R1′, fase 6.3.4). Solo hechos verificables:
 *
 * <ul>
 *   <li>{@code api}: el usuario autenticado (ADMIN con passkey reciente), en {@code requested_by}.
 *   <li>{@code herramienta}: el login de PostgreSQL de la sesión ({@code session_user}, autenticado
 *       por PostgreSQL) y el nombre de la máquina. Nunca un operador declarado: no afirma quién es la
 *       persona (eso corresponde al acceso al sistema).
 *   <li>{@code reconciliacion}: la propia API, sin usuario.
 * </ul>
 */
public record Actor(UUID userId, String via, Map<String, String> facts) {

  public static Actor api(UUID userId) {
    return new Actor(userId, "api", Map.of());
  }

  public static Actor tool(String databaseUser, String host) {
    Map<String, String> facts = new LinkedHashMap<>();
    facts.put("dbUser", clean(databaseUser));
    facts.put("host", clean(host));
    return new Actor(null, "herramienta", Map.copyOf(facts));
  }

  static final Actor RECONCILIATION = new Actor(null, "reconciliacion", Map.of());

  private static String clean(String value) {
    if (value == null || value.isBlank()) {
      return "desconocido";
    }
    String safe = value.replaceAll("[^A-Za-z0-9._@:-]", "_");
    return safe.length() > 100 ? safe.substring(0, 100) : safe;
  }
}
