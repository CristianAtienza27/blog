-- =============================================================================================
-- V4 · Importación del contenido publicado.
--
--   import_published_article(...)  operación controlada: crea el artículo, su revisión inicial y
--                                  los punteros de trabajo y publicado en una transacción.
--   cms_importer                   el importador autorizado: inserta el catálogo y ejecuta la
--                                  operación; no modifica ni borra nada ni toca los punteros.
--   cms_import_writer              dueño de la operación, con los privilegios mínimos.
--
-- Los punteros de article dejan de depender de un indicador de sesión: solo puede moverlos el
-- rol definidor de la operación autorizada (current_user), al que nadie accede salvo a través
-- de esa función.
-- =============================================================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cms_importer') THEN
    CREATE ROLE cms_importer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cms_import_writer') THEN
    CREATE ROLE cms_import_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

-- --- Punteros de article: por identidad del rol, no por una variable de sesión ---------------
--   head_revision_id      solo cms_revision_writer (create/restore/insert_article_revision)
--   published_revision_id solo cms_import_writer (import_published_article; la publicación de
--                         la fase 6.3 añadirá su propio rol)
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
  IF NEW.head_revision_id IS DISTINCT FROM OLD.head_revision_id AND current_user <> 'cms_revision_writer' THEN
    RAISE EXCEPTION 'la revisión de trabajo solo la cambian create_article_revision y restore_article_revision (rol actual: %)',
      current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.published_revision_id IS DISTINCT FROM OLD.published_revision_id AND current_user <> 'cms_import_writer' THEN
    RAISE EXCEPTION 'la revisión publicada solo la cambia una operación autorizada (rol actual: %)',
      current_user USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;

DROP TRIGGER article_pointers_guard ON article;
CREATE TRIGGER article_pointers_guard BEFORE INSERT OR UPDATE OF head_revision_id, published_revision_id ON article
  FOR EACH ROW EXECUTE FUNCTION guard_article_pointers();

-- --- Operación controlada de importación -------------------------------------------------------

-- p_record: el registro de articles.json tal cual (ArticleRecord).
-- p_blocks: los bloques de articles/<slug>.json.
-- Separa la identidad (article) del contenido (revisión 1, con el mismo núcleo y las mismas
-- comprobaciones que cualquier revisión) y publica esa revisión.
CREATE FUNCTION import_published_article(
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

-- --- Privilegios ------------------------------------------------------------------------------

-- Dueño de la operación: lo mínimo para ejecutarla.
GRANT USAGE ON SCHEMA public TO cms_import_writer;
GRANT SELECT, INSERT ON article TO cms_import_writer;
GRANT UPDATE (published_revision_id) ON article TO cms_import_writer;
GRANT EXECUTE ON FUNCTION insert_article_revision(text, bigint, text, jsonb, bigint) TO cms_import_writer;
ALTER FUNCTION import_published_article(jsonb, jsonb, integer) OWNER TO cms_import_writer;
REVOKE ALL ON FUNCTION import_published_article(jsonb, jsonb, integer) FROM PUBLIC;

-- Importador autorizado: lee todo, inserta el catálogo y ejecuta la operación. Nada más.
GRANT USAGE ON SCHEMA public TO cms_importer;
GRANT SELECT ON category, tag, author, media, media_original, media_reserved_use, gallery, gallery_item,
  destination, trip, trip_stop, project, project_stack_item, article, article_revision,
  article_revision_tag, article_revision_destination, article_revision_block_ref TO cms_importer;
GRANT INSERT ON category, tag, author, media, media_original, media_reserved_use, gallery, gallery_item,
  destination, trip, trip_stop, project, project_stack_item TO cms_importer;
GRANT EXECUTE ON FUNCTION import_published_article(jsonb, jsonb, integer) TO cms_importer;
GRANT EXECUTE ON FUNCTION article_revision_content(bigint) TO cms_importer;
