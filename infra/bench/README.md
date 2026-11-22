# Mediciones de capacidad (fase 6.1.4)

Entorno para medir CPU, memoria y comportamiento bajo carga del stack (PostgreSQL, API, SSR y
procesamiento de imágenes) con límites que simulan un VPS. Los resultados y las conclusiones
están en [`informe-6.1.4.md`](informe-6.1.4.md); los de la fase 6.2.7 (autenticación, tiempos,
sondas y recolector), en [`informe-6.2.7.md`](informe-6.2.7.md).

> **Alcance.** En este equipo son mediciones locales en Docker Desktop (WSL2) con límites
> simulados, no rendimiento garantizado en OVHcloud. La validación definitiva del VPS es de la
> fase 6.9, que puede reutilizar estos mismos scripts en el servidor real.

## Protecciones

- **Credenciales mínimas (6.2.2):** cada proceso usa su propio login, con la contraseña en un
  secreto; el superusuario solo inicializa el volumen (mismo script de roles que `infra/local`).

  | Proceso                     | Login                                                            |
  | --------------------------- | ---------------------------------------------------------------- |
  | `migrate`                   | `cms_migrator` (+ `SET ROLE cms_owner`)                          |
  | `api`, `pgbench`            | `cms_api` (`pgbench` solo lee, como la API)                      |
  | `content-tool`              | `cms_tool`                                                       |
  | `recuperacion.sh` (conteos) | `cms_tool`                                                       |
  | `pg_amcheck`                | superusuario: verificación administrativa excepcional, solo aquí |

  Desde la 6.2.2 el volumen por defecto es `cms-bench_v62`; los anteriores se conservan sin usarse.

  > **`cms-bench_v62` no sirve para la 6.2.3.** Se migró durante la 6.2.2 con una V7 provisional
  > (antes de sus ajustes finales) y su script de inicio no creó `cms_enrollment_writer`, que V8
  > necesita. Mientras la imagen `cristian-atienza/cms-api:local` no se reconstruya, nada cambia; con
  > la imagen nueva, `migrate` fallará en ese volumen por la diferencia de checksum de la versión 7
  > (Flyway valida antes de aplicar nada: el volumen no se modifica). Para medir, usar un volumen
  > nuevo (`BENCH_DB=v623 node infra/bench/scripts/bench.mjs preparar`), que ya se inicializa con el
  > rol y con la clave HMAC de la API (`secrets/cms_api_hmac_key`), y conservar `cms-bench_v62`
  > hasta que se apruebe borrarlo. **No usar `flyway repair`**: aceptaría el checksum nuevo sin
  > aplicar los permisos nuevos.

- **Proyecto Compose separado** (`cms-bench`): sus volúmenes son `cms-bench_*`. `bench.mjs`
  comprueba antes de cada ejecución que ningún servicio monta el volumen real
  (`cristian-atienza-cms_postgres-data`) ni un volumen externo.
- **Sin puertos** en el equipo: la carga se genera dentro de la red del proyecto.
- **Resultados fuera del repositorio**, en `../blog-bench/` (`BENCH_ROOT`). `bench.mjs` se niega
  a escribir dentro del repositorio.
- **Imágenes:** el servicio `media` solo monta `../blog-bench/imagenes-prueba/` (copias de
  prueba, en solo lectura) y el script de medición. No monta `media-src/`, `public/media/` ni
  `src/content/`. `bench.mjs` verifica los montajes del contenedor antes de procesar, y
  `media/bench.mjs` aborta si una ruta de salida no está bajo `/tmp/bench-*`.
- **Sin swap por contenedor** (`memswap_limit = mem_limit`), como un VPS sin swap. Sin esto,
  Docker permitiría otro tanto de swap y la máquina virtual de Docker Desktop tiene 2 GiB.
- **Corte automático de k6:** más de un 5 % de errores o un p95 por encima de 3 s.

## Requisitos

- Docker Desktop (o Docker Engine en Linux) con cgroup v2.
- Node 26 en el equipo (orquestador).
- Las contraseñas de este entorno en `infra/bench/secrets/` (ver su README): independientes de las
  de `infra/local`. No necesita `infra/local/.env`.
