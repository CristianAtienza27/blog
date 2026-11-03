# Solo para pruebas: crea una HEIC (HEVC) sintética para verificar la decodificación
# antes de usar fotos reales. Es el único contenedor con x265; el media-worker no lo lleva.
ARG NODE_IMAGE=node:26.13.0-trixie-slim@sha256:e4db4c914bf8ad241bd24804fc25d272d89bad97e68cdc8a1414862790a02c39
FROM ${NODE_IMAGE}

RUN apt-get update \
  && apt-get install -y --no-install-recommends libheif-examples libheif-plugin-x265 libimage-exiftool-perl \
  && rm -rf /var/lib/apt/lists/*

USER node
WORKDIR /data/sintetico
ENTRYPOINT ["sh", "-c"]
CMD ["heif-enc -q 90 -o sintetico-hevc.heic sintetico-p3.png && exiftool -q -overwrite_original -GPSLatitude=40.416775 -GPSLatitudeRef=N -GPSLongitude=3.70379 -GPSLongitudeRef=W -DateTimeOriginal='2026:05:12 18:30:00' sintetico-hevc.heic && echo 'HEIC de prueba creada: sintetico-hevc.heic'"]
