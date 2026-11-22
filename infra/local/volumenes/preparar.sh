#!/bin/sh
# Prepara los directorios RAÍZ de los volúmenes compartidos de la publicación (fase 6.3.5).
#
# Lo ejecuta el servicio «volumenes» de infra/local/compose.yaml como root, sin red y solo con la
# capacidad CHOWN, antes de arrancar la API y la web; termina enseguida.
#
#   instantaneas  999:999   (cms, la API: escribe vN/; la web lo monta en solo lectura)
#   estado        1000:1000 (node, la web: escribe activa.json y control.json; la API, solo lectura)
#
# Idempotente y NO recursivo: solo mira y, si hace falta, corrige el propietario y el modo (0755)
# del directorio raíz. Nunca toca su contenido: las instantáneas ya generadas (0644/0755, de la
# 6.3.1) y el estado de la web conservan sus permisos. Si todo está bien, no cambia nada.
set -eu

prepare() {
  dir=$1
  owner=$2
  current=$(stat -c '%u:%g %a' "$dir")
  if [ "$current" = "$owner 755" ]; then
    echo "$dir: correcto ($current)"
    return
  fi
  # chmod antes del chown final: mientras el dueño es root, chmod no necesita CAP_FOWNER.
  chown 0:0 "$dir"
  chmod 0755 "$dir"
  chown "$owner" "$dir"
  echo "$dir: $current -> $owner 755"
}

prepare /volumenes/instantaneas 999:999
prepare /volumenes/estado 1000:1000
