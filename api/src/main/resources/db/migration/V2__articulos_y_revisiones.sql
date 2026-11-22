-- =============================================================================================
-- V2 · Artículos y revisiones.
--
--   article          identidad estable y lugar editorial (slug, sección, orden, destacado,
--                    demo, fechas) y los punteros a la revisión de trabajo y a la publicada.
--   article_revision contenido versionado e INMUTABLE (título, entradilla, etiquetas, cuerpo…).
--                    Solo se crea con create_article_revision / restore_article_revision (V3).
--
-- ArticleRecord = article + su revisión publicada. ArticleBodyRecord = article.slug + blocks.
-- =============================================================================================

-- --- Funciones de comprobación del contenido de una revisión --------------------------------

-- ArticleSeo: objeto con, como mucho, una descripción no vacía.
CREATE FUNCTION article_seo_valid(p jsonb) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(p) = 'object' THEN COALESCE(
      NOT EXISTS (SELECT 1 FROM jsonb_object_keys(p) AS k WHERE k <> 'description')
      AND (NOT p ? 'description' OR json_is_text(p -> 'description')),
      false)
    ELSE false
  END
$$;

-- Cuerpo: lista no vacía de bloques, cada uno un objeto con un `type` del contrato. La forma
-- campo a campo de cada bloque la valida el backend al guardar y la puerta al publicar.
CREATE FUNCTION article_blocks_valid(p jsonb) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(p) = 'array' THEN COALESCE(
      jsonb_array_length(p) >= 1
      AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(p) AS b
        WHERE jsonb_typeof(b) <> 'object'
           OR jsonb_typeof(b -> 'type') IS DISTINCT FROM 'string'
           OR (b ->> 'type') NOT IN ('text', 'image', 'figures', 'gallery', 'quote', 'map', 'code',
                                     'specs', 'chapter', 'divider')),
      false)
    ELSE false
  END
$$;

-- --- Artículo -----------------------------------------------------------------------------

CREATE TABLE article (
  id                    d_slug     PRIMARY KEY,
  slug                  d_slug     NOT NULL UNIQUE,
  category_id           text       NOT NULL REFERENCES category (id) ON DELETE RESTRICT,
  -- Orden editorial dentro de la sección. NULL = sin orden (un borrador futuro).
  editorial_order       d_position,
  featured              boolean    NOT NULL,
  demo                  boolean    NOT NULL,
  published_at          d_date,
  updated_at            d_date,
  -- Revisión de trabajo actual (la cambia solo create_article_revision) y publicada.
  head_revision_id      bigint,
  published_revision_id bigint,
  catalog_position      d_position NOT NULL,
  lock_version          integer    NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  created_at            timestamptz NOT NULL DEFAULT now(),
  -- Orden único dentro de cada sección. Los NULL son distintos: los borradores no chocan.
  CONSTRAINT article_order_key UNIQUE (category_id, editorial_order) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT article_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT article_updated_after_published_check CHECK (
    updated_at IS NULL OR (published_at IS NOT NULL AND updated_at >= published_at)),
  -- Un artículo publicado tiene revisión de trabajo, fecha y orden (ArticleRecord los exige).
  CONSTRAINT article_published_complete_check CHECK (
    published_revision_id IS NULL
    OR (head_revision_id IS NOT NULL AND published_at IS NOT NULL AND editorial_order IS NOT NULL))
);
CREATE INDEX article_published_at_idx ON article (published_at DESC);
CREATE INDEX article_featured_idx ON article (featured) WHERE featured;

-- --- Revisión (inmutable) -----------------------------------------------------------------

CREATE TABLE article_revision (
  id                        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  article_id                d_slug      NOT NULL REFERENCES article (id) ON DELETE RESTRICT,
  number                    d_position  NOT NULL,
  -- Historial lineal: cada revisión parte de la anterior (NULL solo la primera).
  parent_revision_id        bigint,
  -- Restauración: la revisión cuyo contenido se recuperó.
  restored_from_revision_id bigint,
  origin                    text        NOT NULL CHECK (origin IN ('import', 'edit', 'restore')),
  title                     d_text      NOT NULL,
  dek                       d_text      NOT NULL,
  excerpt                   d_text      NOT NULL,
  format                    text        NOT NULL
                                        CHECK (format IN ('reportaje', 'guia', 'ensayo-fotografico', 'ficha', 'nota')),
  author_id                 d_slug      NOT NULL REFERENCES author (id) ON DELETE RESTRICT,
  cover_media_id            d_slug      REFERENCES media (id) ON DELETE RESTRICT,
  trip_id                   d_slug      REFERENCES trip (id) ON DELETE RESTRICT,
  project_id                d_slug      REFERENCES project (id) ON DELETE RESTRICT,
  -- seo: NULL = ausente; {} = presente sin datos.
  seo                       jsonb       CHECK (seo IS NULL OR article_seo_valid(seo)),
  -- destinations: el contrato distingue la lista ausente de la lista vacía.
  destinations_present      boolean     NOT NULL,
  -- ContentBlockRecord[] tal cual (el contrato del cuerpo).
  blocks                    jsonb       NOT NULL CHECK (article_blocks_valid(blocks)),
  reading_minutes           d_position  NOT NULL,
  -- Transacción que creó la revisión: sus elementos solo pueden añadirse en ella (V3).
  created_xact              xid8        NOT NULL DEFAULT pg_current_xact_id(),
  created_at                timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT article_revision_number_key UNIQUE (article_id, number),
  CONSTRAINT article_revision_id_article_key UNIQUE (id, article_id),
  -- Una revisión tiene como mucho una hija: no hay ramas ni guardados sobre versiones viejas.
  CONSTRAINT article_revision_parent_key UNIQUE (parent_revision_id),
  CONSTRAINT article_revision_parent_fkey FOREIGN KEY (parent_revision_id, article_id)
    REFERENCES article_revision (id, article_id) ON DELETE RESTRICT,
  CONSTRAINT article_revision_restored_from_fkey FOREIGN KEY (restored_from_revision_id, article_id)
    REFERENCES article_revision (id, article_id) ON DELETE RESTRICT,
  CONSTRAINT article_revision_first_check CHECK ((number = 1) = (parent_revision_id IS NULL)),
  CONSTRAINT article_revision_restore_check CHECK ((origin = 'restore') = (restored_from_revision_id IS NOT NULL))
);
CREATE INDEX article_revision_parent_idx ON article_revision (parent_revision_id, article_id);
CREATE INDEX article_revision_restored_from_idx ON article_revision (restored_from_revision_id, article_id);
CREATE INDEX article_revision_author_id_idx ON article_revision (author_id);
CREATE INDEX article_revision_cover_media_id_idx ON article_revision (cover_media_id);
CREATE INDEX article_revision_trip_id_idx ON article_revision (trip_id);
CREATE INDEX article_revision_project_id_idx ON article_revision (project_id);

