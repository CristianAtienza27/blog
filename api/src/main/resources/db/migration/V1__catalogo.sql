-- =============================================================================================
-- V1 · Catálogo editorial: taxonomía, autoría, imágenes, galerías, lugares, itinerarios y
-- proyectos. Representa el contrato de src/content (content/records/records.ts) con las reglas
-- de forma de content/records/validate.ts. Las relaciones entre colecciones que necesitan ver
-- el conjunto completo siguen en la puerta de publicación (integrity.ts).
--
-- Convenciones:
--   · NULL en una columna opcional = campo ausente en el JSON.
--   · catalog_position = posición del registro en su archivo JSON (orden del catálogo).
--   · Las restricciones de orden son diferibles: se comprueban al confirmar la transacción.
-- =============================================================================================

-- --- Tipos de dominio: las reglas de forma del validador TypeScript ------------------------

-- Identificadores y slugs: minúsculas, dígitos y guiones simples.
CREATE DOMAIN d_slug AS text CHECK (VALUE ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
-- Texto no vacío (con al menos un carácter que no sea espacio).
CREATE DOMAIN d_text AS text CHECK (VALUE ~ '[^[:space:]]');
-- URL https sin espacios, comillas ni ángulos.
CREATE DOMAIN d_https_url AS text CHECK (VALUE ~ '^https://[^[:space:]"''<>]+$');
-- Código ISO 3166-1 alfa-2.
CREATE DOMAIN d_country AS text CHECK (VALUE ~ '^[A-Z]{2}$');
-- Fecha del contrato (AAAA-MM-DD): cuatro cifras de año.
CREATE DOMAIN d_date AS date CHECK (VALUE BETWEEN DATE '0001-01-01' AND DATE '9999-12-31');
-- SHA-256 en hexadecimal.
CREATE DOMAIN d_hex64 AS text CHECK (VALUE ~ '^[a-f0-9]{64}$');
-- Posición en una lista, desde 1.
CREATE DOMAIN d_position AS integer CHECK (VALUE >= 1);

-- --- Funciones de comprobación (inmutables; devuelven false, nunca NULL) -------------------

-- Anchos generados: al menos uno, todos ≥ 1, estrictamente crecientes y sin superar el ancho.
CREATE FUNCTION media_widths_valid(p_widths integer[], p_width integer) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT COALESCE(
    array_ndims(p_widths) = 1
    AND array_lower(p_widths, 1) = 1
    AND p_width IS NOT NULL
    AND NOT EXISTS (
      SELECT 1
      FROM generate_subscripts(p_widths, 1) AS i
      WHERE p_widths[i] IS NULL
         OR p_widths[i] < 1
         OR p_widths[i] > p_width
         OR (i > 1 AND p_widths[i] <= p_widths[i - 1])
    ),
    false)
$$;

-- Texto JSON no vacío.
CREATE FUNCTION json_is_text(p jsonb) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT COALESCE(jsonb_typeof(p) = 'string' AND (p #>> '{}') ~ '[^[:space:]]', false)
$$;

-- Número JSON mayor o igual que un mínimo (y entero si se pide).
CREATE FUNCTION json_is_number(p jsonb, p_min numeric, p_integer boolean) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(p) = 'number' THEN
      (p::text)::numeric >= p_min
      AND (NOT p_integer OR (p::text)::numeric = trunc((p::text)::numeric))
    ELSE false
  END
$$;

-- Datos de cámara (MediaRecord.camera): solo las claves del contrato, con su tipo.
CREATE FUNCTION media_camera_valid(c jsonb) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(c) = 'object' THEN COALESCE(
      NOT EXISTS (
        SELECT 1 FROM jsonb_object_keys(c) AS k
        WHERE k NOT IN ('make', 'model', 'lens', 'focalLength', 'aperture', 'exposure', 'iso'))
      AND (NOT c ? 'make' OR json_is_text(c -> 'make'))
      AND (NOT c ? 'model' OR json_is_text(c -> 'model'))
      AND (NOT c ? 'lens' OR json_is_text(c -> 'lens'))
      AND (NOT c ? 'focalLength' OR json_is_number(c -> 'focalLength', 0, false))
      AND (NOT c ? 'aperture' OR json_is_number(c -> 'aperture', 0, false))
      AND (NOT c ? 'exposure' OR (json_is_text(c -> 'exposure')
                                  AND (c ->> 'exposure') ~ '^[0-9]+(\.[0-9]+)?(/[0-9]+)?$'))
      AND (NOT c ? 'iso' OR json_is_number(c -> 'iso', 1, true)),
      false)
    ELSE false
  END
$$;

-- --- Taxonomía ----------------------------------------------------------------------------

-- categories.json: las cinco secciones de la revista.
CREATE TABLE category (
  id               text       PRIMARY KEY
                              CHECK (id IN ('viajes', 'fotografia', 'motor', 'experiencias', 'tecnologia')),
  slug             d_slug     NOT NULL UNIQUE,
  name             d_text     NOT NULL,
  description      d_text     NOT NULL,
  catalog_position d_position NOT NULL,
  CONSTRAINT category_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);

-- tags.json
CREATE TABLE tag (
  id               d_slug     PRIMARY KEY,
  slug             d_slug     NOT NULL UNIQUE,
  label            d_text     NOT NULL,
  catalog_position d_position NOT NULL,
  CONSTRAINT tag_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);

-- --- Imágenes -----------------------------------------------------------------------------

-- media.json (sin `original`, que va en media_original).
CREATE TABLE media (
  id                 d_slug      PRIMARY KEY,
  path               text        NOT NULL
                                 CHECK (path ~ '^[a-z0-9]+(-[a-z0-9]+)*/[a-z0-9]+(-[a-z0-9]+)*\.[a-f0-9]{8}$'),
  width              integer     NOT NULL CHECK (width >= 1),
  height             integer     NOT NULL CHECK (height >= 1),
  widths             integer[]   NOT NULL,
  alt                d_text      NOT NULL,
  title              d_text,
  caption            d_text,
  description        d_text,
  taken_at           d_date,
  -- location: presente si y solo si location_name no es NULL.
  location_name      d_text,
  location_region    d_text,
  location_country   d_country,
  -- location.point: ubicación pública de la foto, como máximo dos decimales (privacidad).
  location_lat       numeric(9, 6),
  location_lng       numeric(9, 6),
  -- camera: NULL = ausente; {} = presente sin datos.
  camera             jsonb,
  -- focal: obligatorio en el contrato (models/media.ts, validate.ts).
  focal_x            numeric     NOT NULL CHECK (focal_x BETWEEN 0 AND 1),
  focal_y            numeric     NOT NULL CHECK (focal_y BETWEEN 0 AND 1),
  placeholder        text        NOT NULL
                                 CHECK (placeholder ~ '^data:image/(webp|avif|png|jpeg);base64,[A-Za-z0-9+/]+=*$'
                                        AND length(placeholder) <= 4000),
  credit_author      d_text      NOT NULL,
  credit_source      text        NOT NULL CHECK (credit_source IN ('propia', 'wikimedia', 'unsplash', 'pexels')),
  credit_source_url  d_https_url UNIQUE,
  credit_license     text        NOT NULL
                                 CHECK (credit_license IN ('derechos-reservados', 'CC0-1.0', 'PDM-1.0', 'CC-BY-2.0',
                                                           'CC-BY-3.0', 'CC-BY-4.0', 'CC-BY-SA-2.0', 'CC-BY-SA-3.0',
                                                           'CC-BY-SA-4.0', 'Unsplash', 'Pexels')),
  credit_license_url d_https_url,
  demo               boolean     NOT NULL,
  -- Retirada (editorial/retired-media.ts): sigue en el catálogo, pero no admite usos nuevos.
  retired            boolean     NOT NULL DEFAULT false,
  catalog_position   d_position  NOT NULL,
  CONSTRAINT media_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT media_widths_check CHECK (media_widths_valid(widths, width)),
  CONSTRAINT media_location_check CHECK (
    location_name IS NOT NULL
    OR (location_region IS NULL AND location_country IS NULL AND location_lat IS NULL AND location_lng IS NULL)),
  CONSTRAINT media_location_point_pair_check CHECK ((location_lat IS NULL) = (location_lng IS NULL)),
  CONSTRAINT media_location_point_range_check CHECK (
    location_lat BETWEEN -90 AND 90 AND location_lng BETWEEN -180 AND 180),
  CONSTRAINT media_location_point_privacy_check CHECK (
    location_lat = round(location_lat, 2) AND location_lng = round(location_lng, 2)),
  CONSTRAINT media_camera_check CHECK (camera IS NULL OR media_camera_valid(camera)),
  -- Foto propia: sin URL de origen. Fuente externa: URL obligatoria y licencia libre.
  CONSTRAINT media_credit_own_check CHECK (credit_source <> 'propia' OR credit_source_url IS NULL),
  CONSTRAINT media_credit_external_check CHECK (
    credit_source = 'propia' OR (credit_source_url IS NOT NULL AND credit_license <> 'derechos-reservados'))
);

-- MediaRecord.original: datos privados del original. Nunca forman parte de una publicación.
CREATE TABLE media_original (
  media_id   d_slug  PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
  file       text    NOT NULL CHECK (file ~ '^[a-z0-9]+(-[a-z0-9]+)*\.[a-z0-9]+$'),
  format     text    NOT NULL CHECK (format IN ('jpeg', 'png', 'heic', 'heif', 'webp', 'avif', 'tiff')),
  width      integer NOT NULL CHECK (width >= 1),
  height     integer NOT NULL CHECK (height >= 1),
  bytes      bigint  NOT NULL CHECK (bytes >= 1),
  sha256     d_hex64 NOT NULL UNIQUE,
  url        d_https_url,
  raw_file   text    CHECK (raw_file ~ '^[a-z0-9]+(-[a-z0-9]+)*\.dng$'),
  raw_bytes  bigint  CHECK (raw_bytes >= 1),
  raw_sha256 d_hex64,
  CONSTRAINT media_original_raw_check CHECK (
    (raw_file IS NULL) = (raw_bytes IS NULL) AND (raw_file IS NULL) = (raw_sha256 IS NULL))
);

-- Usos de imágenes fuera del contenido (por ejemplo, las vistas previas del menú).
CREATE TABLE media_reserved_use (
  use_key     text   PRIMARY KEY CHECK (use_key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'),
  media_id    d_slug NOT NULL REFERENCES media (id) ON DELETE RESTRICT,
  description d_text NOT NULL
);
CREATE INDEX media_reserved_use_media_id_idx ON media_reserved_use (media_id);

-- --- Autoría ------------------------------------------------------------------------------

-- authors.json
CREATE TABLE author (
  id               d_slug     PRIMARY KEY,
  name             d_text     NOT NULL,
  role             d_text     NOT NULL,
  bio              d_text,
  avatar_media_id  d_slug     REFERENCES media (id) ON DELETE RESTRICT,
  catalog_position d_position NOT NULL,
  CONSTRAINT author_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX author_avatar_media_id_idx ON author (avatar_media_id);

-- --- Galerías -----------------------------------------------------------------------------

-- galleries.json
CREATE TABLE gallery (
  id               d_slug     PRIMARY KEY,
  slug             d_slug     NOT NULL UNIQUE,
  title            d_text     NOT NULL,
  description      d_text,
  layout           text       NOT NULL CHECK (layout IN ('grid', 'horizontal', 'diptych')),
  demo             boolean    NOT NULL,
  catalog_position d_position NOT NULL,
  CONSTRAINT gallery_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);

-- galleries.json → items[]: el orden de la serie.
CREATE TABLE gallery_item (
  gallery_id d_slug     NOT NULL REFERENCES gallery (id) ON DELETE CASCADE,
  position   d_position NOT NULL,
  media_id   d_slug     NOT NULL REFERENCES media (id) ON DELETE RESTRICT,
  caption    d_text,
  CONSTRAINT gallery_item_pkey PRIMARY KEY (gallery_id, position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX gallery_item_media_id_idx ON gallery_item (media_id);

-- --- Lugares e itinerarios ----------------------------------------------------------------

-- destinations.json
CREATE TABLE destination (
  id               d_slug        PRIMARY KEY,
  slug             d_slug        NOT NULL UNIQUE,
  name             d_text        NOT NULL,
  country          d_country     NOT NULL,
  region           d_text,
  lat              numeric(9, 6) NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng              numeric(9, 6) NOT NULL CHECK (lng BETWEEN -180 AND 180),
  elevation        integer       CHECK (elevation BETWEEN -500 AND 9000),
  summary          d_text        NOT NULL,
  cover_media_id   d_slug        REFERENCES media (id) ON DELETE RESTRICT,
  demo             boolean       NOT NULL,
  catalog_position d_position    NOT NULL,
  CONSTRAINT destination_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX destination_cover_media_id_idx ON destination (cover_media_id);

-- trips.json
CREATE TABLE trip (
  id               d_slug     PRIMARY KEY,
  slug             d_slug     NOT NULL UNIQUE,
  title            d_text     NOT NULL,
  summary          d_text     NOT NULL,
  cover_media_id   d_slug     REFERENCES media (id) ON DELETE RESTRICT,
  demo             boolean    NOT NULL,
  catalog_position d_position NOT NULL,
  CONSTRAINT trip_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX trip_cover_media_id_idx ON trip (cover_media_id);

-- trips.json → stops[]: «order» es la posición en la ruta, desde 1.
CREATE TABLE trip_stop (
  trip_id        d_slug     NOT NULL REFERENCES trip (id) ON DELETE CASCADE,
  stop_order     d_position NOT NULL,
  destination_id d_slug     NOT NULL REFERENCES destination (id) ON DELETE RESTRICT,
  note           d_text,
  distance_km    numeric    CHECK (distance_km >= 0),
  mode           text       CHECK (mode IN ('coche', 'moto', 'tren', 'barco', 'a-pie')),
  CONSTRAINT trip_stop_pkey PRIMARY KEY (trip_id, stop_order) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX trip_stop_destination_id_idx ON trip_stop (destination_id);

-- --- Proyectos ----------------------------------------------------------------------------

-- projects.json
CREATE TABLE project (
  id               d_slug      PRIMARY KEY,
  slug             d_slug      NOT NULL UNIQUE,
  name             d_text      NOT NULL,
  summary          d_text      NOT NULL,
  status           text        NOT NULL CHECK (status IN ('activo', 'pausado', 'archivado')),
  year             integer     NOT NULL CHECK (year BETWEEN 1990 AND 2100),
  repository_url   d_https_url,
  cover_media_id   d_slug      REFERENCES media (id) ON DELETE RESTRICT,
  demo             boolean     NOT NULL,
  catalog_position d_position  NOT NULL,
  CONSTRAINT project_catalog_position_key UNIQUE (catalog_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX project_cover_media_id_idx ON project (cover_media_id);

-- projects.json → stack[]: etiquetas, en su orden.
CREATE TABLE project_stack_item (
  project_id d_slug     NOT NULL REFERENCES project (id) ON DELETE CASCADE,
  position   d_position NOT NULL,
  tag_id     d_slug     NOT NULL REFERENCES tag (id) ON DELETE RESTRICT,
  CONSTRAINT project_stack_item_pkey PRIMARY KEY (project_id, position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX project_stack_item_tag_id_idx ON project_stack_item (tag_id);
