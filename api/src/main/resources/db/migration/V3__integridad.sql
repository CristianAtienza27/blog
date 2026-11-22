-- =============================================================================================
-- V3 · Integridad: reglas que cruzan tablas, imágenes retiradas, revisiones inmutables y
-- selladas, la operación controlada de creación y restauración de revisiones, y los roles.
--
-- Todas las funciones fijan search_path (pg_catalog primero, pg_temp al final) y nombran los
-- objetos con su esquema. Las funciones SECURITY DEFINER pertenecen a cms_revision_writer, un
-- rol sin inicio de sesión, que no es superusuario y solo tiene los privilegios imprescindibles.
-- =============================================================================================

-- --- Roles ----------------------------------------------------------------------------------
-- Los roles son del clúster, no de la base de datos: se crean solo si no existen.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cms_app') THEN
    CREATE ROLE cms_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cms_revision_writer') THEN
    CREATE ROLE cms_revision_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

-- --- Imágenes: original obligatorio y reglas que dependen de él -----------------------------

CREATE FUNCTION check_media_original() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  -- NEW es NULL al borrar; to_jsonb evita nombrar columnas que solo existen en una tabla.
  v_row jsonb := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  v_media_id text := CASE TG_TABLE_NAME WHEN 'media' THEN v_row ->> 'id' ELSE v_row ->> 'media_id' END;
  m public.media;
  o public.media_original;
BEGIN
  SELECT * INTO m FROM public.media WHERE id = v_media_id;
  IF NOT FOUND THEN
    RETURN NULL; -- la imagen ya no existe (su original se borra en cascada)
  END IF;
  SELECT * INTO o FROM public.media_original WHERE media_id = v_media_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'la imagen «%» no tiene original (MediaRecord.original es obligatorio)', v_media_id
      USING ERRCODE = 'not_null_violation';
  END IF;
  IF m.credit_source = 'propia' AND o.url IS NOT NULL THEN
    RAISE EXCEPTION 'el original de una foto propia no es público: «%» no puede tener URL', v_media_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF m.credit_source <> 'propia' AND o.url IS NULL THEN
    RAISE EXCEPTION 'la URL del original es obligatoria en fuentes externas («%»)', v_media_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF m.width > greatest(o.width, o.height) THEN
    RAISE EXCEPTION 'la versión optimizada de «%» no puede superar al original', v_media_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER media_original_required
  AFTER INSERT OR UPDATE ON media DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION check_media_original();
CREATE CONSTRAINT TRIGGER media_original_consistent
  AFTER INSERT OR UPDATE OR DELETE ON media_original DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION check_media_original();

-- --- Imágenes retiradas: ningún uso nuevo ----------------------------------------------------
-- Uso nuevo = insertar una fila que referencie la imagen o cambiar la referencia hacia ella.
-- Las filas que ya la usaban (referencias históricas) siguen siendo válidas y modificables.

CREATE FUNCTION forbid_retired_media() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_column text := TG_ARGV[0];
  v_new text := to_jsonb(NEW) ->> v_column;
  v_old text;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    v_old := to_jsonb(OLD) ->> v_column;
  END IF;
  IF v_new IS NOT NULL
     AND (TG_OP = 'INSERT' OR v_new IS DISTINCT FROM v_old)
     AND EXISTS (SELECT 1 FROM public.media WHERE id = v_new AND retired) THEN
    RAISE EXCEPTION 'la imagen «%» está retirada: no admite usos nuevos (%.%)', v_new, TG_TABLE_NAME, v_column
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER author_avatar_not_retired BEFORE INSERT OR UPDATE OF avatar_media_id ON author
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('avatar_media_id');
CREATE TRIGGER destination_cover_not_retired BEFORE INSERT OR UPDATE OF cover_media_id ON destination
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('cover_media_id');
CREATE TRIGGER trip_cover_not_retired BEFORE INSERT OR UPDATE OF cover_media_id ON trip
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('cover_media_id');
CREATE TRIGGER project_cover_not_retired BEFORE INSERT OR UPDATE OF cover_media_id ON project
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('cover_media_id');
CREATE TRIGGER gallery_item_media_not_retired BEFORE INSERT OR UPDATE OF media_id ON gallery_item
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('media_id');
CREATE TRIGGER media_reserved_use_not_retired BEFORE INSERT OR UPDATE OF media_id ON media_reserved_use
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('media_id');
-- Cada revisión es un uso nuevo (también al restaurar una antigua).
CREATE TRIGGER article_revision_cover_not_retired BEFORE INSERT ON article_revision
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('cover_media_id');
CREATE TRIGGER article_revision_block_ref_media_not_retired BEFORE INSERT ON article_revision_block_ref
  FOR EACH ROW EXECUTE FUNCTION forbid_retired_media('media_id');

-- --- Revisiones: inmutables, creadas solo por la operación controlada y selladas -------------

