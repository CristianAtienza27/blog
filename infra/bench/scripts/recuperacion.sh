#!/usr/bin/env bash
# Pruebas de límites y recuperación (fase 6.1.4). SOLO sobre el proyecto cms-bench.
#
#   bash infra/bench/scripts/recuperacion.sh <prueba> <carpeta de resultados>
#
# Pruebas:
#   api-oom-cgroup   API con límite por debajo de su pico, bajo carga (OOMKill del kernel)
#   api-oom-heap     API con heap mínimo, bajo carga (OutOfMemoryError + ExitOnOutOfMemoryError)
#   api-kill         SIGKILL a la JVM bajo carga
#   pg-kill-backend  SIGKILL a un proceso de conexión de PostgreSQL bajo carga (lo que haría el OOM killer)
#   pg-kill-import   SIGKILL a PostgreSQL a mitad de una importación, en un volumen de pruebas vacío
#   ssr-kill         SIGKILL a Node bajo carga
#   ssr-oom          SSR con límite por debajo de su pico, bajo carga de renderizado
#
# Los fallos se provocan con SIGKILL desde el espacio de procesos del host (como el OOM killer),
# no con `docker kill`, que Docker trata como una parada manual y no aplica la política de reinicio.
# Todos los servicios se arrancan con restart: unless-stopped (BENCH_RESTART), como en producción.
set -euo pipefail

TEST=${1:?prueba}
OUT=${2:?carpeta de resultados}
REPO=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$REPO"
mkdir -p "$OUT"
export BENCH_OUT="$OUT" BENCH_IMAGES="${BENCH_IMAGES:-$REPO/../blog-bench/imagenes-prueba}" BENCH_RESTART=unless-stopped
export MSYS_NO_PATHCONV=1
C=(docker compose -f infra/bench/compose.yaml --profile tools)
# Consultas dentro del contenedor de PostgreSQL con un login de permisos mínimos (cms_tool), con la
# contraseña leída del secreto montado allí: nunca en argumentos.
tool_psql() { docker exec "$1" sh -c "PGPASSWORD=\"\$(cat /run/secrets/cms_tool_password)\" psql -h localhost -U cms_tool -d cms -Atc \"$2\""; }
PG_IMAGE=postgres:18.6-trixie@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
LOG="$OUT/$TEST.log"

node infra/bench/scripts/bench.mjs comprobar >> "$LOG"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
cid() { "${C[@]}" ps -q "$1"; }
state() { docker inspect -f '{{.State.Status}} reinicios={{.RestartCount}} oom={{.State.OOMKilled}} salida={{.State.ExitCode}}' "$(cid "$1")"; }
# SIGKILL al proceso indicado, desde el espacio de procesos del host.
hostkill() { docker run --rm --pid=host --entrypoint sh "$PG_IMAGE" -c "kill -9 $1"; }
wait_http() { # espera hasta que la URL responda 200 y devuelve los segundos
  local url=$1 start=$SECONDS
  until docker run --rm --network cms-bench_default --entrypoint bash "$PG_IMAGE" -c "exec 3<>/dev/tcp/${url%%/*}/${2:-80} && printf 'GET /${url#*/} HTTP/1.0\r\nHost: ${3:-x}\r\n\r\n' >&3 && head -1 <&3" 2>/dev/null | grep -q ' 200'; do
    (( SECONDS - start > 180 )) && { echo "sin respuesta"; return 1; }
    sleep 1
  done
  echo $(( SECONDS - start ))
}
api_up() { wait_http "api/actuator/health" 8080 api; }
ssr_up() { wait_http "ssr/" 4000 ssr; }
probe() { # sonda de 1 petición/s + carga, en segundo plano (registra cada petición)
  local name=$1 vus=$2 duration=$3
  "${C[@]}" run -d --name "$name" -e DURATION="$duration" -e CARGA_VUS="$vus" k6 run --quiet "/scripts/${4:-actuator-pausa.js}" > /dev/null
}
collect() { docker wait "$1" > /dev/null; docker logs "$1" > "$OUT/$1.log" 2>&1; docker rm "$1" > /dev/null; }
summary_events() { # resumen de la sonda: peticiones, errores y el intervalo sin servicio
  node -e '
    const lines = require("fs").readFileSync(process.argv[1], "utf8").split("\n").map((l) => l.match(/EV (\{.*\})/)).filter(Boolean).map((m) => JSON.parse(m[1].replaceAll("\\\"", "\"")));
    const probes = lines.filter((e) => e.k === "sonda").sort((a, b) => a.t - b.t);
    const bad = probes.filter((e) => e.http !== 200);
    const load = lines.filter((e) => e.k === "carga");
    const out = { sondas: probes.length, sondasFallidas: bad.length,
      sinServicio: bad.length ? { desde: bad[0].t.toFixed(1), hasta: bad.at(-1).t.toFixed(1) } : null,
      carga: { peticiones: load.length, fallidas: load.filter((e) => e.http !== 200).length } };
    console.log(JSON.stringify(out));' "$1"
}

