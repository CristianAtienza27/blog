# syntax=docker/dockerfile:1
#
# media-worker: procesador de fotografías (prueba de concepto).
#
# - libvips compilado desde el código fuente (versión y checksum fijados), solo con los
#   formatos necesarios: HEIF/AVIF, JPEG, PNG, WebP, perfiles ICC (lcms) y EXIF.
# - libheif de Debian con libde265 (decodifica HEIC/HEVC), dav1d (decodifica AV1) y
#   aom (codifica AVIF). Sin x265: no se codifica HEIC.
# - sharp compilado contra ese libvips; no se usan sus binarios precompilados.
# - node_modules se instala dentro de la imagen (Linux) y nunca se comparte con Windows.

ARG NODE_IMAGE=node:26.13.0-trixie-slim@sha256:e4db4c914bf8ad241bd24804fc25d272d89bad97e68cdc8a1414862790a02c39

# --- 1. libvips ------------------------------------------------------------------
FROM ${NODE_IMAGE} AS vips-build

ARG VIPS_VERSION=8.18.7
ARG VIPS_SHA256=5baaead3b0bb20ffdb9e9ff09aa9fda08620923df77b63b436654cb5e0b3bf94

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    build-essential meson ninja-build pkg-config python3 ca-certificates curl xz-utils \
    libglib2.0-dev libexpat1-dev \
    libheif-dev libjpeg62-turbo-dev libspng-dev libwebp-dev \
    libexif-dev liblcms2-dev libhwy-dev \
  && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /tmp/vips.tar.xz \
    "https://github.com/libvips/libvips/releases/download/v${VIPS_VERSION}/vips-${VIPS_VERSION}.tar.xz" \
  && echo "${VIPS_SHA256}  /tmp/vips.tar.xz" | sha256sum -c - \
  && mkdir /tmp/vips && tar -xJf /tmp/vips.tar.xz -C /tmp/vips --strip-components=1

WORKDIR /tmp/vips

# Todo desactivado salvo lo que se activa explícitamente: el build falla si falta algo.
RUN meson setup build \
    --prefix=/usr/local --libdir=lib --buildtype=release --auto-features=disabled \
    -Dheif=enabled -Djpeg=enabled -Dspng=enabled -Dwebp=enabled \
    -Dlcms=enabled -Dexif=enabled -Dhighway=enabled -Dzlib=enabled \
    -Dmodules=disabled -Dintrospection=disabled -Dcplusplus=true \
    -Ddeprecated=false -Dexamples=false \
    -Dnsgif=false -Dppm=false -Danalyze=false -Dradiance=false \
  && meson compile -C build \
  && meson install -C build \
  && ldconfig

# --- 2. Dependencias de Node (sharp desde el código fuente) -----------------------
FROM vips-build AS deps

WORKDIR /app
COPY package.json package-lock.json ./
# Sin dependencias opcionales: los binarios precompilados de sharp (@img/*) no se instalan.
# sharp se compila después, de forma explícita, contra el libvips del sistema.
RUN npm ci --omit=optional \
  && cd node_modules/sharp \
  && SHARP_FORCE_GLOBAL_LIBVIPS=1 npm run build \
  && cd /app \
  && npm prune --omit=dev --omit=optional

# --- 3. Imagen final ---------------------------------------------------------------
FROM ${NODE_IMAGE} AS worker

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    libglib2.0-0t64 libexpat1 zlib1g \
    libheif1 libheif-plugin-libde265 libheif-plugin-dav1d libheif-plugin-aomenc \
    libjpeg62-turbo libspng0 libwebp7 libwebpmux3 libwebpdemux2 libsharpyuv0 \
    libexif12 liblcms2-2 libhwy1t64 \
    libheif-examples libimage-exiftool-perl \
  && rm -rf /var/lib/apt/lists/*

# Misma imagen base: se copia /usr/local/lib entero para conservar los enlaces simbólicos.
COPY --from=vips-build /usr/local/lib/ /usr/local/lib/
COPY --from=vips-build /usr/local/bin/vips /usr/local/bin/vipsheader /usr/local/bin/
RUN ldconfig

ENV VIPS_VERSION=8.18.7 \
    MEDIA_DATA=/data \
    NODE_ENV=production

WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src

USER node
ENTRYPOINT ["node"]
CMD ["src/check-codecs.mjs"]
