-- V6: endurecimiento de la separación de roles (fase 6.2.1).
--
-- Problema: V3 y V4 transfieren sus funciones a cms_revision_writer y cms_import_writer y después
-- gestionan sus permisos, así que cms_owner debe heredar de esos roles (INHERIT). En consecuencia,
-- una sesión de cms_migrator con SET ROLE cms_owner (o encadenando SET ROLE a esos roles) podía
-- usar las operaciones de negocio. Las reglas de V3/V4 comprueban current_user, que SET ROLE
-- cambia.
--
-- Solución: comprobar también session_user, el login real de la conexión, que SET ROLE no cambia.
--   Operación / regla                         current_user exigido   session_user (login) exigido
--   import_published_article                  (definer)              miembro de cms_importer
--   create_article_revision                   (definer)              miembro de cms_app
--   restore_article_revision                  (definer)              miembro de cms_app
--   insert_article_revision (núcleo)          (definer)              cms_app o cms_importer
--   INSERT en las tablas de revisión          cms_revision_writer    cms_app o cms_importer
--   cambio de head_revision_id                cms_revision_writer    cms_app o cms_importer
--   cambio de published_revision_id           cms_import_writer      cms_importer
-- Los triggers aplican la regla en TODOS los caminos (funciones, DML directo o SET ROLE).
--
-- Límite de confianza: cms_owner (y cms_migrator con SET ROLE) conserva el poder de DDL y podría
-- modificar estas funciones o desactivar los triggers. Esto impide usar las operaciones de
-- negocio por los caminos normales; no protege frente al compromiso deliberado de una credencial
-- con DDL.
--
-- Solo endurecimiento: no cambia el esquema de datos ni el resultado de ninguna operación legítima.
-- Las cuatro operaciones se redefinen con el texto EXACTO de V3/V4 más la comprobación inicial.

-- --- Reglas en los triggers (todos los caminos) -------------------------------------------------

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
      current_user = 'cms_import_writer'
      AND pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE')) THEN
    RAISE EXCEPTION 'la revisión publicada solo la cambia una operación autorizada (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;

