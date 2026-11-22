-- V11: publicar y despublicar un artículo desde el CMS (fase 6.4.5, decisiones D1–D3). Se ejecuta
-- como cms_owner (cms_migrator + SET ROLE). V1–V10 no cambian.
--
-- Hasta aquí, published_revision_id solo lo movía la importación (V4). Ahora la API (cms_app) puede
-- pedir dos operaciones controladas, con su propio rol definidor (cms_article_publisher):
--
--   publish_article(artículo, revisión, versión esperada)   la revisión de TRABAJO pasa a ser la publicada
--   unpublish_article(artículo, versión esperada)          el artículo deja de tener revisión publicada
--
-- Lo que publica la web sigue siendo la instantánea de la fase 6.3: estas operaciones solo marcan
-- qué revisión de cada artículo entra en la siguiente.

-- --- Guarda: el rol dueño de las dos operaciones ----------------------------------------------------
-- V11 no crea roles (cms_migrator no tiene CREATEROLE). Lo crea, de forma idempotente,
-- infra/local/postgres-init/sql/cms-function-owners.psql: automáticamente en un volumen nuevo y a
-- mano en uno ya inicializado. Si falta o no tiene la forma exacta, V11 se detiene aquí y Flyway
-- revierte la migración entera.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles
                  WHERE rolname = 'cms_article_publisher' AND NOT rolcanlogin AND NOT rolsuper
                    AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) THEN
    RAISE EXCEPTION 'V11: falta el rol cms_article_publisher (sin login ni privilegios especiales). Ejecuta infra/local/postgres-init/sql/cms-function-owners.psql como administrador (infra/local/README.md) y vuelve a migrar.';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
                   JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
                   JOIN pg_catalog.pg_roles u ON u.oid = m.member
                  WHERE g.rolname = 'cms_article_publisher' AND u.rolname = 'cms_owner'
                    AND m.inherit_option AND m.set_option) THEN
    RAISE EXCEPTION 'V11: cms_owner debe ser miembro de cms_article_publisher con INHERIT y SET. Ejecuta infra/local/postgres-init/sql/cms-function-owners.psql como administrador (infra/local/README.md) y vuelve a migrar.';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m
               JOIN pg_catalog.pg_roles g ON g.oid = m.roleid
               JOIN pg_catalog.pg_roles u ON u.oid = m.member
              WHERE (g.rolname = 'cms_article_publisher' AND u.rolname <> 'cms_owner')
                 OR u.rolname = 'cms_article_publisher') THEN
    RAISE EXCEPTION 'V11: cms_article_publisher solo puede tener a cms_owner como miembro y no puede pertenecer a ningún rol.';
  END IF;
END
$$;