-- Punteros del artículo: solo a revisiones del propio artículo.
ALTER TABLE article
  ADD CONSTRAINT article_head_revision_fkey FOREIGN KEY (head_revision_id, id)
    REFERENCES article_revision (id, article_id) ON DELETE RESTRICT,
  ADD CONSTRAINT article_published_revision_fkey FOREIGN KEY (published_revision_id, id)
    REFERENCES article_revision (id, article_id) ON DELETE RESTRICT;
CREATE INDEX article_head_revision_idx ON article (head_revision_id, id);
CREATE INDEX article_published_revision_idx ON article (published_revision_id, id);

-- --- Elementos de la revisión (inmutables) -------------------------------------------------

-- ArticleRecord.tags[], en su orden.
CREATE TABLE article_revision_tag (
  revision_id bigint     NOT NULL REFERENCES article_revision (id) ON DELETE RESTRICT,
  position    d_position NOT NULL,
  tag_id      d_slug     NOT NULL REFERENCES tag (id) ON DELETE RESTRICT,
  CONSTRAINT article_revision_tag_pkey PRIMARY KEY (revision_id, position)
);
CREATE INDEX article_revision_tag_tag_id_idx ON article_revision_tag (tag_id);

-- ArticleRecord.destinations[], en su orden.
CREATE TABLE article_revision_destination (
  revision_id    bigint     NOT NULL REFERENCES article_revision (id) ON DELETE RESTRICT,
  position       d_position NOT NULL,
  destination_id d_slug     NOT NULL REFERENCES destination (id) ON DELETE RESTRICT,
  CONSTRAINT article_revision_destination_pkey PRIMARY KEY (revision_id, position)
);
CREATE INDEX article_revision_destination_destination_id_idx ON article_revision_destination (destination_id);

-- Cada referencia que aparece dentro de los bloques, una fila por ocurrencia:
--   image   → blocks[i].media             figures → blocks[i].items[k].media
--   gallery → blocks[i].gallery           map     → blocks[i].trip | blocks[i].destinations[k]
--   chapter → blocks[i].destination
-- Índices desde 0, como en las rutas del validador. La rellena create_article_revision (V3).
CREATE TABLE article_revision_block_ref (
  revision_id    bigint  NOT NULL REFERENCES article_revision (id) ON DELETE RESTRICT,
  ref_path       text    NOT NULL CHECK (ref_path ~ '^blocks\[[0-9]+\]\.[a-z]+(\[[0-9]+\](\.[a-z]+)?)?$'),
  block_index    integer NOT NULL CHECK (block_index >= 0),
  item_index     integer CHECK (item_index >= 0),
  block_type     text    NOT NULL CHECK (block_type IN ('image', 'figures', 'gallery', 'map', 'chapter')),
  kind           text    NOT NULL CHECK (kind IN ('media', 'gallery', 'trip', 'destination')),
  media_id       d_slug  REFERENCES media (id) ON DELETE RESTRICT,
  gallery_id     d_slug  REFERENCES gallery (id) ON DELETE RESTRICT,
  trip_id        d_slug  REFERENCES trip (id) ON DELETE RESTRICT,
  destination_id d_slug  REFERENCES destination (id) ON DELETE RESTRICT,
  CONSTRAINT article_revision_block_ref_pkey PRIMARY KEY (revision_id, ref_path),
  CONSTRAINT article_revision_block_ref_target_check CHECK (
    num_nonnulls(media_id, gallery_id, trip_id, destination_id) = 1
    AND (kind = 'media') = (media_id IS NOT NULL)
    AND (kind = 'gallery') = (gallery_id IS NOT NULL)
    AND (kind = 'trip') = (trip_id IS NOT NULL)
    AND (kind = 'destination') = (destination_id IS NOT NULL))
);
CREATE INDEX article_revision_block_ref_media_idx ON article_revision_block_ref (media_id) WHERE media_id IS NOT NULL;
CREATE INDEX article_revision_block_ref_gallery_idx ON article_revision_block_ref (gallery_id) WHERE gallery_id IS NOT NULL;
CREATE INDEX article_revision_block_ref_trip_idx ON article_revision_block_ref (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX article_revision_block_ref_destination_idx ON article_revision_block_ref (destination_id)
  WHERE destination_id IS NOT NULL;
