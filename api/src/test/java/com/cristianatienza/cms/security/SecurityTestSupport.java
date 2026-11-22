package com.cristianatienza.cms.security;

import com.cristianatienza.cms.testsupport.ApiDatabase;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de los tests de seguridad de la 6.2.2: la API real en un puerto aleatorio, conectada como
 * cms_api a un PostgreSQL 18.6 migrado por cms_migrator. Los usuarios de prueba los crea
 * cms_security_tool (el login de la herramienta de seguridad) y las comprobaciones en la base de
 * datos se hacen con logins reales, nunca con el superusuario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class SecurityTestSupport {

  static final String PASSWORD = "una-contraseña-de-prueba-larga";

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    ApiDatabase.register(registry);
  }

  @Value("${local.server.port}")
  int port;

  @Autowired PasswordHashing hashing;

  @Autowired javax.sql.DataSource dataSource;

  /**
   * Esquema desde cero CON la aplicación en marcha (tests editoriales, 6.4): tras recrear los tipos
   * (dominios), las sentencias ya preparadas en las conexiones del pool fallarían con «cached plan
   * must not change result type»; se desalojan para que el pool abra conexiones nuevas. En producción
   * el esquema nunca se recrea con la API arrancada.
   */
  void freshSchema() {
    TestDatabase.migrateFromScratch();
    ((com.zaxxer.hikari.HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
  }

  @SuppressWarnings("rawtypes")
  @Autowired
  FindByIndexNameSessionRepository sessions;

  /**
   * Simula el estado F (contraseña + passkey) en una sesión existente, con el propio repositorio de
   * sesiones de la aplicación (conexión cms_api): la passkey llega en la 6.2.3.
   */
  @SuppressWarnings("unchecked")
  void promoteToFull(String cookieValue) {
    Session session = (Session) sessions.findById(sessionId(cookieValue));
    SecurityContext current = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    Authentication password = current.getAuthentication();
    List<GrantedAuthority> authorities = new ArrayList<>(password.getAuthorities());
    authorities.add(FactorGrantedAuthority.fromAuthority(AuthStates.FACTOR_WEBAUTHN));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
        UsernamePasswordAuthenticationToken.authenticated(password.getPrincipal(), null, authorities)));
    sessions.save(session);
  }

  ApiClient client() {
    return new ApiClient(port);
  }

  static Connection connectAs(String login) throws Exception {
    return DriverManager.getConnection(TestDatabase.url(), login, TestDatabase.password(login));
  }

  /** Crea un usuario como cms_security_tool y devuelve su correo (único por test). */
  String createUser(boolean enabled, boolean enrollRequired) throws Exception {
    return createUser(enabled, enrollRequired, "ADMIN");
  }

  String createUser(boolean enabled, boolean enrollRequired, String role) throws Exception {
    String email = "u-" + UUID.randomUUID() + "@example.test";
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("INSERT INTO app_user (id, email, password_hash, role, enabled,"
            + " enroll_required) VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, email);
      ps.setString(3, hashing.encode(PASSWORD));
      ps.setString(4, role);
      ps.setBoolean(5, enabled);
      ps.setBoolean(6, enrollRequired);
      ps.executeUpdate();
    }
    return email;
  }

  /** Id del usuario, leído como cms_security_tool. */
  static UUID userIdOf(String email) throws Exception {
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT id FROM app_user WHERE email = ?")) {
      ps.setString(1, email);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  // --- Flujos con passkey (6.2.3), siempre por la API real ---------------------------------------

  /** Una sesión con su usuario y su autenticador de pruebas. */
  record Account(ApiClient client, TestAuthenticator authenticator, String email) {}

  /** Desde P1 (o R): opciones y registro; la sesión pasa a P2. */
  TestAuthenticator enroll(ApiClient client) throws Exception {
    return enroll(client, new TestAuthenticator());
  }

  TestAuthenticator enroll(ApiClient client, TestAuthenticator authenticator) throws Exception {
    var options = client.post("/api/auth/passkeys/registration/options", null);
    org.assertj.core.api.Assertions.assertThat(options.status()).as(options.body()).isEqualTo(200);
    var registered = client.post("/api/auth/passkeys/registration", authenticator.register(options.body(), "pruebas"));
    org.assertj.core.api.Assertions.assertThat(registered.status()).as(registered.body()).isEqualTo(201);
    return authenticator;
  }

  /** Desde P2: opciones y aserción. */
  ApiClient.Response authenticate(ApiClient client, TestAuthenticator authenticator) throws Exception {
    var options = client.post("/api/auth/passkeys/authentication/options", null);
    org.assertj.core.api.Assertions.assertThat(options.status()).as(options.body()).isEqualTo(200);
    return client.post("/api/auth/passkeys/authentication", authenticator.assertion(options.body()));
  }

  /** Desde F: verificación reciente. */
  ApiClient.Response stepUp(ApiClient client, TestAuthenticator authenticator) throws Exception {
    var options = client.post("/api/auth/step-up/options", null);
    org.assertj.core.api.Assertions.assertThat(options.status()).as(options.body()).isEqualTo(200);
    return client.post("/api/auth/step-up", authenticator.assertion(options.body()));
  }

  /** Usuario nuevo de ese rol, en F por el camino real: contraseña, registro y passkey. */
  Account fullAccount(String role) throws Exception {
    String email = createUser(true, true, role);
    ApiClient client = client();
    org.assertj.core.api.Assertions.assertThat(client.login(email, PASSWORD).status()).isEqualTo(200);
    TestAuthenticator authenticator = enroll(client);
    var full = authenticate(client, authenticator);
    org.assertj.core.api.Assertions.assertThat(full.body()).isEqualTo("{\"state\":\"AUTHENTICATED\"}");
    return new Account(client, authenticator, email);
  }

  Account fullAccount() throws Exception {
    return fullAccount("ADMIN");
  }

  /** Nueva sesión del mismo usuario en P2 (contraseña). */
  ApiClient passwordSession(String email) throws Exception {
    ApiClient client = client();
    org.assertj.core.api.Assertions.assertThat(client.login(email, PASSWORD).status()).isEqualTo(200);
    return client;
  }

  /** Lo que el inicio de sesión puede o no cambiar de un usuario. */
  record UserRow(Instant lastLoginAt, Instant updatedAt, boolean enrollRequired) {}

  /** Lee el usuario como cms_security_tool. */
  static UserRow userRow(String email) throws Exception {
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement(
            "SELECT last_login_at, updated_at, enroll_required FROM app_user WHERE email = ?")) {
      ps.setString(1, email);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        OffsetDateTime lastLogin = rs.getObject(1, OffsetDateTime.class);
        return new UserRow(lastLogin == null ? null : lastLogin.toInstant(),
            rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getBoolean(3));
      }
    }
  }

  /** Primera columna de una consulta con la conexión real de un login (nunca el superusuario). */
  static List<String> query(String login, String sql, Object... params) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs(login); PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** Identificador de sesión (Spring Session codifica la cookie en Base64). */
  static String sessionId(String cookieValue) {
    return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
  }

  /**
   * Filas de sesión con ese identificador, consultadas como cms_api: es el único login que puede
   * leer SESSION_ID (cms_security_tool solo ve principal_name).
   */
  static int sessionRows(String sessionId) throws Exception {
    try (Connection c = connectAs("cms_api");
        PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM spring_session WHERE session_id = ?")) {
      ps.setString(1, sessionId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
