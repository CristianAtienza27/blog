-- V10: publicación del contenido con instantáneas (fase 6.3.4).
-- Se ejecuta como cms_owner (cms_migrator + SET ROLE). Diseño: api/docs/publicacion.md. V1-V9 no
-- cambian. No crea roles ni secretos: los permisos nuevos son para cms_app (la API y sus
-- herramientas de publicación, que usan su mismo login).

-- --- Historial de publicaciones -------------------------------------------------------------------
-- Una fila por versión de instantánea (vN = version). estado/activa.json (lo escribe la web) es la
-- referencia de la versión que se sirve; esta tabla es el historial y se reconcilia con él.
CREATE TABLE content_publication (
  version          integer     PRIMARY KEY CHECK (version BETWEEN 1 AND 999999999),
  state            text        NOT NULL CHECK (state IN
                                 ('generando', 'candidata', 'activa', 'retirada', 'rechazada', 'fallida')),
  content_sha256   d_hex64,
  files            integer     CHECK (files >= 1),
  bytes            bigint      CHECK (bytes >= 1),
  articles         integer     CHECK (articles >= 0),
  issues           jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(issues) = 'array'),
  previous_version integer     REFERENCES content_publication (version),
  requested_by     uuid        REFERENCES app_user (id),
  requested_via    text        NOT NULL CHECK (requested_via IN ('api', 'herramienta', 'reconciliacion')),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  generated_at     timestamptz,
  activated_at     timestamptz,
  updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  files_purged_at  timestamptz,
  -- La huella solo puede faltar mientras se genera o si la generación no llegó a completarse.
  CONSTRAINT content_publication_sha_check
    CHECK (content_sha256 IS NOT NULL OR state IN ('generando', 'fallida')),
  -- Solo se rechaza una candidata (que ya tiene huella).
  CONSTRAINT content_publication_rejected_check
    CHECK (state <> 'rechazada' OR content_sha256 IS NOT NULL),
  CONSTRAINT content_publication_activated_check
    CHECK (state NOT IN ('activa', 'retirada') OR activated_at IS NOT NULL),
  -- Los datos del manifiesto van juntos.
  CONSTRAINT content_publication_manifest_check CHECK (
    (content_sha256 IS NULL) = (generated_at IS NULL) AND (content_sha256 IS NULL) = (files IS NULL)
    AND (content_sha256 IS NULL) = (bytes IS NULL) AND (content_sha256 IS NULL) = (articles IS NULL)),
  CONSTRAINT content_publication_previous_check CHECK (previous_version IS NULL OR previous_version <> version)
);
-- Una sola versión activa.
CREATE UNIQUE INDEX content_publication_single_active ON content_publication ((true)) WHERE state = 'activa';
-- Índices de las claves foráneas (como en todo el esquema: borrar o actualizar la fila referida no
-- recorre la tabla entera).
CREATE INDEX content_publication_previous_idx ON content_publication (previous_version);
CREATE INDEX content_publication_requested_by_idx ON content_publication (requested_by);

-- Matriz de transiciones (api/docs/publicacion.md) y qué columnas puede cambiar cada una. La aplica
-- también al propietario. updated_at lo fija este trigger (no necesita permiso de UPDATE).
CREATE FUNCTION guard_content_publication() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_transition text := OLD.state || '→' || NEW.state;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.state <> 'generando' OR NEW.content_sha256 IS NOT NULL OR NEW.activated_at IS NOT NULL
       OR NEW.previous_version IS NOT NULL OR NEW.files_purged_at IS NOT NULL OR NEW.issues <> '[]' THEN
      RAISE EXCEPTION 'una publicación nueva empieza en «generando», sin huella ni activación'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.version <> OLD.version OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
     OR NEW.requested_via <> OLD.requested_via OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'la versión, quién la pidió, desde dónde y cuándo no cambian'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.state = OLD.state THEN
    -- Sin cambio de estado, solo la purga de archivos: de NULL a una fecha, nunca de la activa.
    IF NEW.state = 'activa' OR OLD.files_purged_at IS NOT NULL OR NEW.files_purged_at IS NULL
       OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256 OR NEW.files IS DISTINCT FROM OLD.files
       OR NEW.bytes IS DISTINCT FROM OLD.bytes OR NEW.articles IS DISTINCT FROM OLD.articles
       OR NEW.generated_at IS DISTINCT FROM OLD.generated_at OR NEW.issues <> OLD.issues
       OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
       OR NEW.previous_version IS DISTINCT FROM OLD.previous_version THEN
      RAISE EXCEPTION 'cambio no permitido sin transición de estado (%)', OLD.state
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF v_transition NOT IN ('generando→candidata', 'generando→fallida', 'candidata→activa',
        'candidata→rechazada', 'candidata→fallida', 'activa→retirada', 'retirada→activa', 'fallida→activa') THEN
    RAISE EXCEPTION 'transición no permitida: %', v_transition USING ERRCODE = 'check_violation';
  ELSIF NEW.state = 'activa' AND (OLD.content_sha256 IS NULL OR OLD.files_purged_at IS NOT NULL) THEN
    -- Nunca se activa una versión sin huella ni una cuyos archivos se purgaron.
    RAISE EXCEPTION 'una versión sin huella o sin archivos no se puede activar'
      USING ERRCODE = 'check_violation';
  ELSE
    -- Datos del manifiesto: solo al pasar de generando a candidata.
    IF v_transition <> 'generando→candidata' AND (NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256
       OR NEW.files IS DISTINCT FROM OLD.files OR NEW.bytes IS DISTINCT FROM OLD.bytes
       OR NEW.articles IS DISTINCT FROM OLD.articles OR NEW.generated_at IS DISTINCT FROM OLD.generated_at) THEN
      RAISE EXCEPTION 'la huella y los datos del manifiesto solo se fijan al completar la generación'
        USING ERRCODE = 'check_violation';
    END IF;
    -- Activación: activated_at y previous_version, solo al pasar a activa.
    IF NEW.state <> 'activa' AND (NEW.activated_at IS DISTINCT FROM OLD.activated_at
       OR NEW.previous_version IS DISTINCT FROM OLD.previous_version) THEN
      RAISE EXCEPTION 'la activación solo se registra al pasar a «activa»' USING ERRCODE = 'check_violation';
    END IF;
    -- Incidencias: solo al pasar a rechazada o fallida.
    IF NEW.state NOT IN ('rechazada', 'fallida') AND NEW.issues <> OLD.issues THEN
      RAISE EXCEPTION 'las incidencias solo se registran al rechazar o fallar' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.files_purged_at IS DISTINCT FROM OLD.files_purged_at THEN
      RAISE EXCEPTION 'la purga no cambia el estado' USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$$;