-- --- Guarda de los punteros: una vía más para published_revision_id ---------------------------------
-- Igual que en V6 (mismo texto para la revisión de trabajo y la importación) más UNA vía nueva: la
-- revisión publicada también la cambia cms_article_publisher (dentro de publish_article o
-- unpublish_article) desde una conexión de la API. Ninguna otra: ni DML directo de cms_app (que no
-- tiene UPDATE en la columna), ni el propietario, ni cadenas de SET ROLE (se mira session_user).
CREATE OR REPLACE FUNCTION guard_article_pointers() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.head_revision_id IS NOT NULL OR NEW.published_revision_id IS NOT NULL THEN
      RAISE EXCEPTION 'un artículo nuevo no tiene revisiones: créalas con las operaciones controladas'
        USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.head_revision_id IS DISTINCT FROM OLD.head_revision_id AND NOT (
      current_user = 'cms_revision_writer'
      AND (pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE')
           OR pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE'))) THEN
    RAISE EXCEPTION 'la revisión de trabajo solo la cambian create_article_revision y restore_article_revision (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.published_revision_id IS DISTINCT FROM OLD.published_revision_id AND NOT (
      (current_user = 'cms_import_writer' AND pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE'))
      OR (current_user = 'cms_article_publisher' AND pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE'))) THEN
    RAISE EXCEPTION 'la revisión publicada solo la cambia una operación autorizada (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;

-- --- publish_article --------------------------------------------------------------------------------
-- En UNA transacción, con el artículo bloqueado (FOR UPDATE):
--   1. La versión esperada es la actual (lock_version); si no: CM412 (versión obsoleta).
--   2. La revisión es de ESE artículo; si no: 22023.
--   3. Es su revisión de TRABAJO actual; si no: CM409 (otro guardado se adelantó).
--   4. Si ya era la publicada, no cambia nada (changed = false).
--   5. Fecha de publicación: la primera vez, el día de hoy en Europe/Madrid (la real, nunca una que
--      envíe el cliente); después se conserva.
--   6. Orden editorial: si no tiene, el siguiente de su sección, con un bloqueo asesor de transacción
--      (clase 0x434D53, objeto 65) compartido con la reordenación de la API.
--   7. Sube lock_version.
CREATE FUNCTION publish_article(p_article_id text, p_revision_id bigint, p_expected_version integer,
  OUT changed boolean, OUT new_published_at date, OUT new_order integer, OUT new_version integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_head bigint;
  v_published bigint;
  v_category text;
  v_order integer;
  v_version integer;
  v_published_at date;
BEGIN
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE') THEN
    RAISE EXCEPTION 'publish_article solo puede usarla una conexión de la API (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF p_article_id IS NULL OR p_revision_id IS NULL OR p_expected_version IS NULL THEN
    RAISE EXCEPTION 'publish_article: artículo, revisión y versión son obligatorios'
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  SELECT a.head_revision_id, a.published_revision_id, a.category_id, a.editorial_order, a.lock_version,
         a.published_at
    INTO v_head, v_published, v_category, v_order, v_version, v_published_at
    FROM public.article AS a WHERE a.id = p_article_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'artículo inexistente: «%»', p_article_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF v_version <> p_expected_version THEN
    RAISE EXCEPTION 'versión obsoleta de «%»: la actual es %, no %', p_article_id, v_version, p_expected_version
      USING ERRCODE = 'CM412';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.article_revision AS r
                  WHERE r.id = p_revision_id AND r.article_id = p_article_id) THEN
    RAISE EXCEPTION 'la revisión % no es del artículo «%»', p_revision_id, p_article_id
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF v_head IS DISTINCT FROM p_revision_id THEN
    RAISE EXCEPTION 'conflicto de revisiones en «%»: la revisión de trabajo es %, no %',
      p_article_id, COALESCE(v_head::text, 'ninguna'), p_revision_id USING ERRCODE = 'CM409';
  END IF;
  IF v_published IS NOT DISTINCT FROM p_revision_id THEN
    changed := false;
    new_published_at := v_published_at;
    new_order := v_order;
    new_version := v_version;
    RETURN;
  END IF;
  IF v_order IS NULL THEN
    PERFORM pg_catalog.pg_advisory_xact_lock(4410707, 65);
    SELECT COALESCE(max(a.editorial_order), 0) + 1 INTO v_order
      FROM public.article AS a WHERE a.category_id = v_category;
  END IF;
  UPDATE public.article AS a
     SET published_revision_id = p_revision_id,
         published_at = COALESCE(a.published_at,
                                 (pg_catalog.statement_timestamp() AT TIME ZONE 'Europe/Madrid')::date),
         editorial_order = v_order,
         lock_version = a.lock_version + 1
   WHERE a.id = p_article_id
   RETURNING a.published_at, a.editorial_order, a.lock_version
        INTO new_published_at, new_order, new_version;
  changed := true;
END
$$;

-- --- unpublish_article --------------------------------------------------------------------------------
-- El artículo deja de tener revisión publicada (sale de la siguiente instantánea). Conserva su fecha
-- de publicación (la real; el slug y la sección siguen congelados, D4) y su orden (su lugar en la
-- sección si se vuelve a publicar). Misma comprobación de versión; si no estaba publicado, nada.
CREATE FUNCTION unpublish_article(p_article_id text, p_expected_version integer,
  OUT changed boolean, OUT new_version integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_published bigint;
  v_version integer;
BEGIN
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE') THEN
    RAISE EXCEPTION 'unpublish_article solo puede usarla una conexión de la API (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF p_article_id IS NULL OR p_expected_version IS NULL THEN
    RAISE EXCEPTION 'unpublish_article: artículo y versión son obligatorios' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  SELECT a.published_revision_id, a.lock_version INTO v_published, v_version
    FROM public.article AS a WHERE a.id = p_article_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'artículo inexistente: «%»', p_article_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF v_version <> p_expected_version THEN
    RAISE EXCEPTION 'versión obsoleta de «%»: la actual es %, no %', p_article_id, v_version, p_expected_version
      USING ERRCODE = 'CM412';
  END IF;
  IF v_published IS NULL THEN
    changed := false;
    new_version := v_version;
    RETURN;
  END IF;
  UPDATE public.article AS a
     SET published_revision_id = NULL, lock_version = a.lock_version + 1
   WHERE a.id = p_article_id
   RETURNING a.lock_version INTO new_version;
  changed := true;
END
$$;

-- --- Dueño y permisos -------------------------------------------------------------------------------
-- cms_article_publisher, con lo justo para esas dos sentencias. Como en V3, V4 y V8, el nuevo dueño
-- necesita CREATE en el esquema durante el ALTER OWNER; se retira a continuación.
GRANT USAGE ON SCHEMA public TO cms_article_publisher;
GRANT SELECT (id, head_revision_id, published_revision_id, category_id, editorial_order, lock_version, published_at)
   ON article TO cms_article_publisher;
GRANT UPDATE (published_revision_id, published_at, editorial_order, lock_version) ON article TO cms_article_publisher;
GRANT SELECT (id, article_id) ON article_revision TO cms_article_publisher;
GRANT CREATE ON SCHEMA public TO cms_article_publisher;
ALTER FUNCTION publish_article(text, bigint, integer) OWNER TO cms_article_publisher;
ALTER FUNCTION unpublish_article(text, integer) OWNER TO cms_article_publisher;
REVOKE CREATE ON SCHEMA public FROM cms_article_publisher;

-- Nadie las ejecuta por defecto; la API, sí.
REVOKE ALL ON FUNCTION publish_article(text, bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION unpublish_article(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION publish_article(text, bigint, integer) TO cms_app;
GRANT EXECUTE ON FUNCTION unpublish_article(text, integer) TO cms_app;
-- Las funciones futuras de este dueño también nacen sin EXECUTE para PUBLIC (como en V8).
ALTER DEFAULT PRIVILEGES FOR ROLE cms_article_publisher REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