- Las imágenes `cristian-atienza/cms-api:local` (`docker compose … build api` en `infra/local`) y
  `cristian-atienza/media-worker:poc` (`npm run media:build`), y el build de la web
  (`npm run build`).

## Envolventes simuladas

| Variable                         | VPS-1 (2 vCores, 4 GB)   | VPS-2 (4 vCores, 8 GB)   |
| -------------------------------- | ------------------------ | ------------------------ |
| `SUT_CPUS` (servicios medidos)   | `0-1`                    | `0-3`                    |
| `LOAD_CPUS` (generadores)        | `8-9`                    | `8-9`                    |
| `PG_MEM` / `API_MEM` / `SSR_MEM` | `448m` / `640m` / `384m` | `768m` / `768m` / `512m` |
| `MEDIA_MEM`                      | `1024m`                  | `2048m`                  |

Las CPU se fijan con `cpuset`: los servicios medidos compiten entre sí como en un VPS, y los
generadores de carga y Windows usan otras CPU.

## Comandos (desde la raíz del proyecto)

```bash
node infra/bench/scripts/bench.mjs comprobar            # protecciones
node infra/bench/scripts/bench.mjs preparar             # postgres de pruebas + importación del contenido
docker compose -f infra/bench/compose.yaml up -d --wait api ssr
```

(Para llamar a `docker compose` directamente hay que definir `BENCH_OUT` y `BENCH_IMAGES`.)

| Medición                        | Comando                                                                                                     |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Reposo                          | `bench.mjs reposo --segundos 600 --servicios postgres,api,ssr`                                              |
| Health con PostgreSQL pausado   | `bench.mjs pausa --segundos 60 [--clientes 10]`                                                             |
| PostgreSQL (consultas reales)   | `bench.mjs pgbench --clientes 1,2,5,10 --duracion 60 --servicios postgres`                                  |
| API (`/actuator/health`)        | `bench.mjs k6 --script api-health.js --niveles 1,5,10,20,40 --servicios postgres,api`                       |
| SSR A: prerenderizadas          | `bench.mjs k6 --script ssr.js --modo prerender --niveles 1,5,10,20,40 --servicios ssr`                      |
| SSR B: renderizado (página 404) | `bench.mjs k6 --script ssr.js --modo dinamica --niveles 1,2,5,10,20 --servicios ssr`                        |
| SSR B2: artículos empaquetados  | igual que B con `--modo articulos`, contra la variante temporal (ver el informe)                            |
| Imágenes                        | `bench.mjs media --modo derive\|fases --imagenes zahara-de-la-sierra.heic [--concurrencia N] [--mem 1024m]` |
| JVM bajo límites                | `bench.mjs jvm-limites`                                                                                     |
| Carga combinada                 | `bench.mjs combinada --rate 20 --api-rate 5 --pgbench 5 --imagen zahara-de-la-sierra.heic --duracion 180`   |

### Autenticación (fase 6.2.7)

Sobre un volumen propio (`BENCH_DB=v627`). Los usuarios de prueba los crea la herramienta de
seguridad real (`crear-admin`, servicio `security-tool`, como `cms_security_tool`). Su contraseña de
prueba (decisión D5) está en `../blog-bench/credenciales/cms_admin_password` (`BENCH_CRED`, fuera
del repositorio), y la montan en solo lectura `security-tool` (fuera de `/run/secrets/`) y k6
(`/cred`). Nunca aparece en la consola ni en los resultados.

