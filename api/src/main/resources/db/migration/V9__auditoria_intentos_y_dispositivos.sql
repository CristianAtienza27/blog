-- V9: auditoría persistente, limitación de intentos y dispositivos de confianza (fase 6.2.4).
-- Se ejecuta como cms_owner (cms_migrator + SET ROLE). Diseño: api/docs/seguridad.md. V1-V8 no
-- cambian. No crea roles: cms_audit_retention y cms_audit_maintainer existen desde la 6.2.1.

-- --- Auditoría ----------------------------------------------------------------------------------
-- Nunca contiene secretos: ni contraseñas, ni hashes, ni identificadores de sesión, ni tokens, ni
-- challenges, ni códigos, ni material de credenciales, ni el correo tecleado (solo su HMAC en
-- details, si el usuario no existe). La IP va aparte, con una retención más corta.
-- El identificador lo genera quien escribe (UUID): así la API solo inserta, sin RETURNING (que
-- exigiría SELECT).
CREATE TABLE audit_event (
  id           uuid        PRIMARY KEY,
  occurred_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  event        text        NOT NULL CHECK (event ~ '^[a-z0-9_]+(\.[a-z0-9_]+)+$' AND length(event) <= 80),
  outcome      text        NOT NULL CHECK (outcome IN ('success', 'failure', 'info')),
  user_id      uuid,
  session_hash bytea       CHECK (session_hash IS NULL OR length(session_hash) = 32),
  user_agent   text        CHECK (user_agent IS NULL OR length(user_agent) <= 200),
  details      jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX audit_event_occurred_idx ON audit_event (occurred_at);
CREATE INDEX audit_event_user_idx ON audit_event (user_id, occurred_at);

-- IP completa (dato personal): 90 días como mínimo, antes que el evento.
CREATE TABLE audit_event_ip (
  event_id uuid   PRIMARY KEY REFERENCES audit_event (id),
  ip       inet   NOT NULL
);

-- Solo inserción, también para el propietario; únicamente la purga por retención (purge_audit,
-- dueño cms_audit_retention, ejecutada desde una sesión de cms_audit_maintainer) puede borrar.
-- Límite documentado: cms_owner (vía cms_migrator) y el superusuario pueden desactivar triggers.
CREATE FUNCTION guard_audit_immutable() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF TG_OP = 'DELETE' AND current_user = 'cms_audit_retention'
     AND pg_catalog.pg_has_role(session_user, 'cms_audit_maintainer', 'USAGE') THEN
    RETURN OLD;
  END IF;
  RAISE EXCEPTION 'la auditoría es de solo inserción: % no permitido en % (sesión: %, rol: %)',
    TG_OP, TG_TABLE_NAME, session_user, current_user USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER audit_event_immutable BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION guard_audit_immutable();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
  FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_immutable();
CREATE TRIGGER audit_event_ip_immutable BEFORE UPDATE OR DELETE ON audit_event_ip
  FOR EACH ROW EXECUTE FUNCTION guard_audit_immutable();
CREATE TRIGGER audit_event_ip_no_truncate BEFORE TRUNCATE ON audit_event_ip
  FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_immutable();

-- --- Limitación de intentos ---------------------------------------------------------------------
-- Una fila por ámbito y clave (HMAC: de la IP, del correo tecleado o de sus combinaciones; nunca en
-- claro). Los incrementos son un único INSERT ... ON CONFLICT DO UPDATE ... RETURNING, atómico.
--   ip             fallos de cualquier cuenta desde una IP
--   account_ip     cuenta + IP, sin dispositivo de confianza
--   account_device cuenta + dispositivo de confianza (familia)
--   device_day     fallos del dispositivo en 24 h (20: se revoca su confianza)
--   account        fallos de la cuenta desde cualquier IP (modo de ataque; nunca bloquea la cuenta)
CREATE TABLE login_attempt (
  key_type          text        NOT NULL CHECK (key_type IN ('ip', 'account_ip', 'account_device', 'device_day', 'account')),
  key_hash          bytea       NOT NULL CHECK (length(key_hash) = 32),
  window_start      timestamptz NOT NULL,
  failures          integer     NOT NULL CHECK (failures >= 0),
  last_failure_at   timestamptz,
  block_level       integer     NOT NULL DEFAULT 0 CHECK (block_level BETWEEN 0 AND 10),
  blocked_until     timestamptz,
  attack_mode_until timestamptz,
  expires_at        timestamptz NOT NULL,
  PRIMARY KEY (key_type, key_hash)
);
CREATE INDEX login_attempt_expires_idx ON login_attempt (expires_at);

-- --- Dispositivos de confianza -----------------------------------------------------------------
-- __Host-DEVICE: confianza del navegador, NUNCA un factor de autenticación. Solo su HMAC.
CREATE TABLE trusted_device (
  id             uuid        PRIMARY KEY,
  user_id        uuid        NOT NULL REFERENCES app_user (id),
  family_id      uuid        NOT NULL,
  token_hmac     bytea       NOT NULL UNIQUE CHECK (length(token_hmac) = 32),
  state          text        NOT NULL CHECK (state IN ('ACTIVE', 'PENDING', 'RETIRED', 'SUPERSEDED', 'REVOKED')),
  predecessor_id uuid,
  superseded_by  uuid,
  created_at     timestamptz NOT NULL,
  confirmed_at   timestamptz,
  retired_at     timestamptz,
  last_used_at   timestamptz,
  expires_at     timestamptz NOT NULL,
  revoked_at     timestamptz,
  revoked_reason text        CHECK (revoked_reason IS NULL OR length(revoked_reason) <= 40),
  CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL)),
  CHECK (state NOT IN ('RETIRED', 'SUPERSEDED') OR retired_at IS NOT NULL)
);
CREATE INDEX trusted_device_family_idx ON trusted_device (family_id);
CREATE INDEX trusted_device_user_idx ON trusted_device (user_id);
-- Como mucho un ACTIVE y un PENDING por familia.
CREATE UNIQUE INDEX trusted_device_one_active ON trusted_device (family_id) WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX trusted_device_one_pending ON trusted_device (family_id) WHERE state = 'PENDING';