-- Ni modificar ni borrar el historial.
CREATE FUNCTION forbid_revision_changes() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  RAISE EXCEPTION 'las revisiones son inmutables: % no está permitido en %', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'integrity_constraint_violation';
END
$$;

-- Entrada única: solo create_article_revision / restore_article_revision activan el indicador.
CREATE FUNCTION require_revision_writer() RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp
AS $$
  SELECT COALESCE(current_setting('cms.revision_writer', true) = 'on', false)
$$;

CREATE FUNCTION guard_article_revision_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NOT public.require_revision_writer() THEN
    RAISE EXCEPTION 'las revisiones solo se crean con create_article_revision o restore_article_revision'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  -- La transacción de creación la fija la base de datos, nunca quien inserta.
  NEW.created_xact := pg_current_xact_id();
  RETURN NEW;
END
$$;

-- Sellado: los elementos de una revisión solo pueden añadirse en la transacción que la creó.
CREATE FUNCTION guard_revision_child_insert() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF NOT public.require_revision_writer() THEN
    RAISE EXCEPTION 'los elementos de una revisión solo se añaden al crearla (create_article_revision)'
      USING ERRCODE = 'insufficient_privilege';
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

CREATE TRIGGER article_revision_insert_guard BEFORE INSERT ON article_revision
  FOR EACH ROW EXECUTE FUNCTION guard_article_revision_insert();

CREATE TRIGGER article_revision_immutable BEFORE UPDATE OR DELETE ON article_revision
  FOR EACH ROW EXECUTE FUNCTION forbid_revision_changes();
CREATE TRIGGER article_revision_no_truncate BEFORE TRUNCATE ON article_revision
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_revision_changes();

CREATE TRIGGER article_revision_tag_sealed BEFORE INSERT ON article_revision_tag
  FOR EACH ROW EXECUTE FUNCTION guard_revision_child_insert();
CREATE TRIGGER article_revision_tag_immutable BEFORE UPDATE OR DELETE ON article_revision_tag
  FOR EACH ROW EXECUTE FUNCTION forbid_revision_changes();
CREATE TRIGGER article_revision_tag_no_truncate BEFORE TRUNCATE ON article_revision_tag
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_revision_changes();

CREATE TRIGGER article_revision_destination_sealed BEFORE INSERT ON article_revision_destination
  FOR EACH ROW EXECUTE FUNCTION guard_revision_child_insert();
CREATE TRIGGER article_revision_destination_immutable BEFORE UPDATE OR DELETE ON article_revision_destination
  FOR EACH ROW EXECUTE FUNCTION forbid_revision_changes();
CREATE TRIGGER article_revision_destination_no_truncate BEFORE TRUNCATE ON article_revision_destination
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_revision_changes();

CREATE TRIGGER article_revision_block_ref_sealed BEFORE INSERT ON article_revision_block_ref
  FOR EACH ROW EXECUTE FUNCTION guard_revision_child_insert();
CREATE TRIGGER article_revision_block_ref_immutable BEFORE UPDATE OR DELETE ON article_revision_block_ref
  FOR EACH ROW EXECUTE FUNCTION forbid_revision_changes();
CREATE TRIGGER article_revision_block_ref_no_truncate BEFORE TRUNCATE ON article_revision_block_ref
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_revision_changes();

-- Punteros del artículo: un artículo nace sin revisiones y solo la operación controlada mueve
-- la revisión de trabajo.
CREATE FUNCTION guard_article_pointers() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.head_revision_id IS NOT NULL OR NEW.published_revision_id IS NOT NULL THEN
      RAISE EXCEPTION 'un artículo nuevo no tiene revisiones: créalas con create_article_revision'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSIF NEW.head_revision_id IS DISTINCT FROM OLD.head_revision_id AND NOT public.require_revision_writer() THEN
    RAISE EXCEPTION 'la revisión de trabajo solo la cambian create_article_revision y restore_article_revision'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER article_pointers_guard BEFORE INSERT OR UPDATE OF head_revision_id ON article
  FOR EACH ROW EXECUTE FUNCTION guard_article_pointers();

-- --- Operación controlada de creación de revisiones -------------------------------------------

-- Contenido de una revisión con el contrato de ArticleRecord (+ blocks). Solo devuelve las
-- claves presentes: ausente ≠ vacío.
CREATE FUNCTION article_revision_content(p_revision_id bigint) RETURNS jsonb
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp
AS $$
  SELECT jsonb_build_object(
           'title', r.title,
           'dek', r.dek,
           'excerpt', r.excerpt,
           'format', r.format,
           'author', r.author_id,
           'tags', COALESCE((SELECT jsonb_agg(t.tag_id ORDER BY t.position)
                             FROM public.article_revision_tag AS t WHERE t.revision_id = r.id), '[]'::jsonb),
           'readingMinutes', r.reading_minutes,
           'blocks', r.blocks)
         || CASE WHEN r.cover_media_id IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('cover', r.cover_media_id) END
         || CASE WHEN r.trip_id IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('trip', r.trip_id) END
         || CASE WHEN r.project_id IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('project', r.project_id) END
         || CASE WHEN r.seo IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('seo', r.seo) END
         || CASE WHEN NOT r.destinations_present THEN '{}'::jsonb
                 ELSE jsonb_build_object('destinations', COALESCE(
                   (SELECT jsonb_agg(d.destination_id ORDER BY d.position)
                    FROM public.article_revision_destination AS d WHERE d.revision_id = r.id), '[]'::jsonb))
            END
  FROM public.article_revision AS r
  WHERE r.id = p_revision_id
