package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Inmutabilidad de la auditoría y retención (criterio 21), con conexiones reales: nadie modifica ni
 * borra auditoría fuera de purge_audit, que respeta sus mínimos, deja su evento y solo puede
 * ejecutar cms_maintenance. Límite documentado: el propietario y el superusuario podrían
 * desactivar los triggers.
 */
class AuditImmutabilityTests extends PostgresSchemaTest {

  private static String one(String login, String sql) throws Exception {
    try (Connection c = connectAs(login); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getString(1);
    }
  }

  /** Un evento con IP y una aserción WebAuthn de hace {@code days} días, como propietario. */
  private static void seed(String id, int days) throws Exception {
    txAs("cms_security_tool", "INSERT INTO app_user (id, email, password_hash, role) SELECT '00000000-0000-0000-0000-0000000000f1',"
        + " 'audit@example.test', '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN'"
        + " WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE id = '00000000-0000-0000-0000-0000000000f1')");
    asOwner("INSERT INTO audit_event (id, occurred_at, event, outcome) VALUES ('" + id + "', now() - interval '" + days
            + " days', 'login.password_failed', 'failure')",
        "INSERT INTO audit_event_ip (event_id, ip) VALUES ('" + id + "', '198.51.100.7')",
        "INSERT INTO webauthn_assertion (credential_id, user_id, ceremony, challenge_id, challenge_issued_at,"
            + " counter_at_issue, verified_at, received_counter, stored_before, stored_after, outcome, classification)"
            + " VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000f1', 'authenticate', gen_random_uuid(),"
            + " now() - interval '" + days + " days', 0, now() - interval '" + days + " days', 0, 0, 0, 'accepted', 'no_counter')");
  }

  @Test
  void nadieModificaNiBorraLaAuditoria() throws Exception {
    seed("00000000-0000-0000-0000-00000000a001", 1);
    for (String login : List.of(APP_LOGIN, "cms_security_tool", "cms_maintenance")) {
      for (String sql : List.of("UPDATE audit_event SET outcome = 'success'", "DELETE FROM audit_event",
          "TRUNCATE audit_event", "DELETE FROM audit_event_ip", "UPDATE audit_event_ip SET ip = '10.0.0.1'")) {
        rejectedAs(login, sql);
      }
    }
    // Tampoco el propietario: lo impiden los triggers (también TRUNCATE).
    asOwnerFails(NO_PRIVILEGE, "UPDATE audit_event SET outcome = 'success'");
    asOwnerFails(NO_PRIVILEGE, "DELETE FROM audit_event");
    asOwnerFails(NO_PRIVILEGE, "TRUNCATE audit_event, audit_event_ip");
    asOwnerFails(NO_PRIVILEGE, "DELETE FROM audit_event_ip");
    // Ni con una variable de sesión que intente hacerse pasar por la retención.
    asOwnerFails(NO_PRIVILEGE, "SET LOCAL cms.audit_retention = 'on'", "DELETE FROM audit_event");
    // La API y la herramienta de seguridad sí pueden INSERTAR; solo la herramienta lee.
    rejectedAs(APP_LOGIN, "SELECT count(*) FROM audit_event");
    assertThat(one("cms_security_tool", "SELECT count(*) FROM audit_event")).isNotEqualTo("0");
    // Los triggers existen y están activos.
    assertThat(one("cms_security_tool", "SELECT string_agg(tgname, ',' ORDER BY tgname) FROM pg_trigger"
        + " WHERE tgrelid IN ('audit_event'::regclass, 'audit_event_ip'::regclass) AND tgenabled = 'O' AND NOT tgisinternal"))
        .isEqualTo("audit_event_immutable,audit_event_ip_immutable,audit_event_ip_no_truncate,audit_event_no_truncate");
  }

  @Test
  void laPurgaRespetaSusMinimosYDejaConstancia() throws Exception {
    seed("00000000-0000-0000-0000-00000000b400", 400);
    seed("00000000-0000-0000-0000-00000000b100", 100);
    seed("00000000-0000-0000-0000-00000000b010", 10);
    // Cortes más recientes que los mínimos: rechazados.
    txAsFails("cms_maintenance", INVALID_PARAMETER, "SELECT purge_audit(now() - interval '300 days', now() - interval '90 days')");
    txAsFails("cms_maintenance", INVALID_PARAMETER, "SELECT purge_audit(now() - interval '365 days', now() - interval '30 days')");
    // Solo cms_maintenance puede ejecutarla.
    for (String login : List.of(APP_LOGIN, "cms_security_tool", IMPORTER_LOGIN)) {
      rejectedAs(login, "SELECT purge_audit(now() - interval '400 days', now() - interval '100 days')");
    }
    asOwnerFails(NO_PRIVILEGE, "SELECT purge_audit(now() - interval '400 days', now() - interval '100 days')");

    String result = one("cms_maintenance", "SELECT purge_audit(now() - interval '365 days', now() - interval '90 days')::text");
    assertThat(result).contains("\"events\": 1", "\"ips\": 2", "\"assertions\": 1");
    assertThat(one("cms_security_tool", "SELECT string_agg(id::text, ',' ORDER BY id) FROM audit_event WHERE id::text LIKE '00000000-0000-0000-0000-00000000b%'"))
        .as("el de 400 días se borra; los de 100 y 10 quedan").isEqualTo("00000000-0000-0000-0000-00000000b010,"
            + "00000000-0000-0000-0000-00000000b100");
    assertThat(one("cms_security_tool", "SELECT count(*) FROM audit_event_ip WHERE event_id::text LIKE '00000000-0000-0000-0000-00000000b%'"))
        .as("la IP de 100 días se borra; la de 10 queda").isEqualTo("1");
    assertThat(one("cms_security_tool", "SELECT count(*) FROM audit_event WHERE event = 'audit.retention_purge'"))
        .isEqualTo("1");
    assertThat(one(APP_LOGIN, "SELECT count(*) FROM webauthn_assertion"
        + " WHERE verified_at < now() - interval '365 days'")).isEqualTo("0");
  }