| Medición                                | Comando                                                                                         |
| --------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Usuarios de prueba                      | `bench.mjs admin [--email a@b]` (por defecto, `bench-login@example.test`, siempre en P1)        |
| A: un Argon2id (fuera de Spring)        | `bench.mjs argon2 --clientes 1,2,4 --niveles 1,2,4,8` (`ARGON2_JAVA_OPTS` para otro recolector) |
| B: inicio de sesión                     | `bench.mjs k6 --script login.js --niveles 20 --servicios postgres,api`                          |
| C: cadena de seguridad y sesión         | `bench.mjs k6 --script sesion.js --modo me                                                      | csrf                            | health --niveles 1,10,40` |
| Filtro del sello H7 (consulta sola)     | `bench.mjs pgbench --sql sello.sql --clientes 1,10`                                             |
| D: passkeys (F y step-up)               | `API_IP_FAILURES=1000000 bench.mjs k6 --script passkey.js --modo f                              | stepup --email <usuario nuevo>` |
| Memoria de la JVM (NMT y heap)          | `bench.mjs nmt` (la API con `-XX:NativeMemoryTracking=summary` en `API_JAVA_OPTS`)              |
| Fallos y reinicios (6.2.7.4)            | `node infra/bench/scripts/fallos.mjs R1                                                         | R3                              | R4                        | R5  | R6a | R6b | R7  | R8  | R9` |
| Estado de autenticación vacío (6.2.7.3) | `bench.mjs reset` (sesiones, contadores, fallos por sesión y challenges, como `cms_api`)        |
| Pausas del recolector (6.2.7.3)         | `bench.mjs gc` (la API con `-Xlog:gc:stdout` en `API_JAVA_OPTS`)                                |
| Elección del recolector (6.2.7.3)       | `node infra/bench/scripts/gc-decision.mjs <registro de las rondas>` (regla aprobada)            |
| Crecimiento de `spring_session`         | `bench.mjs sesiones --clientes 50000` (sesiones caducadas sintéticas, como `cms_api`)           |

- **Passkeys:** cada ejecución necesita un usuario nuevo (la clave privada del autenticador de k6,
  `k6/auth.js`, solo vive en esa ejecución). `API_IP_FAILURES` eleva el límite por IP **solo en
  este entorno** (D6): todas las peticiones de k6 llegan desde la misma IP y las opciones WebAuthn
  cuentan en ese ámbito. Por defecto es el valor real (20).
- **Cookies:** las de la API son `__Host-` y `Secure`; k6 no las envía por HTTP, así que `auth.js`
  las gestiona a mano (como `ApiClient` en los tests de la API).
- **NMT y jcmd:** la imagen de la API es un JRE sin `jcmd`; `bench.mjs nmt` lo ejecuta desde la
  imagen de JDK del Dockerfile en el espacio de procesos del contenedor de la API.
- **`pausa`:** desde la 6.2.2 `/actuator/metrics` exige sesión; el estado del pool sale de las
  líneas de Hikari del registro de la API (`api-hikari.log`).

Contenido para el crecimiento (fuera del repositorio) y su importación en la base de pruebas:

```bash
node infra/bench/scripts/growth.mjs 10 ../blog-bench/contenido-x10
BENCH_CONTENT=../../../blog-bench/contenido-x10 node infra/bench/scripts/bench.mjs preparar
```

## Archivos

| Archivo               | Uso                                                                                   |
| --------------------- | ------------------------------------------------------------------------------------- |
| `compose.yaml`        | Proyecto `cms-bench`: servicios medidos, herramientas (perfil `tools`), límites       |
| `scripts/bench.mjs`   | Orquestador: protecciones, escenarios, muestreo y resultados                          |
| `scripts/sampler.mjs` | Muestreo del cgroup v2 de cada contenedor (memoria, pico, anónima/caché, CPU, OOM)    |
| `scripts/growth.mjs`  | Contenido sintético ×N fuera del repositorio                                          |
| `k6/*.js`             | Escenarios de carga (API, SSR, pausa de PostgreSQL, carga combinada)                  |
| `media/bench.mjs`     | Procesamiento con el `derive()` real del media-worker, sobre copias, salida en `/tmp` |

Cada ejecución deja en `../blog-bench/resultados/<fecha>-<nombre>/`: `meta.json` (límites,
CPU y versión), el resumen de la carga, `muestras-*.csv` (una fila por muestra) y
`muestras-*.json` (máximos, medias, p95, pico del kernel y eventos OOM).