$$;

-- Núcleo interno (no ejecutable por la aplicación): valida el contenido, comprueba que se parte
-- de la revisión actual y crea la revisión con todos sus elementos en la misma transacción.
CREATE FUNCTION insert_article_revision(
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

-- API pública: guardar una revisión nueva (edición o importación) a partir de la actual.
CREATE FUNCTION create_article_revision(
  p_article_id text,
  p_parent_revision_id bigint,
  p_origin text,
  p_content jsonb,
  OUT new_revision_id bigint,
  OUT new_number integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
  IF p_origin IS NULL OR p_origin NOT IN ('import', 'edit') THEN
    RAISE EXCEPTION 'origen no permitido: «%» (las restauraciones usan restore_article_revision)', p_origin
      USING ERRCODE = 'invalid_parameter_value';
  END IF;
  SELECT i.new_revision_id, i.new_number INTO new_revision_id, new_number
  FROM public.insert_article_revision(p_article_id, p_parent_revision_id, p_origin, p_content, NULL) AS i;
END
$$;

-- API pública: recuperar una revisión anterior como revisión NUEVA (N+1), sin tocar el historial.
CREATE FUNCTION restore_article_revision(
  p_revision_id bigint,
  p_parent_revision_id bigint,
  OUT new_revision_id bigint,
  OUT new_number integer)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_article_id text;
BEGIN
  SELECT r.article_id INTO v_article_id FROM public.article_revision AS r WHERE r.id = p_revision_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'revisión inexistente: %', p_revision_id USING ERRCODE = 'foreign_key_violation';
  END IF;
  SELECT i.new_revision_id, i.new_number INTO new_revision_id, new_number
  FROM public.insert_article_revision(
    v_article_id, p_parent_revision_id, 'restore', public.article_revision_content(p_revision_id), p_revision_id) AS i;
END
$$;

-- --- Privilegios ------------------------------------------------------------------------------

-- cms_revision_writer: dueño de las funciones SECURITY DEFINER, con lo mínimo para ejecutarlas.
GRANT USAGE ON SCHEMA public TO cms_revision_writer;
GRANT SELECT ON article, article_revision, article_revision_tag, article_revision_destination,
  article_revision_block_ref, media TO cms_revision_writer;
GRANT INSERT ON article_revision, article_revision_tag, article_revision_destination,
  article_revision_block_ref TO cms_revision_writer;
GRANT UPDATE (head_revision_id) ON article TO cms_revision_writer;

ALTER FUNCTION article_revision_content(bigint) OWNER TO cms_revision_writer;
ALTER FUNCTION insert_article_revision(text, bigint, text, jsonb, bigint) OWNER TO cms_revision_writer;
ALTER FUNCTION create_article_revision(text, bigint, text, jsonb) OWNER TO cms_revision_writer;
ALTER FUNCTION restore_article_revision(bigint, bigint) OWNER TO cms_revision_writer;

-- Nadie las ejecuta por defecto; la aplicación solo las dos operaciones públicas.
REVOKE ALL ON FUNCTION article_revision_content(bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION insert_article_revision(text, bigint, text, jsonb, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION create_article_revision(text, bigint, text, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION restore_article_revision(bigint, bigint) FROM PUBLIC;

-- cms_app: el rol de la aplicación. Lee todo; escribe el catálogo y los datos del artículo,
-- pero NUNCA las revisiones ni los punteros a revisiones: eso solo por las funciones.
GRANT USAGE ON SCHEMA public TO cms_app;
GRANT SELECT ON category, tag, author, media, media_original, media_reserved_use, gallery, gallery_item,
  destination, trip, trip_stop, project, project_stack_item, article, article_revision,
  article_revision_tag, article_revision_destination, article_revision_block_ref TO cms_app;
GRANT INSERT, UPDATE, DELETE ON category, tag, author, media, media_original, media_reserved_use, gallery,
  gallery_item, destination, trip, trip_stop, project, project_stack_item TO cms_app;
GRANT INSERT ON article TO cms_app;
GRANT UPDATE (slug, category_id, editorial_order, featured, demo, published_at, updated_at,
  catalog_position, lock_version) ON article TO cms_app;
GRANT EXECUTE ON FUNCTION create_article_revision(text, bigint, text, jsonb) TO cms_app;
GRANT EXECUTE ON FUNCTION restore_article_revision(bigint, bigint) TO cms_app;
