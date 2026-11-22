package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.audit.CsrfRejections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auditoría persistente: cada evento deja su fila con los campos previstos y la IP aparte; ningún
 * secreto aparece en audit_event, audit_event_ip ni en los registros capturados. Agregación CSRF
 * por IP y minuto, con capacidad limitada (E5).
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditTests extends LimitsTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired CsrfRejections csrf;

  private static List<String> audit(UUID user) throws Exception {
    return query("cms_security_tool", "SELECT e.event || ':' || e.outcome || ':' || coalesce(host(i.ip), '-')"
        + " FROM audit_event e LEFT JOIN audit_event_ip i ON i.event_id = e.id WHERE e.user_id = ? ORDER BY e.occurred_at",
        user);
  }

  @Test
  void cadaEventoDejaSuFilaYNingunSecretoAparece(CapturedOutput output) throws Exception {
    String ip = freshIp();
    String email = createUser(true, true);
    ApiClient client = clientFrom(ip).agent("Navegador/" + "x".repeat(300));
    assertThat(client.login(email, "contraseña-equivocada-secreta").status()).isEqualTo(401);
    client.login(email, PASSWORD);
    TestAuthenticator authenticator = enroll(client);
    authenticate(client, authenticator);
    List<String> codes = new ArrayList<>();
    JSON.readTree(client.post("/api/auth/recovery-codes", null).body()).path("codes").forEach(n -> codes.add(n.asString()));
    ApiClient other = clientFrom(ip);
    other.login(email, PASSWORD);
    other.post("/api/auth/recovery", "{\"code\": \"" + codes.get(0) + "\"}");
    other.post("/api/auth/logout", null);
    UUID user = userIdOf(email);

    assertThat(audit(user)).contains("login.password_failed:failure:" + ip, "login.password_succeeded:success:" + ip,
        "passkey.registered:success:" + ip, "login.passkey_succeeded:success:" + ip,
        "recovery.codes_generated:success:" + ip, "recovery.code_used:success:" + ip, "session.logout:success:" + ip);
    assertThat(query("cms_security_tool", "SELECT max(length(user_agent)) FROM audit_event WHERE user_id = ?", user))
        .as("agente de usuario truncado a 200").containsExactly("200");
    assertThat(query("cms_security_tool", "SELECT count(*) FROM audit_event WHERE user_id = ? AND session_hash IS NOT NULL"
        + " AND length(session_hash) = 32", user)).isNotEqualTo(List.of("0"));

    // Ningún secreto: contraseñas (también la errónea), códigos, cookies, tokens.
    List<String> secrets = new ArrayList<>(List.of(PASSWORD, "contraseña-equivocada-secreta",
        sessionId(client.cookie(SecurityConfig.SESSION_COOKIE)), client.cookie(SecurityConfig.CSRF_COOKIE),
        client.cookie("__Host-DEVICE")));
    codes.forEach(c -> {
      secrets.add(c);
      secrets.add(c.replace("-", ""));
    });
    String everything = String.join("\n", query("cms_security_tool",
        "SELECT e::text || coalesce(i::text, '') FROM audit_event e LEFT JOIN audit_event_ip i ON i.event_id = e.id"));
    for (String secret : secrets) {
      assertThat(everything).as("auditoría").doesNotContain(secret);
      assertThat(output.getAll()).as("registros").doesNotContain(secret);
    }
  }

  @Test
  void unUsuarioInexistenteSoloDejaElHmacDeLoTecleado(CapturedOutput output) throws Exception {
    String typed = "alguien-escribio-su-contraseña-aqui-" + System.nanoTime();
    assertThat(clientFrom(freshIp()).login(typed, "otra-cosa").status()).isEqualTo(401);
    List<String> rows = query("cms_security_tool", "SELECT details::text FROM audit_event"
        + " WHERE event = 'login.password_failed' AND user_id IS NULL AND details->>'reason' = 'unknown_user'");
    assertThat(rows).isNotEmpty();
    assertThat(String.join("\n", rows)).doesNotContain(typed).contains("typedEmailHmac");
    assertThat(output.getAll()).doesNotContain(typed);
  }

  @Test
  void losRechazosCsrfSeAgreganPorIpYMinutoConCapacidadLimitada() throws Exception {
    csrf.flush();
    String ip = freshIp();
    for (int i = 0; i < 3; i++) {
      ApiClient client = clientFrom(ip);
      client.get("/api/auth/csrf");
      assertThat(client.postWithoutCsrf("/api/auth/login", "{}").status()).isEqualTo(403);
    }
    // Más IP que la capacidad (1000): ya hay 1 (la de arriba); de 1005 nuevas caben 999 y 6 desbordan.
    for (int i = 0; i < 1005; i++) {
      csrf.record("203.0." + (i / 250) + "." + (i % 250));
    }
    csrf.flush();
    assertThat(query("cms_security_tool", "SELECT e.details->>'count' FROM audit_event e JOIN audit_event_ip i"
        + " ON i.event_id = e.id WHERE e.event = 'security.csrf_rejected' AND host(i.ip) = ?", ip)).containsExactly("3");
    assertThat(query("cms_security_tool", "SELECT details->>'count' FROM audit_event"
        + " WHERE event = 'security.csrf_rejected_overflow' ORDER BY occurred_at DESC LIMIT 1")).containsExactly("6");
    // Tras vaciar, el minuto siguiente empieza de cero: el mapa no crece.
    csrf.record("203.0.9.9");
    csrf.flush();
    assertThat(query("cms_security_tool", "SELECT count(*) FROM audit_event e JOIN audit_event_ip i"
        + " ON i.event_id = e.id WHERE e.event = 'security.csrf_rejected' AND host(i.ip) = '203.0.9.9'"))
        .containsExactly("1");
  }
}
