package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

/**
 * complete_passkey_enrollment (D5): la única forma que tiene la API de cambiar enroll_required,
 * con conexiones reales. Solo pasa a false, solo con una passkey utilizable y solo desde la API.
 */
class EnrollmentFunctionTests extends PostgresSchemaTest {

  private static final String USER = "00000000-0000-0000-0000-0000000000c1";

  private static String one(String login, String sql) throws Exception {
    try (Connection c = connectAs(login); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String enrollRequired() throws Exception {
    return one("cms_security_tool", "SELECT enroll_required::text FROM app_user WHERE id = '" + USER + "'");
  }

  private static String passkey(String id, boolean suspended) {
    return "INSERT INTO passkey_credential (id, user_id, credential_id, public_key_cose, aaguid, sign_count,"
        + " uv_initialized, backup_eligible, backup_state, suspended_at) VALUES ('" + id + "', '" + USER + "',"
        + " decode(md5('" + id + "') || md5('" + id + "x'), 'hex'), decode(repeat('cd', 77), 'hex'), gen_random_uuid(),"
        + " 0, true, false, false, " + (suspended ? "now()" : "NULL") + ")";
  }

  @Test
  void soloPasaAFalseConUnaPasskeyUtilizable() throws Exception {
    txAs("cms_security_tool", "INSERT INTO app_user (id, email, password_hash, role) VALUES ('" + USER + "',"
        + " 'enroll@example.test', '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN')");
    String call = "SELECT complete_passkey_enrollment('" + USER + "')::text";
    assertThat(one(APP_LOGIN, call)).as("sin passkey").isEqualTo("false");
    txAs(APP_LOGIN, passkey("00000000-0000-0000-0000-0000000000d1", true));
    assertThat(one(APP_LOGIN, call)).as("solo una suspendida").isEqualTo("false");
    assertThat(enrollRequired()).isEqualTo("true");
    txAs(APP_LOGIN, passkey("00000000-0000-0000-0000-0000000000d2", false));
    assertThat(one(APP_LOGIN, call)).isEqualTo("true");
    assertThat(enrollRequired()).isEqualTo("false");
    assertThat(one(APP_LOGIN, call)).as("ya completado").isEqualTo("false");
  }

  @Test
  void soloLaApiPuedeEjecutarla() throws Exception {
    String call = "SELECT complete_passkey_enrollment('" + USER + "')";
    for (String login : new String[] {"cms_security_tool", IMPORTER_LOGIN, "cms_maintenance", NOBODY_LOGIN}) {
      rejectedAs(login, call);
    }
    // El propietario la hereda (INHERIT), pero la propia función exige una sesión de la API.
    asOwnerFails(NO_PRIVILEGE, call);
    assertThat(one("cms_security_tool", "SELECT proowner::regrole || ':' || prosecdef || ':'"
        + " || array_to_string(proconfig, ',') FROM pg_proc WHERE proname = 'complete_passkey_enrollment'"))
        .isEqualTo("cms_enrollment_writer:true:search_path=pg_catalog, public, pg_temp");
  }
}