-- Lo que no puede ocurrir: un token revocado no vuelve, y la identidad de un token no cambia.
CREATE FUNCTION guard_trusted_device_update() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NEW.id <> OLD.id OR NEW.user_id <> OLD.user_id OR NEW.family_id <> OLD.family_id
     OR NEW.token_hmac <> OLD.token_hmac OR NEW.created_at <> OLD.created_at
     OR NEW.predecessor_id IS DISTINCT FROM OLD.predecessor_id THEN
    RAISE EXCEPTION 'la identidad de un dispositivo no cambia' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF OLD.state = 'REVOKED' AND NEW.state <> 'REVOKED' THEN
    RAISE EXCEPTION 'un dispositivo revocado no se reactiva' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trusted_device_guard BEFORE UPDATE ON trusted_device
  FOR EACH ROW EXECUTE FUNCTION guard_trusted_device_update();

-- Presentaciones recientes (24 h): la evidencia para revocar una familia de forma proporcionada
-- (E6). Huellas HMAC del origen (IP y agente de usuario), nunca en claro.
CREATE TABLE trusted_device_presentation (
  id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id        uuid        NOT NULL,
  family_id        uuid        NOT NULL,
  kind             text        NOT NULL CHECK (kind IN ('active', 'stale')),
  ip_hash          bytea       NOT NULL CHECK (length(ip_hash) = 32),
  agent_hash       bytea       NOT NULL CHECK (length(agent_hash) = 32),
  presented_at     timestamptz NOT NULL,
  followed_by_full boolean     NOT NULL DEFAULT false
);
CREATE INDEX trusted_device_presentation_family_idx ON trusted_device_presentation (family_id, presented_at);

-- --- Retención de la auditoría ------------------------------------------------------------------
-- Mínimos fijos: eventos y aserciones WebAuthn de menos de 365 días e IP de menos de 90 no se
-- borran nunca; rechaza cortes más recientes. Deja constancia en la misma transacción. Solo la
-- ejecuta cms_maintenance (cms_audit_maintainer); la tarea programada es de la 6.9.
CREATE FUNCTION purge_audit(p_events_before timestamptz, p_ips_before timestamptz) RETURNS jsonb
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_ips bigint;
  v_events bigint;
  v_assertions bigint;
  v_result jsonb;
