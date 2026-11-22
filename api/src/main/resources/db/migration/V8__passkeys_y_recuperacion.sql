-- V8: passkeys y códigos de recuperación (fase 6.2.3). Se ejecuta como cms_owner (cms_migrator +
-- SET ROLE). Diseño: api/docs/seguridad.md. V1-V7 no cambian.

-- --- Guarda: el rol dueño de complete_passkey_enrollment ------------------------------------------
-- V8 no crea roles (cms_migrator no tiene CREATEROLE). El rol lo crea, de forma idempotente,
-- infra/local/postgres-init/sql/cms-function-owners.psql: automáticamente en un volumen nuevo y a
-- mano en uno ya inicializado. Si falta o no tiene la forma exacta, V8 se detiene aquí y Flyway
-- revierte la migración entera: el volumen queda como estaba.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles
                  WHERE rolname = 'cms_enrollment_writer' AND NOT rolcanlogin AND NOT rolsuper
                    AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) THEN
    RAISE EXCEPTION 'V8: falta el rol cms_enrollment_writer (sin login ni privilegios especiales). Ejecuta infra/local/postgres-init/sql/cms-function-owners.psql como administrador (infra/local/README.md) y vuelve a migrar.';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                   JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
                   JOIN pg_catalog.pg_roles u ON u.oid = m.member
                  WHERE g.rolname = 'cms_enrollment_writer' AND u.rolname = 'cms_owner'
                    AND m.inherit_option AND m.set_option) THEN
    RAISE EXCEPTION 'V8: cms_owner debe ser miembro de cms_enrollment_writer con INHERIT y SET. Ejecuta infra/local/postgres-init/sql/cms-function-owners.psql como administrador (infra/local/README.md) y vuelve a migrar.';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
               JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
               JOIN pg_catalog.pg_roles u ON u.oid = m.member
              WHERE (g.rolname = 'cms_enrollment_writer' AND u.rolname <> 'cms_owner')
                 OR u.rolname = 'cms_enrollment_writer') THEN
    RAISE EXCEPTION 'V8: cms_enrollment_writer solo puede tener a cms_owner como miembro y no puede pertenecer a ningún rol.';
  END IF;
END
$$;

