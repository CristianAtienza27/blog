package com.cristianatienza.cms.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.security.SessionAbsoluteExpiryTests.MutableClock;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Base de las pruebas de la 6.2.4: límites REALES (también el de la IP), reloj controlado y un
 * proxy de confianza solo en los tests (127.0.0.1), para que cada cliente pueda llegar desde su
 * propia IP con X-Forwarded-For. En producción no hay proxy de confianza hasta la 6.9.
 */
@Import(SessionAbsoluteExpiryTests.ClockOverride.class)
@TestPropertySource(properties = {
    "cms.security.limits.ip-failures=20",
    "server.forward-headers-strategy=native",
    "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
    "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For"})
abstract class LimitsTestSupport extends SecurityTestSupport {

  @Autowired MutableClock clock;

  private static int nextIp = 1;

  /** Una IP de pruebas distinta en cada llamada (documentación, RFC 5737). */
  static synchronized String freshIp() {
    int n = nextIp++;
    return "198.51." + (n / 250) + "." + (1 + n % 250);
  }

  ApiClient clientFrom(String ip) {
    return client().from(ip);
  }

  ApiClient.Response login(ApiClient client, String email, String password) throws Exception {
    return client.login(email, password);
  }

  /** Cuenta en F por el camino real desde una IP; deja la cookie __Host-DEVICE en el cliente. */
  Account fullAccountFrom(String ip) throws Exception {
    String email = createUser(true, true);
    ApiClient client = clientFrom(ip);
    assertThat(client.login(email, PASSWORD).status()).isEqualTo(200);
    TestAuthenticator authenticator = enroll(client);
    assertThat(authenticate(client, authenticator).status()).isEqualTo(200);
    return new Account(client, authenticator, email);
  }

  /** Otra sesión (otro «navegador») con la misma cookie de dispositivo, desde otra IP. */
  ApiClient sameDevice(ApiClient browser, String ip) {
    ApiClient other = clientFrom(ip);
    String device = browser.cookie("__Host-DEVICE");
    if (device != null) {
      other.cookies().put("__Host-DEVICE", device);
    }
    return other;
  }

  /** Sentencias como propietario (cms_migrator + SET ROLE cms_owner), para simular fallos. */
  static void asOwner(String... statements) throws Exception {
    try (Connection c = connectAs("cms_migrator"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SET LOCAL ROLE cms_owner");
      for (String sql : statements) {
        s.execute(sql);
      }
      c.commit();
    }
  }

  /**
   * Estados de los tokens del usuario, en el orden de la cadena de cada familia (de la raíz a los
   * sucesores). El reloj de los tests está parado: varios tokens comparten created_at, así que el
   * orden por fecha sería arbitrario. A igual profundidad: RETIRED, ACTIVE, SUPERSEDED, PENDING.
   */
  static List<String> deviceStates(String email) throws Exception {
    return query("cms_api", "WITH RECURSIVE chain AS ("
        + " SELECT id, state, family_id, 0 AS depth FROM trusted_device WHERE user_id = ? AND predecessor_id IS NULL"
        + " UNION ALL SELECT d.id, d.state, d.family_id, c.depth + 1 FROM trusted_device d JOIN chain c"
        + " ON d.predecessor_id = c.id)"
        + " SELECT state FROM chain ORDER BY (SELECT min(t.created_at) FROM trusted_device t"
        + " WHERE t.family_id = chain.family_id), family_id, depth,"
        + " array_position(ARRAY['RETIRED','ACTIVE','SUPERSEDED','PENDING','REVOKED'], state)", userIdOf(email));
  }
}