  @Test
  void permisosEfectivosDeV9() throws Exception {
    String privileges = "SELECT string_agg(r || ':' || p, ',' ORDER BY r, o) FROM unnest(ARRAY['cms_api','cms_tool',"
        + "'cms_security_tool','cms_maintenance','cms_migrator']) r, unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE',"
        + "'TRUNCATE']) WITH ORDINALITY AS x(p, o) WHERE has_table_privilege(r, 'public.%s', p)";
    assertThat(one("cms_security_tool", privileges.formatted("audit_event")))
        .isEqualTo("cms_api:INSERT,cms_security_tool:SELECT,cms_security_tool:INSERT");
    assertThat(one("cms_security_tool", privileges.formatted("audit_event_ip")))
        .isEqualTo("cms_api:INSERT,cms_security_tool:SELECT,cms_security_tool:INSERT");
    assertThat(one("cms_security_tool", privileges.formatted("login_attempt")))
        .isEqualTo("cms_api:SELECT,cms_api:INSERT,cms_api:DELETE,cms_security_tool:DELETE");
    assertThat(one("cms_security_tool", privileges.formatted("trusted_device")))
        .isEqualTo("cms_api:SELECT,cms_api:INSERT");
    assertThat(one("cms_security_tool", privileges.formatted("trusted_device_presentation")))
        .isEqualTo("cms_api:SELECT,cms_api:INSERT,cms_api:DELETE");
    String columns = "SELECT coalesce(string_agg(a.attname::text, ',' ORDER BY a.attnum), '-') FROM pg_attribute a"
        + " WHERE a.attrelid = 'public.%s'::regclass AND a.attnum > 0 AND has_column_privilege('%s', a.attrelid, a.attnum, '%s')";
    assertThat(one("cms_security_tool", columns.formatted("trusted_device", "cms_api", "UPDATE")))
        .as("la API nunca cambia el token ni su identidad")
        .isEqualTo("state,superseded_by,confirmed_at,retired_at,last_used_at,revoked_at,revoked_reason");
    assertThat(one("cms_security_tool", columns.formatted("trusted_device", "cms_security_tool", "SELECT")))
        .isEqualTo("user_id,state");
    assertThat(one("cms_security_tool", columns.formatted("trusted_device", "cms_security_tool", "UPDATE")))
        .isEqualTo("state,revoked_at,revoked_reason");
    assertThat(one("cms_security_tool", columns.formatted("login_attempt", "cms_security_tool", "SELECT")))
        .isEqualTo("key_type,key_hash");
    assertThat(one("cms_security_tool", columns.formatted("trusted_device_presentation", "cms_api", "UPDATE")))
        .isEqualTo("followed_by_full");
    // cms_tool y cms_maintenance: nada en las tablas nuevas (cms_maintenance solo EXECUTE de la purga).
    for (String table : List.of("audit_event", "login_attempt", "trusted_device", "trusted_device_presentation")) {
      assertThat(one("cms_security_tool", "SELECT has_any_column_privilege('cms_tool', 'public." + table + "', 'SELECT')"
          + " OR has_any_column_privilege('cms_maintenance', 'public." + table + "', 'SELECT')")).as(table).isEqualTo("f");
    }
    // PUBLIC no tiene EXECUTE en ninguna función nueva.
    assertThat(one("cms_security_tool", "SELECT count(*) FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace"
        + " AND p.proname IN ('guard_audit_immutable', 'guard_trusted_device_update', 'purge_audit')"
        + " AND (p.proacl IS NULL OR EXISTS (SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee = 0))")).isEqualTo("0");
  }

  @Test
  void unDispositivoRevocadoNoSeReactivaYSuTokenNoCambia() throws Exception {
    txAs("cms_security_tool", "INSERT INTO app_user (id, email, password_hash, role) VALUES ('00000000-0000-0000-0000-0000000000e1',"
        + " 'dev@example.test', '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN')");
    txAs(APP_LOGIN, "INSERT INTO trusted_device (id, user_id, family_id, token_hmac, state, created_at, expires_at)"
        + " VALUES (gen_random_uuid(), '00000000-0000-0000-0000-0000000000e1', gen_random_uuid(),"
        + " decode(repeat('ab', 32), 'hex'), 'ACTIVE', now(), now() + interval '90 days')");
    txAs("cms_security_tool", "UPDATE trusted_device SET state = 'REVOKED', revoked_at = now(), revoked_reason = 'cli'"
        + " WHERE user_id = '00000000-0000-0000-0000-0000000000e1'");
    txAsFails(APP_LOGIN, CHECK, "UPDATE trusted_device SET state = 'ACTIVE', revoked_at = NULL, revoked_reason = NULL");
    deniedAs(APP_LOGIN, "UPDATE trusted_device SET token_hmac = decode(repeat('cd', 32), 'hex')");
    asOwnerFails(NO_PRIVILEGE, "UPDATE trusted_device SET token_hmac = decode(repeat('cd', 32), 'hex')");
    deniedAs("cms_security_tool", "SELECT token_hmac FROM trusted_device");
  }
}