BEGIN
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_audit_maintainer', 'USAGE') THEN
    RAISE EXCEPTION 'purge_audit solo se ejecuta desde una sesión de mantenimiento (sesión: %)', session_user
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF p_events_before IS NULL OR p_ips_before IS NULL
     OR p_events_before > now() - interval '365 days' OR p_ips_before > now() - interval '90 days' THEN
    RAISE EXCEPTION 'purge_audit: los cortes deben ser de hace al menos 365 días (eventos) y 90 (IP)'
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  DELETE FROM public.audit_event_ip i USING public.audit_event e
   WHERE e.id = i.event_id AND (e.occurred_at < p_ips_before OR e.occurred_at < p_events_before);
  GET DIAGNOSTICS v_ips = ROW_COUNT;
  DELETE FROM public.audit_event e WHERE e.occurred_at < p_events_before;
  GET DIAGNOSTICS v_events = ROW_COUNT;
  DELETE FROM public.webauthn_assertion a WHERE a.verified_at < p_events_before;
  GET DIAGNOSTICS v_assertions = ROW_COUNT;
  v_result := jsonb_build_object('eventsBefore', p_events_before, 'ipsBefore', p_ips_before,
    'events', v_events, 'ips', v_ips, 'assertions', v_assertions);
  INSERT INTO public.audit_event (id, event, outcome, details)
    VALUES (gen_random_uuid(), 'audit.retention_purge', 'info', v_result);
  RETURN v_result;
END
$$;

-- Dueño cms_audit_retention, con lo justo. Como en V3, V4 y V8, el nuevo dueño necesita CREATE en el
-- esquema durante el ALTER OWNER; se retira a continuación.
GRANT USAGE ON SCHEMA public TO cms_audit_retention;
GRANT SELECT (id, occurred_at), DELETE, INSERT (id, event, outcome, details) ON audit_event TO cms_audit_retention;
GRANT SELECT (event_id), DELETE ON audit_event_ip TO cms_audit_retention;
GRANT SELECT (verified_at), DELETE ON webauthn_assertion TO cms_audit_retention;
GRANT CREATE ON SCHEMA public TO cms_audit_retention;
ALTER FUNCTION purge_audit(timestamptz, timestamptz) OWNER TO cms_audit_retention;
REVOKE CREATE ON SCHEMA public FROM cms_audit_retention;

-- Sin EXECUTE para PUBLIC en ninguna función nueva (los privilegios por defecto globales de V8 ya
-- lo evitan; se revoca también de forma explícita).
REVOKE ALL ON FUNCTION guard_audit_immutable(), guard_trusted_device_update(),
  purge_audit(timestamptz, timestamptz) FROM PUBLIC;
-- cms_audit_maintainer necesita USAGE en el esquema para resolver el nombre de la función (sin él,
-- «no existe»); no le da acceso a ninguna tabla.
GRANT USAGE ON SCHEMA public TO cms_audit_maintainer;
GRANT EXECUTE ON FUNCTION purge_audit(timestamptz, timestamptz) TO cms_audit_maintainer;

-- --- Permisos -----------------------------------------------------------------------------------
-- API (cms_app): escribe auditoría (solo INSERT), lleva los contadores y los dispositivos.
GRANT INSERT ON audit_event, audit_event_ip TO cms_app;
GRANT SELECT, INSERT, DELETE ON login_attempt TO cms_app;
GRANT UPDATE (window_start, failures, last_failure_at, block_level, blocked_until, attack_mode_until, expires_at)
   ON login_attempt TO cms_app;
GRANT SELECT, INSERT ON trusted_device TO cms_app;
GRANT UPDATE (state, superseded_by, confirmed_at, retired_at, last_used_at, revoked_at, revoked_reason)
   ON trusted_device TO cms_app;
GRANT SELECT, INSERT, DELETE ON trusted_device_presentation TO cms_app;
GRANT UPDATE (followed_by_full) ON trusted_device_presentation TO cms_app;

-- Herramienta de seguridad (cms_security_admin): consultar la auditoría y registrar sus propias
-- operaciones (6.2.6); reiniciar contadores y revocar dispositivos por usuario. No lee HMAC.
GRANT SELECT, INSERT ON audit_event, audit_event_ip TO cms_security_admin;
GRANT SELECT (key_type, key_hash), DELETE ON login_attempt TO cms_security_admin;
GRANT SELECT (user_id, state), UPDATE (state, revoked_at, revoked_reason) ON trusted_device TO cms_security_admin;
