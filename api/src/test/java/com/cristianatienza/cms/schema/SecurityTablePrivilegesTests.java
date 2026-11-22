package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Permisos de las tablas de V7 (usuarios y sesiones), con conexiones reales de cada login: la
 * consulta de privilegios la hace cms_security_tool y los intentos, cada login con su conexión.
 */
class SecurityTablePrivilegesTests extends PostgresSchemaTest {

  private static List<String> privileges(String table) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT r || ':' || string_agg(p, ',' ORDER BY o) FROM"
            + " unnest(ARRAY['cms_api','cms_tool','cms_security_tool','cms_maintenance','cms_migrator']) r,"
            + " unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE']) WITH ORDINALITY AS x(p, o)"
            + " WHERE has_table_privilege(r, ?::regclass, p) GROUP BY r ORDER BY r")) {
      ps.setString(1, "public." + table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** Columnas de app_user que cada login puede actualizar (por privilegio de tabla o de columna). */
  private static List<String> updatableColumns() throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT r || ':' || coalesce(string_agg(a.attname::text, ','"
            + " ORDER BY a.attnum) FILTER (WHERE has_column_privilege(r, a.attrelid, a.attnum, 'UPDATE')), '-')"
            + " FROM unnest(ARRAY['cms_api','cms_tool','cms_maintenance','cms_migrator']) r,"
            + " pg_catalog.pg_attribute a WHERE a.attrelid = 'public.app_user'::regclass AND a.attnum > 0"
            + " AND NOT a.attisdropped GROUP BY r ORDER BY r");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Test
  void usuarios() throws Exception {
    // A nivel de tabla, cms_api solo lee; la herramienta de seguridad crea y modifica, sin borrar.
    assertThat(privileges("app_user")).containsExactly("cms_api:SELECT", "cms_security_tool:SELECT,INSERT,UPDATE");
    // A nivel de columna, cms_api solo actualiza last_login_at (ni enroll_required ni updated_at).
    assertThat(updatableColumns())
        .containsExactly("cms_api:last_login_at", "cms_maintenance:-", "cms_migrator:-", "cms_tool:-");
  }

  /**
   * Columnas de una tabla sobre las que cada login tiene un privilegio efectivo (de tabla o de
   * columna, propio, heredado o de PUBLIC: has_column_privilege lo tiene todo en cuenta).
   */
  private static List<String> columns(String table, String privilege, String... logins) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT r || ':' || coalesce(string_agg(a.attname::text, ','"
            + " ORDER BY a.attnum) FILTER (WHERE has_column_privilege(r, a.attrelid, a.attnum, ?)), '-')"
            + " FROM unnest(?::text[]) r, pg_catalog.pg_attribute a WHERE a.attrelid = ?::regclass"
            + " AND a.attnum > 0 AND NOT a.attisdropped GROUP BY r ORDER BY r")) {
      ps.setString(1, privilege);
      ps.setArray(2, c.createArrayOf("text", logins));
      ps.setString(3, "public." + table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static List<String> rows(String login, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = connectAs(login);
        PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static final String[] ALL_LOGINS =
      {"cms_api", "cms_tool", "cms_security_tool", "cms_maintenance", "cms_migrator"};

  @Test
  void sesiones() throws Exception {
    // A nivel de tabla, la herramienta de seguridad solo puede borrar sesiones.
    assertThat(privileges("spring_session"))
        .containsExactly("cms_api:SELECT,INSERT,UPDATE,DELETE", "cms_security_tool:DELETE");
    assertThat(privileges("spring_session_attributes")).containsExactly("cms_api:SELECT,INSERT,UPDATE,DELETE");
    // A nivel de columna, solo lee principal_name: ni SESSION_ID, ni PRIMARY_ID, ni tiempos.
    assertThat(columns("spring_session", "SELECT", ALL_LOGINS)).containsExactly(
        "cms_api:primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name",
        "cms_maintenance:-", "cms_migrator:-", "cms_security_tool:principal_name", "cms_tool:-");
    assertThat(columns("spring_session_attributes", "SELECT", ALL_LOGINS)).containsExactly(
        "cms_api:session_primary_id,attribute_name,attribute_bytes", "cms_maintenance:-", "cms_migrator:-",
        "cms_security_tool:-", "cms_tool:-");
    // Ni INSERT, ni UPDATE, ni REFERENCES en ninguna columna.
    for (String privilege : List.of("INSERT", "UPDATE", "REFERENCES")) {
      for (String table : List.of("spring_session", "spring_session_attributes")) {
        assertThat(columns(table, privilege, "cms_security_tool")).as(table + " " + privilege)
            .containsExactly("cms_security_tool:-");
      }
    }
  }

  /** Vías indirectas: roles heredables, otras relaciones, funciones privilegiadas y estadísticas. */
  @Test
  void laHerramientaDeSeguridadNoTieneViasIndirectasHaciaLasSesiones() throws Exception {
    // Solo hereda de cms_security_admin y no puede cambiar a otro rol.
    assertThat(rows("cms_security_tool", "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles"
        + " WHERE pg_has_role('cms_security_tool', oid, 'MEMBER')"))
        .containsExactly("cms_security_admin,cms_security_tool");
    for (String role : List.of("cms_owner", "cms_app", "cms_importer")) {
      deniedAs("cms_security_tool", "SET LOCAL ROLE " + role);
    }
    // Todas las relaciones (tablas, vistas, vistas materializadas…) fuera del catálogo que puede
    // leer, aunque sea una sola columna: ninguna vista ni copia de las sesiones.
    assertThat(rows("cms_security_tool", "SELECT n.nspname || '.' || c.relname FROM pg_class c"
        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','v','m','f')"
        + " AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'"
        + " AND has_any_column_privilege('cms_security_tool', c.oid, 'SELECT') ORDER BY 1"))
        // V8 añade las tres tablas en las que solo lee user_id (y used_at, invalidated_at en los
        // códigos), comprobadas columna a columna en WebAuthnPrivilegesTests.
        // V9: la auditoría (SELECT para consultarla) y las columnas de revocación de contadores y
        // dispositivos (comprobadas en V9PrivilegesTests).
        .containsExactly("public.app_user", "public.audit_event", "public.audit_event_ip",
            "public.flyway_schema_history", "public.login_attempt", "public.passkey_credential",
            "public.recovery_code", "public.spring_session", "public.trusted_device", "public.webauthn_user_handle");
    // Ninguna función SECURITY DEFINER que pueda ejecutar (podría leer en nombre de su dueño).
    assertThat(rows("cms_security_tool", "SELECT p.oid::regprocedure::text FROM pg_proc p"
        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE p.prosecdef"
        + " AND n.nspname NOT IN ('pg_catalog', 'information_schema')"
        + " AND has_function_privilege('cms_security_tool', p.oid, 'EXECUTE')")).isEmpty();
  }

  /**
   * Con sesiones reales escritas por cms_api: la herramienta de seguridad las localiza y borra por
   * usuario, pero no puede leer identificadores, atributos ni estadísticas de esas columnas.
   */
  @Test
  void laHerramientaDeSeguridadCierraSesionesSinPoderLeerlas() throws Exception {
    String session = "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,"
        + " max_inactive_interval, expiry_time, principal_name) VALUES ('%s', '%s', 0, 0, 1800, 4102444800000, '%s')";
    String attribute = "INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)"
        + " VALUES ('%s', 'SPRING_SECURITY_CONTEXT', '\\xaced0005')";
    txAs(APP_LOGIN, session.formatted("p1", "s1", "u1"), attribute.formatted("p1"),
        session.formatted("p2", "s2", "u1"), attribute.formatted("p2"),
        session.formatted("p3", "s3", "u2"), attribute.formatted("p3"));
    // Estadísticas actualizadas por el dueño: pg_stats solo muestra columnas con permiso de lectura.
    asOwner("ANALYZE spring_session", "ANALYZE spring_session_attributes");
    assertThat(rows(APP_LOGIN, "SELECT count(*) FROM pg_stats WHERE tablename = 'spring_session'"
        + " AND attname = 'session_id'")).as("las estadísticas existen").containsExactly("1");
    assertThat(rows("cms_security_tool", "SELECT attname FROM pg_stats"
        + " WHERE tablename IN ('spring_session', 'spring_session_attributes') ORDER BY 1"))
        .containsExactly("principal_name");

    // Lecturas rechazadas: identificadores, atributos y cualquier otra columna.
    for (String sql : List.of("SELECT session_id FROM spring_session", "SELECT * FROM spring_session",
        "SELECT primary_id FROM spring_session", "SELECT expiry_time FROM spring_session",
        "SELECT count(*) FROM spring_session WHERE session_id LIKE 's%'",
        "SELECT count(*) FROM spring_session_attributes", "SELECT attribute_bytes FROM spring_session_attributes",
        "DELETE FROM spring_session WHERE principal_name = 'u1' RETURNING session_id",
        "DELETE FROM spring_session WHERE session_id = 's1'",
        "DELETE FROM spring_session_attributes",
        "UPDATE spring_session SET expiry_time = 0 WHERE principal_name = 'u2'",
        "COPY spring_session TO STDOUT")) {
      deniedAs("cms_security_tool", sql);
    }

    // Lo que sí puede: contar y cerrar las sesiones de un usuario.
    assertThat(rows("cms_security_tool", "SELECT count(*) FROM spring_session WHERE principal_name = 'u1'"))
        .containsExactly("2");
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("DELETE FROM spring_session WHERE principal_name = ?")) {
      ps.setString(1, "u1");
      assertThat(ps.executeUpdate()).isEqualTo(2);
    }
    // Comprobado como cms_api: sesiones y atributos de u1 borrados (en cascada); los de u2, intactos.
    assertThat(rows(APP_LOGIN, "SELECT trim(session_id) FROM spring_session ORDER BY 1")).containsExactly("s3");
    assertThat(rows(APP_LOGIN, "SELECT trim(session_primary_id) FROM spring_session_attributes ORDER BY 1"))
        .containsExactly("p3");
  }

  @Test
  void intentosRealesRechazados() throws Exception {
    String insert = "INSERT INTO app_user (id, email, password_hash, role) VALUES (gen_random_uuid(),"
        + " 'x@example.test', '$argon2id$v=19$m=19456,t=2,p=1$x$y', 'ADMIN')";
    deniedAs(APP_LOGIN, insert);
    deniedAs(APP_LOGIN, "UPDATE app_user SET enroll_required = false");
    deniedAs(APP_LOGIN, "UPDATE app_user SET updated_at = now()");
    deniedAs(APP_LOGIN, "UPDATE app_user SET enabled = true");
    deniedAs(APP_LOGIN, "UPDATE app_user SET email = 'y@example.test'");
    deniedAs(APP_LOGIN, "UPDATE app_user SET password_hash = 'x', role = 'ADMIN'");
    // Junto con last_login_at, tampoco: el permiso es solo de esa columna.
    deniedAs(APP_LOGIN, "UPDATE app_user SET last_login_at = now(), enroll_required = false");
    deniedAs(APP_LOGIN, "DELETE FROM app_user");
    // La herramienta de seguridad crea, pero no borra usuarios.
    txAs("cms_security_tool", insert);
    // La API sí registra el último acceso, y solo cambia eso.
    txAs(APP_LOGIN, "UPDATE app_user SET last_login_at = '2026-01-02T03:04:05Z' WHERE email = 'x@example.test'");
    try (Connection c = connectAs("cms_security_tool");
        PreparedStatement ps = c.prepareStatement("SELECT last_login_at = '2026-01-02T03:04:05Z',"
            + " enroll_required, updated_at = created_at FROM app_user WHERE email = 'x@example.test'");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).as("last_login_at registrado").isTrue();
      assertThat(rs.getBoolean(2)).as("enroll_required intacto").isTrue();
      assertThat(rs.getBoolean(3)).as("updated_at intacto").isTrue();
    }
    deniedAs("cms_security_tool", "DELETE FROM app_user");
    deniedAs("cms_security_tool", "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time,"
        + " max_inactive_interval, expiry_time) VALUES ('a', 'b', 0, 0, 0, 0)");
    for (String login : List.of(IMPORTER_LOGIN, "cms_maintenance")) {
      rejectedAs(login, "SELECT count(*) FROM public.app_user");
      rejectedAs(login, "SELECT count(*) FROM public.spring_session");
    }
    rejectedAs("cms_migrator", "SELECT count(*) FROM public.app_user");
  }

  @Test
  void restriccionesDeLaTablaDeUsuarios() throws Exception {
    String base = "INSERT INTO app_user (id, email, password_hash, role) VALUES (gen_random_uuid(), ";
    // Solo Argon2id, roles conocidos y correo único sin distinguir mayúsculas.
    txAsFails("cms_security_tool", CHECK, base + "'a@example.test', '$2a$10$bcrypt', 'ADMIN')");
    txAsFails("cms_security_tool", CHECK, base + "'a@example.test', '$argon2id$v=19$x', 'ROOT')");
    txAsFails("cms_security_tool", CHECK, base + "'sin-arroba', '$argon2id$v=19$x', 'ADMIN')");
    txAs("cms_security_tool", base + "'Mayus@Example.test', '$argon2id$v=19$x', 'EDITOR')");
    txAsFails("cms_security_tool", UNIQUE, base + "'mayus@example.TEST', '$argon2id$v=19$x', 'EDITOR')");
  }
}