-- --- Identificador WebAuthn del usuario (user.id de las opciones) --------------------------------
-- Aleatorio, nunca el correo. La recuperación de emergencia (6.2.6) lo borra para que sea otro.
CREATE TABLE webauthn_user_handle (
  user_id    uuid        PRIMARY KEY REFERENCES app_user (id),
  handle     bytea       NOT NULL UNIQUE CHECK (length(handle) = 32),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- --- Passkeys -----------------------------------------------------------------------------------
-- Esquema propio (D2): clave pública COSE, contador, flags y estado de riesgo. La política del
-- contador (api/docs/seguridad.md) la aplica la API; aquí se garantiza lo que nunca puede pasar.
CREATE TABLE passkey_credential (
  id              uuid        PRIMARY KEY,
  user_id         uuid        NOT NULL REFERENCES app_user (id),
  credential_id   bytea       NOT NULL UNIQUE CHECK (length(credential_id) BETWEEN 16 AND 1023),
  public_key_cose bytea       NOT NULL CHECK (length(public_key_cose) BETWEEN 16 AND 2048),
  aaguid          uuid        NOT NULL,
  sign_count      bigint      NOT NULL CHECK (sign_count BETWEEN 0 AND 4294967295),
  uv_initialized  boolean     NOT NULL,
  backup_eligible boolean     NOT NULL,
  backup_state    boolean     NOT NULL,
  transports      text[]      NOT NULL DEFAULT '{}',
  label           text        CHECK (label IS NULL OR length(label) BETWEEN 1 AND 64),
  created_at      timestamptz NOT NULL DEFAULT now(),
  last_used_at    timestamptz,
  anomaly_count   integer     NOT NULL DEFAULT 0 CHECK (anomaly_count >= 0),
  last_anomaly_at timestamptz,
  suspended_at    timestamptz,
  CHECK (backup_eligible OR NOT backup_state)
);
CREATE INDEX passkey_credential_user_idx ON passkey_credential (user_id);

-- Lo que no puede ocurrir nunca, ni por un fallo de la API: el contador no baja, una suspensión
-- no se retira (nunca se reactiva sola), las anomalías no se borran y la identidad de la
-- credencial no cambia.
CREATE FUNCTION guard_passkey_credential_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.credential_id <> OLD.credential_id
     OR NEW.public_key_cose <> OLD.public_key_cose OR NEW.aaguid <> OLD.aaguid
     OR NEW.uv_initialized <> OLD.uv_initialized OR NEW.backup_eligible <> OLD.backup_eligible
     OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'la identidad de una passkey no cambia' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.sign_count < OLD.sign_count THEN
    RAISE EXCEPTION 'el contador de una passkey no puede bajar (% -> %)', OLD.sign_count, NEW.sign_count
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.suspended_at IS NOT NULL AND NEW.suspended_at IS DISTINCT FROM OLD.suspended_at THEN
    RAISE EXCEPTION 'una passkey suspendida no se reactiva' USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.anomaly_count < OLD.anomaly_count THEN
    RAISE EXCEPTION 'las anomalías de una passkey no se borran' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER passkey_credential_guard BEFORE UPDATE ON passkey_credential
  FOR EACH ROW EXECUTE FUNCTION guard_passkey_credential_update();

-- --- Challenges de un solo uso -------------------------------------------------------------------
-- Se consumen con un único DELETE ... RETURNING confirmado en su propia transacción (api/docs/
-- seguridad.md). Como mucho uno vivo por sesión y ceremonia. session_hash es el HMAC del
-- identificador de sesión: el identificador (equivalente a la cookie) no se copia aquí.
CREATE TABLE webauthn_challenge (
  id                uuid        PRIMARY KEY,
  session_hash      bytea       NOT NULL CHECK (length(session_hash) = 32),
  user_id           uuid        NOT NULL REFERENCES app_user (id),
  ceremony          text        NOT NULL CHECK (ceremony IN ('register', 'authenticate', 'step_up')),
  challenge         bytea       NOT NULL CHECK (length(challenge) = 32),
  options           jsonb       NOT NULL,
  -- Contador de cada credencial permitida al emitirlo (procedencia de la política del contador).
  counters_at_issue jsonb       NOT NULL DEFAULT '{}',
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at        timestamptz NOT NULL,
  CHECK (expires_at > created_at),
  UNIQUE (session_hash, ceremony)
);
CREATE INDEX webauthn_challenge_expires_idx ON webauthn_challenge (expires_at);
CREATE INDEX webauthn_challenge_user_idx ON webauthn_challenge (user_id);

-- --- Procedencia de cada verificación del contador ---------------------------------------------
-- Una fila por aserción verificada (aceptada o rechazada), en la misma transacción que la
-- actualización del contador. Sobrevive al borrado del challenge y de la passkey (sin claves
-- foráneas). Solo inserción: ni el propietario puede modificarla ni borrarla; solo la purga por
-- retención (V9, fase 6.2.4), como cms_audit_retention desde una sesión de cms_audit_maintainer.
CREATE TABLE webauthn_assertion (
  id                  bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  credential_id       uuid        NOT NULL,
  user_id             uuid        NOT NULL,
  ceremony            text        NOT NULL CHECK (ceremony IN ('authenticate', 'step_up')),
  challenge_id        uuid        NOT NULL,
  challenge_issued_at timestamptz NOT NULL,
  counter_at_issue    bigint      NOT NULL CHECK (counter_at_issue >= 0),
  verified_at         timestamptz NOT NULL,
  received_counter    bigint      NOT NULL CHECK (received_counter >= 0),
  stored_before       bigint      NOT NULL CHECK (stored_before >= 0),
  stored_after        bigint      NOT NULL CHECK (stored_after >= stored_before),
  outcome             text        NOT NULL CHECK (outcome IN ('accepted', 'rejected')),
  classification      text        NOT NULL CHECK (classification IN
                        ('no_counter', 'increasing', 'overlapped', 'delayed', 'weak_anomaly', 'strong_anomaly')),
  CHECK (challenge_issued_at <= verified_at),
  CHECK ((outcome = 'accepted') = (classification IN ('no_counter', 'increasing')))
);
CREATE INDEX webauthn_assertion_credential_idx ON webauthn_assertion (credential_id, verified_at);

CREATE FUNCTION guard_webauthn_assertion_immutable() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_user = 'cms_audit_retention'
     AND pg_catalog.pg_has_role(session_user, 'cms_audit_maintainer', 'USAGE') THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'webauthn_assertion es de solo inserción: % no permitido (sesión: %, rol: %)',
    TG_OP, session_user, current_user USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER webauthn_assertion_immutable BEFORE UPDATE OR DELETE ON webauthn_assertion
  FOR EACH ROW EXECUTE FUNCTION guard_webauthn_assertion_immutable();
CREATE TRIGGER webauthn_assertion_no_truncate BEFORE TRUNCATE ON webauthn_assertion
  FOR EACH STATEMENT EXECUTE FUNCTION guard_webauthn_assertion_immutable();

-- --- Códigos de recuperación --------------------------------------------------------------------
-- Solo su HMAC-SHA-256 (clave secreta de la API, fuera de la base de datos). Uso único: used_at e
-- invalidated_at solo pasan de vacío a una fecha, una vez.
CREATE TABLE recovery_code (
  id             uuid        PRIMARY KEY,
  user_id        uuid        NOT NULL REFERENCES app_user (id),
  batch_id       uuid        NOT NULL,
  code_hmac      bytea       NOT NULL UNIQUE CHECK (length(code_hmac) = 32),
  created_at     timestamptz NOT NULL DEFAULT now(),
  used_at        timestamptz,
  invalidated_at timestamptz
);
CREATE INDEX recovery_code_active_idx ON recovery_code (user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;

CREATE FUNCTION guard_recovery_code_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.batch_id <> OLD.batch_id
     OR NEW.code_hmac <> OLD.code_hmac OR NEW.created_at <> OLD.created_at
     OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at)
     OR (OLD.invalidated_at IS NOT NULL AND NEW.invalidated_at IS DISTINCT FROM OLD.invalidated_at) THEN
    RAISE EXCEPTION 'un código de recuperación solo puede marcarse como usado o invalidado, una vez'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER recovery_code_guard BEFORE UPDATE ON recovery_code
  FOR EACH ROW EXECUTE FUNCTION guard_recovery_code_update();

-- --- Fallos por sesión (D7) -----------------------------------------------------------------------
-- Fuera de Spring Session: sus atributos se guardan con «gana el último» y, con peticiones
-- simultáneas de la misma sesión, un contador ahí pierde incrementos (y su INSERT choca). Aquí cada
-- fallo es un único INSERT ... ON CONFLICT DO UPDATE ... RETURNING, atómico. session_hash es el
-- HMAC del identificador de sesión (como en webauthn_challenge): ningún identificador en claro.
CREATE TABLE auth_session_failure (
  session_hash bytea       NOT NULL CHECK (length(session_hash) = 32),
  kind         text        NOT NULL CHECK (kind IN ('passkey', 'recovery', 'step_up')),
  failures     integer     NOT NULL CHECK (failures >= 1),
  expires_at   timestamptz NOT NULL,
  PRIMARY KEY (session_hash, kind)
);
CREATE INDEX auth_session_failure_expires_idx ON auth_session_failure (expires_at);

-- --- Fin controlado del registro (D5) -----------------------------------------------------------
-- La API no tiene UPDATE sobre enroll_required: solo puede pedir esta operación, que lo pone a
-- false si el usuario tiene una passkey utilizable, y solo desde una sesión de la API (mismo
-- patrón que V6). Volver a true es cosa de la herramienta de seguridad (emergencia, 6.2.6).
CREATE FUNCTION complete_passkey_enrollment(p_user uuid) RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE') THEN
    RAISE EXCEPTION 'complete_passkey_enrollment solo se ejecuta desde la API (sesión: %)', session_user
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  UPDATE public.app_user u SET enroll_required = false, updated_at = now()
   WHERE u.id = p_user AND u.enroll_required
     AND EXISTS (SELECT 1 FROM public.passkey_credential c WHERE c.user_id = u.id AND c.suspended_at IS NULL);
  RETURN FOUND;
END
$$;

-- Su dueño es cms_enrollment_writer, con lo justo para esa sentencia. Como en V3 y V4, el nuevo
-- dueño necesita CREATE en el esquema durante el ALTER OWNER; se retira a continuación.
GRANT USAGE ON SCHEMA public TO cms_enrollment_writer;
GRANT SELECT (id, enroll_required), UPDATE (enroll_required, updated_at) ON app_user TO cms_enrollment_writer;
GRANT SELECT (user_id, suspended_at) ON passkey_credential TO cms_enrollment_writer;
GRANT CREATE ON SCHEMA public TO cms_enrollment_writer;
ALTER FUNCTION complete_passkey_enrollment(uuid) OWNER TO cms_enrollment_writer;
REVOKE CREATE ON SCHEMA public FROM cms_enrollment_writer;
REVOKE ALL ON FUNCTION complete_passkey_enrollment(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION complete_passkey_enrollment(uuid) TO cms_app;

-- --- EXECUTE de PUBLIC en las funciones (corrige el alcance de V6) --------------------------------
-- V6 usó ALTER DEFAULT PRIVILEGES ... IN SCHEMA public REVOKE EXECUTE ... FROM PUBLIC. En
-- PostgreSQL los privilegios por defecto POR ESQUEMA solo se suman a los globales: no quitan el
-- EXECUTE que PUBLIC recibe por defecto en cada función nueva (detectado en la 6.2.3:
-- SessionUserAuthorizationTests.publicNoTieneExecuteEnNingunaFuncion). V6 está congelada; aquí se
-- revoca en las funciones nuevas y se fija el privilegio por defecto GLOBAL (sin IN SCHEMA) para
-- el propietario y los dueños de funciones, de modo que las funciones futuras nazcan sin él.
REVOKE ALL ON FUNCTION guard_passkey_credential_update(), guard_webauthn_assertion_immutable(),
  guard_recovery_code_update() FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_revision_writer REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_import_writer REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_audit_retention REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_enrollment_writer REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- --- Permisos -----------------------------------------------------------------------------------
-- API (cms_app): las ceremonias, sus passkeys y sus códigos. Por columnas donde basta.
GRANT SELECT, INSERT ON webauthn_user_handle TO cms_app;
GRANT SELECT, INSERT, DELETE ON passkey_credential TO cms_app;
GRANT UPDATE (sign_count, last_used_at, backup_state, anomaly_count, last_anomaly_at, suspended_at)
   ON passkey_credential TO cms_app;
GRANT SELECT, INSERT, DELETE ON webauthn_challenge TO cms_app;
-- SELECT (D4): clasificar una anomalía exige leer las aserciones aceptadas anteriores.
GRANT SELECT, INSERT ON webauthn_assertion TO cms_app;
GRANT SELECT, INSERT ON recovery_code TO cms_app;
GRANT UPDATE (used_at, invalidated_at) ON recovery_code TO cms_app;
GRANT SELECT, INSERT, DELETE ON auth_session_failure TO cms_app;
GRANT UPDATE (failures) ON auth_session_failure TO cms_app;

-- Herramienta de seguridad (cms_security_admin): solo lo que necesitará la recuperación de
-- emergencia (6.2.6) para revocar por usuario. No lee claves públicas, HMAC ni challenges (estos
-- caducan en minutos y quedan ligados a sesiones que la herramienta ya puede cerrar).
GRANT SELECT (user_id), DELETE ON webauthn_user_handle TO cms_security_admin;
GRANT SELECT (user_id), DELETE ON passkey_credential TO cms_security_admin;
GRANT SELECT (user_id, used_at, invalidated_at), UPDATE (invalidated_at) ON recovery_code TO cms_security_admin;