CREATE TRIGGER content_publication_guard BEFORE INSERT OR UPDATE ON content_publication
  FOR EACH ROW EXECUTE FUNCTION guard_content_publication();

-- --- Época (fencing) ------------------------------------------------------------------------------
-- Una sola fila. Quien toma el control de la publicación incrementa la época; toda transacción que
-- cambia el estado bloquea esta fila (FOR UPDATE) y comprueba que la época sigue siendo la suya.
CREATE TABLE content_publication_lease (
  id          integer     PRIMARY KEY CHECK (id = 1),
  epoch       bigint      NOT NULL CHECK (epoch >= 0),
  holder      text        CHECK (holder IS NULL OR length(holder) <= 200),
  acquired_at timestamptz
);
INSERT INTO content_publication_lease (id, epoch) VALUES (1, 0);

-- La época solo crece.
CREATE FUNCTION guard_content_publication_lease() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NEW.epoch <= OLD.epoch OR NEW.id <> OLD.id THEN
    RAISE EXCEPTION 'la época de publicación solo crece (% → %)', OLD.epoch, NEW.epoch
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER content_publication_lease_guard BEFORE UPDATE ON content_publication_lease
  FOR EACH ROW EXECUTE FUNCTION guard_content_publication_lease();

-- --- Resultado de la última reconciliación --------------------------------------------------------
-- Una sola fila (sembrada aquí): CONCILIADO, VACIO o INCONSISTENTE, con el motivo. Permite auditar
-- una inconsistencia solo cuando cambia (reconciliar varias veces no duplica eventos).
CREATE TABLE content_publication_check (
  id              integer     PRIMARY KEY CHECK (id = 1),
  status          text        NOT NULL CHECK (status IN ('SIN_COMPROBAR', 'CONCILIADO', 'VACIO', 'INCONSISTENTE')),
  detail          jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(detail) = 'object'),
  pointer_version integer,
  checked_at      timestamptz
);
INSERT INTO content_publication_check (id, status) VALUES (1, 'SIN_COMPROBAR');

-- --- Permisos -------------------------------------------------------------------------------------
REVOKE ALL ON FUNCTION guard_content_publication(), guard_content_publication_lease() FROM PUBLIC;

-- Leer el estado publicado como cms_app (sin SET ROLE): la función no es SECURITY DEFINER y solo
-- lee tablas que cms_app ya puede leer (V3).
GRANT EXECUTE ON FUNCTION article_revision_content(bigint) TO cms_app;

-- P1: registrar la versión (los demás campos toman su valor por defecto).
GRANT SELECT ON content_publication TO cms_app;
GRANT INSERT (version, state, requested_by, requested_via) ON content_publication TO cms_app;
-- P2 (huella y manifiesto), P4 y volver atrás (activación), rechazos y fallos (incidencias) y la
-- purga (files_purged_at). Nunca version, requested_by, requested_via ni created_at; sin DELETE.
GRANT UPDATE (state, content_sha256, files, bytes, articles, generated_at, issues, activated_at,
  previous_version, files_purged_at) ON content_publication TO cms_app;
-- Época: tomar el control (UPDATE) y el bloqueo de la fila en cada transacción (FOR UPDATE).
GRANT SELECT, UPDATE (epoch, holder, acquired_at) ON content_publication_lease TO cms_app;
-- Resultado de la reconciliación.
GRANT SELECT, UPDATE (status, detail, pointer_version, checked_at) ON content_publication_check TO cms_app;
