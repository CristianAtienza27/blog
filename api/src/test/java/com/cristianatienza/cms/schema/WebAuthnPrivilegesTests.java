package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Permisos de las tablas de V8 (passkeys, challenges, aserciones, códigos y fallos por sesión) con
 * conexiones reales de cada login: privilegios efectivos (has_*_privilege cuenta lo heredado y lo
 * de PUBLIC), intentos reales y las garantías de los triggers, también frente al propietario.
 */
class WebAuthnPrivilegesTests extends PostgresSchemaTest {

  private static final String[] LOGINS = {"cms_api", "cms_tool", "cms_security_tool", "cms_maintenance", "cms_migrator"};
  private static final String USER = "00000000-0000-0000-0000-0000000000a1";
  private static final String PASSKEY = "00000000-0000-0000-0000-0000000000b1";

  private static List<String> rows(String login, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs(login); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  /** Privilegios de tabla efectivos de cada login, como «login:PRIV,PRIV». */
  private static List<String> tablePrivileges(String table) throws Exception {
    return rows("cms_security_tool", "SELECT r || ':' || string_agg(p, ',' ORDER BY o) FROM"
        + " unnest(ARRAY['" + String.join("','", LOGINS) + "']) r,"
        + " unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) WITH ORDINALITY AS x(p, o)"
        + " WHERE has_table_privilege(r, 'public." + table + "', p) GROUP BY r ORDER BY r");
  }

  /** Columnas con un privilegio efectivo, por login («login:col,col» o «login:-»). */
  private static List<String> columns(String table, String privilege) throws Exception {
    return rows("cms_security_tool", "SELECT r || ':' || coalesce(string_agg(a.attname::text, ',' ORDER BY a.attnum)"
        + " FILTER (WHERE has_column_privilege(r, a.attrelid, a.attnum, '" + privilege + "')), '-')"
        + " FROM unnest(ARRAY['" + String.join("','", LOGINS) + "']) r, pg_catalog.pg_attribute a"
        + " WHERE a.attrelid = 'public." + table + "'::regclass AND a.attnum > 0 AND NOT a.attisdropped"
        + " GROUP BY r ORDER BY r");
  }

  private static void seed() throws Exception {
    txAs("cms_security_tool", "INSERT INTO app_user (id, email, password_hash, role) VALUES ('" + USER + "',"
        + " 'v8@example.test', '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN')");
    txAs(APP_LOGIN, "INSERT INTO passkey_credential (id, user_id, credential_id, public_key_cose, aaguid, sign_count,"
        + " uv_initialized, backup_eligible, backup_state) VALUES ('" + PASSKEY + "', '" + USER + "',"
        + " decode(repeat('ab', 32), 'hex'), decode(repeat('cd', 77), 'hex'), gen_random_uuid(), 5, true, false, false)",
        "INSERT INTO webauthn_user_handle (user_id, handle) VALUES ('" + USER + "', decode(repeat('ef', 32), 'hex'))",
        "INSERT INTO recovery_code (id, user_id, batch_id, code_hmac) VALUES (gen_random_uuid(), '" + USER + "',"
            + " gen_random_uuid(), decode(repeat('12', 32), 'hex'))",
        "INSERT INTO webauthn_assertion (credential_id, user_id, ceremony, challenge_id, challenge_issued_at,"
            + " counter_at_issue, verified_at, received_counter, stored_before, stored_after, outcome, classification)"
            + " VALUES ('" + PASSKEY + "', '" + USER + "', 'authenticate', gen_random_uuid(), now(), 4, now(), 5, 4, 5,"
            + " 'accepted', 'increasing')");
  }

  @Test
  void privilegiosDeTablaEfectivos() throws Exception {
    assertThat(tablePrivileges("passkey_credential"))
        .containsExactly("cms_api:SELECT,INSERT,DELETE", "cms_security_tool:DELETE");
    assertThat(tablePrivileges("webauthn_user_handle"))
        .containsExactly("cms_api:SELECT,INSERT", "cms_security_tool:DELETE");
    assertThat(tablePrivileges("webauthn_challenge")).containsExactly("cms_api:SELECT,INSERT,DELETE");
    assertThat(tablePrivileges("webauthn_assertion")).containsExactly("cms_api:SELECT,INSERT");
    assertThat(tablePrivileges("recovery_code")).containsExactly("cms_api:SELECT,INSERT");
    assertThat(tablePrivileges("auth_session_failure")).containsExactly("cms_api:SELECT,INSERT,DELETE");
  }

  @Test
  void privilegiosDeColumnaEfectivos() throws Exception {
    assertThat(columns("passkey_credential", "UPDATE")).containsExactly(
        "cms_api:sign_count,backup_state,last_used_at,anomaly_count,last_anomaly_at,suspended_at",
        "cms_maintenance:-", "cms_migrator:-", "cms_security_tool:-", "cms_tool:-");
    assertThat(columns("passkey_credential", "SELECT")).contains("cms_security_tool:user_id", "cms_tool:-",
        "cms_maintenance:-", "cms_migrator:-");
    assertThat(columns("recovery_code", "UPDATE")).containsExactly("cms_api:used_at,invalidated_at",
        "cms_maintenance:-", "cms_migrator:-", "cms_security_tool:invalidated_at", "cms_tool:-");
    assertThat(columns("recovery_code", "SELECT")).contains("cms_security_tool:user_id,used_at,invalidated_at");
    assertThat(columns("webauthn_user_handle", "SELECT")).contains("cms_security_tool:user_id");
    assertThat(columns("auth_session_failure", "UPDATE")).contains("cms_api:failures", "cms_security_tool:-");
    for (String table : List.of("webauthn_challenge", "webauthn_assertion", "auth_session_failure")) {
      assertThat(columns(table, "SELECT")).as(table).contains("cms_security_tool:-", "cms_tool:-",
          "cms_maintenance:-", "cms_migrator:-");
    }
    // Nadie salvo el dueño de la función puede tocar enroll_required (D5).
    assertThat(columns("app_user", "UPDATE")).contains("cms_api:last_login_at");
  }

  @Test
  void intentosRealesRechazados() throws Exception {
    seed();
    // La herramienta de seguridad no lee claves públicas, HMAC, aserciones ni challenges.
    for (String sql : List.of("SELECT public_key_cose FROM passkey_credential", "SELECT credential_id FROM passkey_credential",
        "SELECT handle FROM webauthn_user_handle", "SELECT code_hmac FROM recovery_code",
        "SELECT count(*) FROM webauthn_assertion", "SELECT count(*) FROM webauthn_challenge",
        "SELECT count(*) FROM auth_session_failure", "UPDATE recovery_code SET used_at = now()",
        "INSERT INTO passkey_credential (id) VALUES (gen_random_uuid())")) {
      deniedAs("cms_security_tool", sql);
    }
    // La API no cambia la identidad de una passkey ni enroll_required, ni borra aserciones o códigos.
    for (String sql : List.of("UPDATE passkey_credential SET public_key_cose = 'x'",
        "UPDATE passkey_credential SET user_id = gen_random_uuid()", "UPDATE app_user SET enroll_required = false",
        "DELETE FROM recovery_code", "DELETE FROM webauthn_assertion", "TRUNCATE webauthn_assertion",
        "UPDATE webauthn_assertion SET outcome = 'accepted'", "DELETE FROM webauthn_user_handle")) {
      deniedAs(APP_LOGIN, sql);
    }
    for (String login : List.of(IMPORTER_LOGIN, "cms_maintenance")) {
      rejectedAs(login, "SELECT count(*) FROM public.passkey_credential");
      rejectedAs(login, "SELECT count(*) FROM public.recovery_code");
    }
    // Lo que la herramienta de seguridad sí puede: revocar por usuario (6.2.6).
    txAs("cms_security_tool", "UPDATE recovery_code SET invalidated_at = now() WHERE user_id = '" + USER + "'"
        + " AND used_at IS NULL AND invalidated_at IS NULL", "DELETE FROM passkey_credential WHERE user_id = '" + USER + "'",
        "DELETE FROM webauthn_user_handle WHERE user_id = '" + USER + "'");
    assertThat(rows("cms_api", "SELECT count(*) FROM passkey_credential")).containsExactly("0");
  }

  @Test
  void lasGarantiasDeLosTriggersValenTambienParaElPropietario() throws Exception {
    seed();
    // El contador no baja, una suspensión no se retira y las anomalías no se borran.
    txAsFails(APP_LOGIN, CHECK, "UPDATE passkey_credential SET sign_count = 4");
    txAs(APP_LOGIN, "UPDATE passkey_credential SET suspended_at = now(), anomaly_count = 1");
    txAsFails(APP_LOGIN, CHECK, "UPDATE passkey_credential SET suspended_at = NULL");
    txAsFails(APP_LOGIN, CHECK, "UPDATE passkey_credential SET anomaly_count = 0");
    asOwnerFails(CHECK, "UPDATE passkey_credential SET sign_count = 0");
    asOwnerFails(NO_PRIVILEGE, "UPDATE passkey_credential SET public_key_cose = 'x'");
    // Aserciones: de solo inserción, ni siquiera el propietario las modifica o borra.
    asOwnerFails(NO_PRIVILEGE, "UPDATE webauthn_assertion SET outcome = 'rejected', classification = 'weak_anomaly'");
    asOwnerFails(NO_PRIVILEGE, "DELETE FROM webauthn_assertion");
    asOwnerFails(NO_PRIVILEGE, "TRUNCATE webauthn_assertion");
    // Códigos: usado o invalidado, una sola vez.
    txAs(APP_LOGIN, "UPDATE recovery_code SET used_at = now()");
    txAsFails(APP_LOGIN, NO_PRIVILEGE, "UPDATE recovery_code SET used_at = now() + interval '1 day'");
    txAsFails(APP_LOGIN, NO_PRIVILEGE, "UPDATE recovery_code SET used_at = NULL");
    asOwnerFails(NO_PRIVILEGE, "UPDATE recovery_code SET code_hmac = decode(repeat('34', 32), 'hex')");
  }

  @Test
  void sinViasIndirectas() throws Exception {
    seed();
    // Relaciones que cms_security_tool puede leer (aunque sea una columna): ninguna copia de lo secreto.
    assertThat(rows("cms_security_tool", "SELECT n.nspname || '.' || c.relname FROM pg_class c"
        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','v','m','f')"
        + " AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'"
        + " AND has_any_column_privilege('cms_security_tool', c.oid, 'SELECT') ORDER BY 1"))
        .containsExactly("public.app_user", "public.audit_event", "public.audit_event_ip",
            "public.flyway_schema_history", "public.login_attempt", "public.passkey_credential",
            "public.recovery_code", "public.spring_session", "public.trusted_device", "public.webauthn_user_handle");
    // Funciones SECURITY DEFINER que puede ejecutar cada login.
    String definers = "SELECT string_agg(p.proname, ',' ORDER BY p.proname) FROM pg_proc p"
        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE p.prosecdef AND n.nspname = 'public'"
        + " AND has_function_privilege('%s', p.oid, 'EXECUTE')";
    assertThat(rows("cms_security_tool", definers.formatted("cms_security_tool"))).containsExactly((String) null);
    assertThat(rows("cms_security_tool", definers.formatted("cms_api")))
        .containsExactly(
            "complete_passkey_enrollment,create_article_revision,publish_article,restore_article_revision,unpublish_article");
    // cms_maintenance: solo la purga de retención (V9).
    assertThat(rows("cms_security_tool", definers.formatted("cms_maintenance"))).containsExactly("purge_audit");
    // Estadísticas: pg_stats solo enseña columnas legibles (tras un ANALYZE del dueño).
    asOwner("ANALYZE passkey_credential", "ANALYZE recovery_code", "ANALYZE webauthn_user_handle");
    assertThat(rows("cms_api", "SELECT count(*) FROM pg_stats WHERE tablename = 'recovery_code'"
        + " AND attname = 'code_hmac'")).containsExactly("1");
    assertThat(rows("cms_security_tool", "SELECT attname FROM pg_stats WHERE tablename IN"
        + " ('passkey_credential', 'recovery_code', 'webauthn_user_handle') ORDER BY 1"))
        .doesNotContain("public_key_cose", "credential_id", "code_hmac", "handle");
  }
}