-- Inserciones en las tablas de revisión: además del indicador que activa el núcleo, el rol
-- definidor del núcleo y una sesión de la API o del importador.
CREATE OR REPLACE FUNCTION guard_article_revision_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NOT (public.require_revision_writer() AND current_user = 'cms_revision_writer'
          AND (pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE')
               OR pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE'))) THEN
    RAISE EXCEPTION 'las revisiones solo se crean con create_article_revision o restore_article_revision (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  -- La transacción de creación la fija la base de datos, nunca quien inserta.
  NEW.created_xact := pg_current_xact_id();
  RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION guard_revision_child_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NOT (public.require_revision_writer() AND current_user = 'cms_revision_writer'
          AND (pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE')
               OR pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE'))) THEN
    RAISE EXCEPTION 'los elementos de una revisión solo se añaden al crearla (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.article_revision AS r
    WHERE r.id = NEW.revision_id AND r.created_xact = pg_current_xact_id()
  ) THEN
    RAISE EXCEPTION 'la revisión % está cerrada: no admite elementos nuevos en %', NEW.revision_id, TG_TABLE_NAME
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$$;

-- --- Operaciones (texto de V3/V4 + comprobación de session_user) --------------------------------
-- CREATE OR REPLACE conserva el dueño (cms_revision_writer, cms_import_writer) y los permisos.

CREATE OR REPLACE FUNCTION insert_article_revision(
  p_article_id text,
  p_parent_revision_id bigint,
  p_origin text,
  p_content jsonb,
  p_restored_from bigint,
  OUT new_revision_id bigint,
  OUT new_number integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_required constant text[] := ARRAY['title', 'dek', 'excerpt', 'format', 'author', 'tags', 'readingMinutes', 'blocks'];
  v_optional constant text[] := ARRAY['cover', 'trip', 'project', 'seo', 'destinations'];
  v_texts constant text[] := ARRAY['title', 'dek', 'excerpt', 'format', 'author', 'cover', 'trip', 'project'];
  v_key text;
  v_head bigint;
  v_parent_number integer;
BEGIN
  -- V6: solo desde una conexión de la API (cms_app) o del importador (cms_importer). Se usa
  -- session_user, el login real: SET ROLE no lo cambia.
  IF NOT (pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE')
      OR pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE')) THEN
    RAISE EXCEPTION 'el núcleo de revisiones solo se usa desde la API o el importador (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  -- 1. Forma del contenido (el contrato de ArticleRecord, sin los campos del artículo).
  IF p_content IS NULL OR jsonb_typeof(p_content) <> 'object' THEN
    RAISE EXCEPTION 'el contenido de la revisión debe ser un objeto JSON' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  FOR v_key IN SELECT jsonb_object_keys(p_content) LOOP
    IF NOT (v_key = ANY (v_required) OR v_key = ANY (v_optional)) THEN
      RAISE EXCEPTION 'campo desconocido en la revisión: «%»', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  FOREACH v_key IN ARRAY v_required LOOP
    IF NOT p_content ? v_key THEN
      RAISE EXCEPTION 'campo obligatorio en la revisión: «%»', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  FOREACH v_key IN ARRAY v_texts LOOP
    IF p_content ? v_key AND jsonb_typeof(p_content -> v_key) <> 'string' THEN
      RAISE EXCEPTION 'el campo «%» debe ser un texto', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  IF jsonb_typeof(p_content -> 'tags') <> 'array' THEN
    RAISE EXCEPTION 'el campo «tags» debe ser una lista' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_content -> 'tags') AS e WHERE jsonb_typeof(e) <> 'string') THEN
    RAISE EXCEPTION 'el campo «tags» debe ser una lista de textos' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF p_content ? 'destinations' THEN
    IF jsonb_typeof(p_content -> 'destinations') <> 'array' THEN
      RAISE EXCEPTION 'el campo «destinations» debe ser una lista' USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_content -> 'destinations') AS e WHERE jsonb_typeof(e) <> 'string') THEN
      RAISE EXCEPTION 'el campo «destinations» debe ser una lista de textos' USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END IF;
  IF NOT public.json_is_number(p_content -> 'readingMinutes', 1, true) THEN
    RAISE EXCEPTION 'el campo «readingMinutes» debe ser un entero mayor o igual que 1'
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF p_content ? 'seo' AND jsonb_typeof(p_content -> 'seo') <> 'object' THEN
    RAISE EXCEPTION 'el campo «seo» debe ser un objeto' USING ERRCODE = 'invalid_parameter_value';
  END IF;

  -- 2. Se parte de la revisión actual: si no, es un conflicto y no se escribe nada.
  SELECT a.head_revision_id INTO v_head FROM public.article AS a WHERE a.id = p_article_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'artículo inexistente: «%»', p_article_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF v_head IS DISTINCT FROM p_parent_revision_id THEN
    RAISE EXCEPTION 'conflicto de revisiones en «%»: la revisión actual es %, no %',
      p_article_id, COALESCE(v_head::text, 'ninguna'), COALESCE(p_parent_revision_id::text, 'ninguna')
      USING ERRCODE = 'CM409';
  END IF;
  SELECT r.number INTO v_parent_number FROM public.article_revision AS r WHERE r.id = p_parent_revision_id;

  -- 3. La revisión y todos sus elementos, en esta misma transacción.
  PERFORM pg_catalog.set_config('cms.revision_writer', 'on', true);

  INSERT INTO public.article_revision AS r (
    article_id, number, parent_revision_id, restored_from_revision_id, origin,
    title, dek, excerpt, format, author_id, cover_media_id, trip_id, project_id,
    seo, destinations_present, blocks, reading_minutes)
  VALUES (
    p_article_id, COALESCE(v_parent_number, 0) + 1, p_parent_revision_id, p_restored_from, p_origin,
    p_content ->> 'title', p_content ->> 'dek', p_content ->> 'excerpt', p_content ->> 'format',
    p_content ->> 'author', p_content ->> 'cover', p_content ->> 'trip', p_content ->> 'project',
    p_content -> 'seo', p_content ? 'destinations', p_content -> 'blocks',
    (p_content ->> 'readingMinutes')::numeric::integer)
  RETURNING r.id, r.number INTO new_revision_id, new_number;

  INSERT INTO public.article_revision_tag (revision_id, position, tag_id)
  SELECT new_revision_id, t.ord::integer, t.value
  FROM jsonb_array_elements_text(p_content -> 'tags') WITH ORDINALITY AS t(value, ord);

  IF p_content ? 'destinations' THEN
    INSERT INTO public.article_revision_destination (revision_id, position, destination_id)
    SELECT new_revision_id, d.ord::integer, d.value
    FROM jsonb_array_elements_text(p_content -> 'destinations') WITH ORDINALITY AS d(value, ord);
  END IF;

  -- Referencias de los bloques: una fila por ocurrencia, con su ruta.
  INSERT INTO public.article_revision_block_ref (
    revision_id, ref_path, block_index, item_index, block_type, kind, media_id, gallery_id, trip_id, destination_id)
  WITH blocks AS (
    SELECT (b.ord - 1)::integer AS bi, b.block
    FROM jsonb_array_elements(p_content -> 'blocks') WITH ORDINALITY AS b(block, ord)
  ),
  refs AS (
    SELECT bi, NULL::integer AS ii, 'image' AS block_type, 'media' AS kind,
           format('blocks[%s].media', bi) AS ref_path, block ->> 'media' AS target
    FROM blocks WHERE block ->> 'type' = 'image'
    UNION ALL
    SELECT bi, (i.ord - 1)::integer, 'figures', 'media',
           format('blocks[%s].items[%s].media', bi, i.ord - 1), i.item ->> 'media'
    FROM blocks,
         jsonb_array_elements(CASE WHEN jsonb_typeof(block -> 'items') = 'array'
                                   THEN block -> 'items' ELSE '[]'::jsonb END) WITH ORDINALITY AS i(item, ord)
    WHERE block ->> 'type' = 'figures'
    UNION ALL
    SELECT bi, NULL, 'gallery', 'gallery', format('blocks[%s].gallery', bi), block ->> 'gallery'
    FROM blocks WHERE block ->> 'type' = 'gallery'
    UNION ALL
    SELECT bi, NULL, 'map', 'trip', format('blocks[%s].trip', bi), block ->> 'trip'
    FROM blocks WHERE block ->> 'type' = 'map' AND block ? 'trip'
    UNION ALL
    SELECT bi, (d.ord - 1)::integer, 'map', 'destination',
           format('blocks[%s].destinations[%s]', bi, d.ord - 1), d.value
    FROM blocks,
         jsonb_array_elements_text(CASE WHEN jsonb_typeof(block -> 'destinations') = 'array'
                                        THEN block -> 'destinations' ELSE '[]'::jsonb END) WITH ORDINALITY AS d(value, ord)
    WHERE block ->> 'type' = 'map'
    UNION ALL
    SELECT bi, NULL, 'chapter', 'destination', format('blocks[%s].destination', bi), block ->> 'destination'
    FROM blocks WHERE block ->> 'type' = 'chapter' AND block ? 'destination'
  )
  SELECT new_revision_id, ref_path, bi, ii, block_type, kind,
         CASE kind WHEN 'media' THEN target END,
         CASE kind WHEN 'gallery' THEN target END,
         CASE kind WHEN 'trip' THEN target END,
         CASE kind WHEN 'destination' THEN target END
  FROM refs;

  -- 4. La nueva revisión pasa a ser la de trabajo. Después, el indicador se desactiva: en el
  --    resto de la transacción nada puede añadirse por fuera.
  UPDATE public.article SET head_revision_id = new_revision_id WHERE id = p_article_id;
  PERFORM pg_catalog.set_config('cms.revision_writer', 'off', true);
END
$$;

CREATE OR REPLACE FUNCTION create_article_revision(
  p_article_id text,
  p_parent_revision_id bigint,
  p_origin text,
  p_content jsonb,
  OUT new_revision_id bigint,
  OUT new_number integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  -- V6: solo desde una conexión de la API (login miembro de cms_app), según session_user.
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE') THEN
    RAISE EXCEPTION 'create_article_revision solo puede usarla una conexión de la API (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF p_origin IS NULL OR p_origin NOT IN ('import', 'edit') THEN
    RAISE EXCEPTION 'origen no permitido: «%» (las restauraciones usan restore_article_revision)', p_origin
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  SELECT i.new_revision_id, i.new_number INTO new_revision_id, new_number
  FROM public.insert_article_revision(p_article_id, p_parent_revision_id, p_origin, p_content, NULL) AS i;
END
$$;

CREATE OR REPLACE FUNCTION restore_article_revision(
  p_revision_id bigint,
  p_parent_revision_id bigint,
  OUT new_revision_id bigint,
  OUT new_number integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_article_id text;
BEGIN
  -- V6: solo desde una conexión de la API (login miembro de cms_app), según session_user.
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_app', 'USAGE') THEN
    RAISE EXCEPTION 'restore_article_revision solo puede usarla una conexión de la API (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  SELECT r.article_id INTO v_article_id FROM public.article_revision AS r WHERE r.id = p_revision_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'revisión inexistente: %', p_revision_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  SELECT i.new_revision_id, i.new_number INTO new_revision_id, new_number
  FROM public.insert_article_revision(
    v_article_id, p_parent_revision_id, 'restore', public.article_revision_content(p_revision_id), p_revision_id) AS i;
END
$$;

CREATE OR REPLACE FUNCTION import_published_article(
  p_record jsonb,
  p_blocks jsonb,
  p_catalog_position integer,
  OUT article_id text,
  OUT revision_id bigint)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_identity constant text[] := ARRAY['id', 'slug', 'category', 'order', 'publishedAt', 'updatedAt', 'featured', 'demo'];
  v_content constant text[] := ARRAY['title', 'dek', 'excerpt', 'tags', 'format', 'cover', 'author',
                                     'readingMinutes', 'trip', 'destinations', 'project', 'seo'];
  v_required constant text[] := ARRAY['id', 'slug', 'title', 'dek', 'excerpt', 'category', 'tags', 'format', 'author',
                                      'order', 'publishedAt', 'readingMinutes', 'featured', 'demo'];
  v_key text;
  v_content_json jsonb;
  v_revision bigint;
BEGIN
  -- V6: solo desde una conexión del importador (login miembro de cms_importer), según session_user.
  -- cms_migrator no puede importar aunque haga SET ROLE cms_owner o asuma cms_import_writer.
  IF NOT pg_catalog.pg_has_role(session_user, 'cms_importer', 'USAGE') THEN
    RAISE EXCEPTION 'la importación solo puede ejecutarla una conexión del importador (sesión: %, rol: %)',
      session_user, current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF p_record IS NULL OR jsonb_typeof(p_record) <> 'object' THEN
    RAISE EXCEPTION 'el registro del artículo debe ser un objeto JSON' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  FOR v_key IN SELECT jsonb_object_keys(p_record) LOOP
    IF NOT (v_key = ANY (v_identity) OR v_key = ANY (v_content)) THEN
      RAISE EXCEPTION 'campo desconocido en el artículo: «%»', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  FOREACH v_key IN ARRAY v_required LOOP
    IF NOT p_record ? v_key THEN
      RAISE EXCEPTION 'campo obligatorio en el artículo: «%»', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  -- El contrato (validate.ts) no admite null en ningún campo de ArticleRecord.
  FOR v_key IN SELECT e.key FROM jsonb_each(p_record) AS e WHERE jsonb_typeof(e.value) = 'null' LOOP
    RAISE EXCEPTION 'el campo «%» no admite null', v_key USING ERRCODE = 'invalid_parameter_value';
  END LOOP;
  FOREACH v_key IN ARRAY ARRAY['id', 'slug', 'category', 'publishedAt', 'updatedAt'] LOOP
    IF p_record ? v_key AND jsonb_typeof(p_record -> v_key) <> 'string' THEN
      RAISE EXCEPTION 'el campo «%» debe ser un texto', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  FOREACH v_key IN ARRAY ARRAY['publishedAt', 'updatedAt'] LOOP
    IF p_record ? v_key AND (p_record ->> v_key) !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN
      RAISE EXCEPTION 'el campo «%» debe ser una fecha AAAA-MM-DD', v_key USING ERRCODE = 'invalid_parameter_value';
    END IF;
  END LOOP;
  IF NOT public.json_is_number(p_record -> 'order', 1, true) THEN
    RAISE EXCEPTION 'el campo «order» debe ser un entero mayor o igual que 1' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF jsonb_typeof(p_record -> 'featured') <> 'boolean' OR jsonb_typeof(p_record -> 'demo') <> 'boolean' THEN
    RAISE EXCEPTION 'los campos «featured» y «demo» deben ser true o false' USING ERRCODE = 'invalid_parameter_value';
  END IF;
  IF EXISTS (SELECT 1 FROM public.article AS a WHERE a.id = p_record ->> 'id') THEN
    RAISE EXCEPTION 'el artículo «%» ya existe: la importación inicial no actualiza artículos', p_record ->> 'id'
      USING ERRCODE = 'unique_violation';
  END IF;

  -- 1. Identidad y lugar editorial (sin punteros).
  INSERT INTO public.article (id, slug, category_id, editorial_order, featured, demo, published_at, updated_at,
                              catalog_position)
  VALUES (p_record ->> 'id', p_record ->> 'slug', p_record ->> 'category', (p_record ->> 'order')::numeric::integer,
          (p_record ->> 'featured')::boolean, (p_record ->> 'demo')::boolean, (p_record ->> 'publishedAt')::date,
          (p_record ->> 'updatedAt')::date, p_catalog_position);

  -- 2. Revisión 1: el contenido del registro más los bloques, por el núcleo común.
  SELECT jsonb_object_agg(e.key, e.value) INTO v_content_json
  FROM jsonb_each(p_record) AS e WHERE e.key = ANY (v_content);
  v_content_json := v_content_json || jsonb_build_object('blocks', p_blocks);
  SELECT i.new_revision_id INTO v_revision
  FROM public.insert_article_revision(p_record ->> 'id', NULL, 'import', v_content_json, NULL) AS i;

  -- 3. El contenido importado es el publicado.
  UPDATE public.article AS a SET published_revision_id = v_revision WHERE a.id = p_record ->> 'id';

  article_id := p_record ->> 'id';
  revision_id := v_revision;
END
$$;

-- --- EXECUTE: nada para PUBLIC --------------------------------------------------------------------
-- PUBLIC ya no tiene USAGE en el esquema (6.2.1), pero se retira también el EXECUTE por defecto
-- de todas las funciones y se concede solo a quien las usa. Las de validación se evalúan en los
-- CHECK con los privilegios de quien escribe; require_revision_writer, en los triggers de las
-- tablas de revisión (dentro del núcleo, como cms_revision_writer).

REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
GRANT EXECUTE ON FUNCTION json_is_text(jsonb), json_is_number(jsonb, numeric, boolean),
  media_camera_valid(jsonb), media_widths_valid(integer[], integer), article_seo_valid(jsonb),
  article_blocks_valid(jsonb)
  TO cms_app, cms_importer, cms_revision_writer, cms_import_writer;
GRANT EXECUTE ON FUNCTION require_revision_writer() TO cms_revision_writer;

-- Las funciones que creen migraciones futuras tampoco tendrán EXECUTE para PUBLIC.
ALTER DEFAULT PRIVILEGES FOR ROLE cms_owner IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_revision_writer IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_import_writer IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE cms_audit_retention IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
