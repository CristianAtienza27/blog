#!/bin/bash
# Roles del CMS (fase 6.2.1). La imagen oficial de PostgreSQL ejecuta los *.sh de
# /docker-entrypoint-initdb.d una sola vez, como superusuario, al inicializar un volumen vacío.
# Las contraseñas las lee sql/cms-roles.psql de los secretos (CMS_SECRETS_DIR, /run/secrets).
# Sin -u: si la imagen ejecuta el script con «source» (sin permiso de ejecución), las opciones
# afectarían al resto de su entrypoint.
set -eo pipefail

psql -v ON_ERROR_STOP=1 -X \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v DBNAME="$POSTGRES_DB" \
  -f "$(dirname "${BASH_SOURCE[0]}")/sql/cms-roles.psql"