case "$TEST" in
  api-oom-cgroup | api-oom-heap | api-kill)
    if [[ $TEST == api-oom-cgroup ]]; then export API_MEM=${API_MEM_TEST:-200m}; fi
    if [[ $TEST == api-oom-heap ]]; then export API_JAVA_OPTS="-Xmx${API_HEAP_TEST:-16m} -XX:+ExitOnOutOfMemoryError"; fi
    "${C[@]}" up -d --wait --no-build postgres api ssr >> "$LOG" 2>&1
    log "API arrancada en $(api_up) s: $(state api) · límite ${API_MEM:-640m} · ${API_JAVA_OPTS:-opciones de la imagen}"
    probe rec-k6 20 150s
    if [[ $TEST == api-kill ]]; then
      sleep 30
      pid=$(docker inspect -f '{{.State.Pid}}' "$(cid api)")
      log "SIGKILL a la JVM (pid $pid en el host)"
      hostkill "$pid"
      log "API de nuevo UP en $(api_up) s: $(state api)"
    else
      sleep 120
      log "Tras 120 s de carga: $(state api)"
    fi
    collect rec-k6
    log "Sonda: $(summary_events "$OUT/rec-k6.log")"
    docker logs "$(cid api)" 2>&1 | grep -iE "OutOfMemory|Terminating|Started CmsApplication" | tail -5 >> "$LOG" || true
    # Restaurar la configuración normal y comprobar que la API vuelve a estar bien.
    unset API_MEM API_JAVA_OPTS
    "${C[@]}" up -d --wait --no-build api >> "$LOG" 2>&1
    log "Restaurada (límite normal): UP en $(api_up) s: $(state api)"
    ;;

  pg-kill-backend)
    cp "$(ls -d "$REPO"/../blog-bench/resultados/*vps1-pgbench | tail -1)/articulo.sql" "$OUT/"
    "${C[@]}" up -d --wait --no-build postgres api >> "$LOG" 2>&1
    probe rec-k6 10 120s
    "${C[@]}" run -d --name rec-pgbench pgbench -n -c 5 -j 2 -T 100 -R 50 -f /out/articulo.sql > /dev/null
    sleep 30
    pg=$(cid postgres)
    backend=$(tool_psql "$pg" "SELECT pid FROM pg_stat_activity WHERE backend_type='client backend' AND application_name='pgbench' LIMIT 1")
    # El pid de la conexión es el del espacio del contenedor: se mata desde dentro (no es el PID 1).
    log "SIGKILL a la conexión de PostgreSQL $backend (pgbench)"
    docker exec "$pg" sh -c "kill -9 $backend"
    sleep 5
    log "Estado: $(state postgres)"
    docker logs "$pg" 2>&1 | grep -E "terminated by signal 9|all server processes terminated|database system was|redo|ready to accept" | tail -8 >> "$LOG"
    collect rec-pgbench
    grep -E "failed|aborted|tps" "$OUT/rec-pgbench.log" | head -5 >> "$LOG" || true
    collect rec-k6
    log "Sonda API: $(summary_events "$OUT/rec-k6.log")"
    ;;

  pg-kill-import)
    export BENCH_DB=${BENCH_DB_TEST:-fallo-importacion}
    # BENCH_CONTENT lo usa Compose para elegir la carpeta montada en /content (solo lectura).
    export BENCH_CONTENT=${BENCH_CONTENT:-../../../blog-bench/contenido-x50}
    "${C[@]}" up -d --wait --no-build postgres >> "$LOG" 2>&1
    log "Volumen de pruebas vacío: cms-bench_$BENCH_DB"
    "${C[@]}" run -d --name rec-import content-tool --cms.tool=import --cms.content-dir=/content > /dev/null
    # Espera a que la importación esté escribiendo (filas en article_revision en la transacción).
    pg=$(cid postgres)
    for _ in $(seq 1 120); do
      # Sin permisos de superusuario, pg_stat_activity no muestra la consulta de otros logins:
      # basta con que haya una conexión activa de cms_tool (la importación).
      n=$(tool_psql "$pg" "SELECT count(*) FROM pg_stat_activity WHERE state='active' AND usename='cms_tool' AND pid <> pg_backend_pid()" 2>/dev/null || echo 0)
      [[ $n -gt 0 ]] && break
      sleep 0.5
    done
    sleep 2
    pid=$(docker inspect -f '{{.State.Pid}}' "$pg")
    log "SIGKILL a PostgreSQL (postmaster, pid $pid en el host) a mitad de la importación"
    hostkill "$pid"
    collect rec-import
    grep -E "Error|Importaci" "$OUT/rec-import.log" | head -3 >> "$LOG" || true
    until docker exec "$(cid postgres)" pg_isready -d cms > /dev/null 2>&1; do sleep 1; done
    log "PostgreSQL de nuevo listo: $(state postgres)"
    docker logs "$(cid postgres)" 2>&1 | grep -E "database system was|redo (starts|done)|ready to accept" | tail -5 >> "$LOG"
    log "Filas tras la recuperación (la importación es una sola transacción):"
    tool_psql "$(cid postgres)" "SELECT 'article='||count(*) FROM article UNION ALL SELECT 'article_revision='||count(*) FROM article_revision UNION ALL SELECT 'media='||count(*) FROM media UNION ALL SELECT 'tag='||count(*) FROM tag" | tee -a "$LOG"
    # Verificación administrativa EXCEPCIONAL: pg_amcheck necesita la extensión amcheck y privilegios
    # que solo tiene el superusuario. Solo en este entorno de pruebas y solo para esta comprobación.
    log "pg_amcheck (índices y tablas; verificación administrativa con el superusuario):"
    docker exec "$(cid postgres)" sh -c 'pg_amcheck -U "$POSTGRES_USER" -d cms --install-missing --heapallindexed --progress 2>&1 | tail -2; echo salida=$?' | tee -a "$LOG"
    log "Nueva importación completa y comparación:"
    "${C[@]}" run --rm content-tool --cms.tool=import --cms.content-dir=/content 2>&1 | grep -E "Importaci|Sin cambios|Error|  article " | tee -a "$LOG"
    rm -rf "$OUT/export" && mkdir -p "$OUT/export"
    BENCH_EXPORT="$OUT/export" "${C[@]}" run --rm content-tool --cms.tool=export 2>&1 | grep -E "Exportaci|Error" | tee -a "$LOG"
    BENCH_EXPORT="$OUT/export" "${C[@]}" run --rm content-tool --cms.tool=compare --cms.content-dir=/content 2>&1 | grep -E "Sin diferencias|diferencias|Error" | tee -a "$LOG"
    ;;

  ssr-kill | ssr-oom)
    cp "$(ls -d "$REPO"/../blog-bench/resultados/*vps1-ssr-b-404 | tail -1)/rutas.json" "$OUT/"
    if [[ $TEST == ssr-oom ]]; then export SSR_MEM=${SSR_MEM_TEST:-112m}; fi
    "${C[@]}" up -d --wait --no-build ssr >> "$LOG" 2>&1
    log "SSR arrancado en $(ssr_up) s: $(state ssr) · límite ${SSR_MEM:-384m}"
    "${C[@]}" run -d --name rec-k6-ssr -e MODE=dinamica -e VUS=20 -e DURATION=150s -e NAME=rec-ssr k6 run --quiet --no-thresholds /scripts/ssr.js > /dev/null
    if [[ $TEST == ssr-kill ]]; then
      sleep 30
      pid=$(docker inspect -f '{{.State.Pid}}' "$(cid ssr)")
      log "SIGKILL a Node (pid $pid en el host)"
      hostkill "$pid"
      log "SSR de nuevo sirviendo en $(ssr_up) s: $(state ssr)"
    else
      sleep 120
      log "Tras 120 s de carga: $(state ssr)"
    fi
    collect rec-k6-ssr
    grep -oE '"peticiones":[0-9]+|"errores":[0-9]+|"timeouts":[0-9]+' "$OUT/rec-k6-ssr.log" | tr '\n' ' ' | tee -a "$LOG" || true; echo | tee -a "$LOG"
    unset SSR_MEM
    "${C[@]}" up -d --wait --no-build ssr >> "$LOG" 2>&1
    log "Restaurado (límite normal): sirviendo en $(ssr_up) s: $(state ssr)"
    ok=0
    for route in $(node -e 'console.log(Object.keys(require("./dist/cristian-atienza/prerendered-routes.json").routes).join(" "))'); do
      code=$(docker run --rm --network cms-bench_default --entrypoint bash "$PG_IMAGE" -c "exec 3<>/dev/tcp/ssr/4000 && printf 'GET $route HTTP/1.0\r\nHost: ssr\r\n\r\n' >&3 && head -1 <&3" | awk '{print $2}')
      [[ $code == 200 ]] && ok=$((ok + 1)) || log "  $route → $code"
    done
    log "Rutas prerenderizadas con 200 tras la recuperación: $ok/20"
    ;;

  *) echo "prueba desconocida: $TEST" >&2; exit 1 ;;
esac
log "FIN $TEST"
