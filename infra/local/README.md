# Entorno local del CMS

PostgreSQL 18, la API del CMS y la web pública en modo instantáneas (fase 6.3.5) en Docker
Compose. Solo para desarrollo en este equipo: los puertos escuchan únicamente en `127.0.0.1`.
Diseño de la ejecución (redes, volúmenes, permisos, endurecimiento, límites, copias y producción):
[`infra/docs/ejecucion.md`](../docs/ejecucion.md).

## Primera vez

1. Copia `.env.example` como `.env` en esta carpeta. El `.env` está ignorado por Git.
2. Genera las contraseñas de la base de datos en `secrets/` (ver
   [`secrets/README.md`](secrets/README.md)): un archivo por login, ignorados por Git.
3. Desde la raíz del proyecto:

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml up --build -d
```

Al arrancar sobre un volumen vacío, PostgreSQL ejecuta el script de roles
(`postgres-init/`). Después, `migrate` aplica las migraciones y termina; la API solo arranca si
ha terminado bien. `volumenes` prepara las raíces de `instantaneas` y `estado` y termina; la web
arranca después, sin depender de la API (sin versión activa responde 503 hasta la primera
publicación).

## Roles y conexiones (fase 6.2.1)

Cada proceso se conecta con su propio login. Ninguno usa el superusuario, que solo inicializa
el volumen.

| Proceso                   | Login               | Rol del que es miembro | Puede                                                     |
| ------------------------- | ------------------- | ---------------------- | --------------------------------------------------------- |
| `migrate`                 | `cms_migrator`      | `cms_owner` (SET ROLE) | Migrar como propietario; sin `SET ROLE`, nada (NOINHERIT) |
| `api`                     | `cms_api`           | `cms_app`              | Lo que concede V3; no migra ni crea objetos               |
| `content-tool`            | `cms_tool`          | `cms_importer`         | Importar y exportar (V4)                                  |
| `security-tool`           | `cms_security_tool` | `cms_security_admin`   | Alta de administrador y recuperación (6.2.6)              |
| Retención de la auditoría | `cms_maintenance`   | `cms_audit_maintainer` | Fase 6.2 (purga controlada)                               |

- `cms_owner` es un rol sin login: es el propietario de la base de datos, del esquema y de todo
  lo que crean las migraciones.
- `PUBLIC` no puede conectarse, crear tablas temporales ni usar el esquema.
- La API y las herramientas no ejecutan Flyway: al arrancar comprueban que el esquema está en la
  versión de su código y, si no, se niegan a arrancar.

Para aplicar migraciones nuevas sin reiniciar el resto:

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm migrate
```

## Volúmenes ya inicializados (fases 6.2.3 y 6.4.5)

Los scripts de `postgres-init/` solo se ejecutan al inicializar un volumen vacío. V8 necesita el rol
`cms_enrollment_writer` (dueño de `complete_passkey_enrollment`) y V11, `cms_article_publisher`
(dueño de `publish_article` y `unpublish_article`): en un volumen nuevo los crea el
script de inicio; en uno ya inicializado, un administrador ejecuta **una vez**, antes de migrar,
el script idempotente de los roles dueños de funciones (operación administrativa excepcional, con
el superusuario por el socket local del contenedor, sin contraseñas en la línea de comandos):

```bash
docker compose -f infra/local/compose.yaml exec postgres sh -c 'psql -v ON_ERROR_STOP=1 -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /docker-entrypoint-initdb.d/sql/cms-function-owners.psql'
```

- Crea el rol si falta (sin login ni privilegios especiales) y reafirma sus atributos y la
  pertenencia de `cms_owner` (`INHERIT` y `SET`). Repetirlo no cambia nada.
- No crea logins ni toca los permisos de la base de datos o del esquema: es seguro en un volumen ya
  migrado (a diferencia del script de inicio completo).
- Si se olvida, `migrate` se detiene al empezar V8 (o V11) con un mensaje que remite aquí, y
  Flyway revierte la migración: el volumen queda como estaba.
- `cms_migrator` no recibe `CREATEROLE`: ni V8 ni V11 crean roles.

## Comprobar que no hay secretos fuera de su sitio (fase 6.2.8)

Desde la raíz, con la API compilada (`./mvnw package` en `api/`):

```bash
node infra/scripts/comprobar-secretos.mjs
```

Busca el valor real de cada archivo de `secrets/` (de este entorno y del de medición), de las
variables sensibles de los `.env` y de la credencial de prueba del entorno de medición en todos los
archivos del repositorio (también los no versionados y los generados: `api/target`, `dist`…) y
dentro del JAR de la API y de sus JAR anidados. Excluye los propios archivos fuente de los secretos,
`.git` y `node_modules`. Busca además patrones genéricos (claves privadas, tokens, asignaciones de
contraseñas) y separa los falsos positivos justificados, cada uno con su motivo. **Nunca imprime un
valor**: solo la fuente y el archivo donde aparece. Sale con `0` sin hallazgos y con `1` si un valor
real o un patrón sin clasificar aparece en algún sitio.

## Comprobar

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml ps
curl http://127.0.0.1:8080/actuator/health
```

`/actuator/health` debe responder `"status":"UP"` con el componente `db` en `UP`. Ningún otro
endpoint de gestión está expuesto. Las sondas (6.2.7.2): `/actuator/health/liveness` (la JVM está
viva; es el `HEALTHCHECK` del contenedor, y no depende de PostgreSQL) y
`/actuator/health/readiness` (puede atender peticiones: incluye PostgreSQL). Con PostgreSQL detenido,
la API sigue `healthy`, readiness da 503 y las peticiones que necesitan la base de datos responden
503 `unavailable` en unos 5 s.

La web (6.3.5): `curl http://localhost:4000/_salud/listo` (`UP` con una versión activa; 503 sin
ella). Su salud de contenedor es `/_salud/vida`. Integración completa en contenedores, en un
proyecto aislado (`cristian-atienza-cms-e2e`) que no toca este:

```bash
node infra/scripts/comprobar-integracion.mjs ../blog-bench/resultados/6.3.5-integracion.json
```

## Parar

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml stop      # para sin borrar nada
docker compose --env-file infra/local/.env -f infra/local/compose.yaml down      # conserva los datos
docker compose --env-file infra/local/.env -f infra/local/compose.yaml down -v   # BORRA el volumen de la 6.2, las instantáneas y el estado de la web (no el volumen anterior, que no se declara)
```

## Importar, exportar y comparar el contenido

El servicio `content-tool` (perfil `tools`) usa la misma imagen que la API, sin servidor web ni
puertos. Monta `src/content`, `retired-media.ts` y `navigation.ts` en solo lectura y escribe la
exportación en `CMS_EXPORT_DIR` (por defecto `../../../blog-export`, la carpeta hermana del
repositorio). Desde la raíz del proyecto, con `postgres` en marcha y la imagen construida:

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm content-tool --cms.tool=import
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm content-tool --cms.tool=export
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm content-tool --cms.tool=compare
```

Código de salida: `0` correcto (o sin cambios), `2` diferencias, `1` error. Repetir la
importación con la base de datos ya cargada no escribe nada; si el origen ha cambiado, se detiene
y lista las diferencias (no sincroniza). Detalles en
[`api/docs/importacion-exportacion.md`](../../api/docs/importacion-exportacion.md).

`CMS_EXPORT_DIR` debe estar fuera del repositorio: dentro del contenedor no se puede comprobar.
La carpeta debe estar vacía o contener una exportación anterior (marcador `.cms-export`).

**Instantáneas publicadas (fase 6.3.1, solo para pruebas):** `--cms.tool=generar-instantanea`
genera una versión inmutable del estado publicado en `--cms.snapshots-dir`, también fuera del
repositorio. El comando y el formato están en
[`api/docs/publicacion.md`](../../api/docs/publicacion.md).

**Publicar y volver atrás (fases 6.3.4 y 6.3.5):** el servicio `publication-tool` (perfil
`tools`): la imagen de la API con **su** login (`cms_api`), sin servidor web, con los volúmenes
`instantaneas` y `estado` (solo lectura) y la red interna de la web, endurecido como la API:

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=publicar
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=activar --cms.version=v3
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=purgar-instantaneas
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=purgar-instantaneas --cms.confirmar=true
```

Código de salida: `0` activada, sin cambios o purga; `2` rechazada o fallida; `1` error. La purga
sin `--cms.confirmar=true` solo simula. La auditoría registra el login de PostgreSQL y la máquina,
nunca un operador declarado. Lo mismo por HTTP: `POST /api/cms/publicaciones` y
`POST /api/cms/publicaciones/vN/activar` (ADMIN con passkey reciente).

## Herramienta de seguridad (fase 6.2.6)

El servicio `security-tool` (perfil `tools`) usa la misma imagen que la API, sin servidor web ni
puertos, conectado como `cms_security_tool` y **sin la clave HMAC de la API**. Crea el primer
administrador y hace la recuperación de emergencia. Requiere acceso a Docker en el equipo (en
producción, SSH al servidor): es un privilegio inherente a la recuperación de emergencia.

**La contraseña nunca va en un argumento ni en una variable de entorno:** se escribe en un archivo
de un solo uso, `infra/local/secrets/cms_admin_password` (ignorado por Git), que se monta solo en
esta ejecución (en `/run/cms-input/`, fuera de `/run/secrets/`, para que Spring no la cargue como
propiedad) y **se borra después**. Política: de 16 a 1024 caracteres, sin la parte local del correo,
no un único carácter repetido y fuera de la lista de contraseñas comunes. Desde la raíz del
proyecto, con `postgres` en marcha y la imagen construida:

```bash
node -e 'require("fs").writeFileSync("infra/local/secrets/cms_admin_password",require("crypto").randomBytes(24).toString("base64url"),{mode:0o600})'
```

(O escribirla a mano en ese archivo con un editor; un salto de línea final se ignora.) Para leerla
y guardarla en el gestor de contraseñas antes de borrar el archivo, ábrelo con un editor; no la
pegues en la terminal.

**Primer administrador** (justo antes del primer acceso: quien conozca la contraseña antes que el
administrador podría registrar su passkey en P1):

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm -e CMS_OPERATOR="$(whoami)@$(hostname)" security-tool --cms.tool=crear-admin --cms.usuario=<correo> --cms.contrasena-desde=/run/cms-input/cms_admin_password
```

**Recuperación de emergencia** (passkey y códigos perdidos): contraseña nueva y revocación de
passkeys, identificador WebAuthn, códigos, dispositivos y sesiones, en una única transacción. Puede
ejecutarse con la API en marcha: las operaciones en curso terminan antes y las siguientes se
rechazan ([sincronización H7](../../api/docs/seguridad.md#sincronización-con-la-api-h7)).

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm -e CMS_OPERATOR="$(whoami)@$(hostname)" security-tool --cms.tool=recuperacion-emergencia --cms.usuario=<correo> --cms.nueva-contrasena-desde=/run/cms-input/cms_admin_password
```

Después, en los dos casos:

```bash
rm infra/local/secrets/cms_admin_password
```

En PowerShell: `Remove-Item infra/local/secrets/cms_admin_password`, y
`-e CMS_OPERATOR="$env:USERNAME@$env:COMPUTERNAME"` en lugar de la variable de bash.

- **Código de salida:** `0` correcto; `2` argumentos o contraseña no válidos; `3` el correo ya
  existe (alta) o no existe (recuperación); `1` error inesperado. En los errores no se cambia nada.
- **Salida:** el id del usuario y los recuentos; nunca la contraseña ni el correo.
- **`CMS_OPERATOR`** queda en la auditoría como operador **declarado**: no es una identidad
  verificada. Sin él, «no indicado».
- **Si la cuenta está recibiendo carga sostenida** (por ejemplo, un atacante con la sesión robada
  repitiendo operaciones), para antes la API (`docker compose … stop api`), ejecuta la recuperación
  y vuelve a arrancarla. Con la API en marcha la recuperación espera a que terminen las operaciones en
  curso sobre la cuenta: medido, 13,4 s con 20 operaciones simultáneas frente a 5,5 s sin carga
  (6.2.7.1), y si no consigue el bloqueo en 30 s sale con `1` sin cambiar nada (se puede repetir).
- **Tras la recuperación:** el siguiente acceso es con la contraseña nueva y solo permite registrar
  una passkey (P1). Los contadores de intentos no se reinician: si había un bloqueo por fallos
  anteriores, hay que esperar a que caduque (15 min a 1 h).
- **Sin el archivo**, `docker compose run` avisa de que el secreto no existe, **Docker crea en su
  lugar una carpeta vacía** `infra/local/secrets/cms_admin_password` y la herramienta sale con `2`
  sin cambiar nada. Hay que borrar esa carpeta (`rmdir infra/local/secrets/cms_admin_password`)
  antes de crear el archivo. El resto de servicios no lo necesitan.
- **Git Bash en Windows** convierte los argumentos que parecen rutas (`/run/cms-input/…` pasaría a
  `C:/Program Files/Git/run/…` y la herramienta no encontraría el archivo, salida `2`): ejecuta los
  comandos con `MSYS_NO_PATHCONV=1` delante, o desde PowerShell.

## Servicios

| Servicio                            | Imagen                                      | Puerto en el equipo                        |
| ----------------------------------- | ------------------------------------------- | ------------------------------------------ |
| `postgres`                          | `postgres:18.6-trixie` (digest fijado)      | `127.0.0.1:5432`                           |
| `migrate` (se ejecuta y termina)    | `cristian-atienza/cms-api:local`            | ninguno                                    |
| `volumenes` (se ejecuta y termina)  | `cristian-atienza/web:local`                | ninguno (sin red)                          |
| `api`                               | `cristian-atienza/cms-api:local` (`api/`)   | `127.0.0.1:8080`                           |
| `web`                               | `cristian-atienza/web:local` (`infra/web/`) | `127.0.0.1:4000` (el interno, 4100, nunca) |
| `content-tool` (perfil `tools`)     | `cristian-atienza/cms-api:local`            | ninguno                                    |
| `publication-tool` (perfil `tools`) | `cristian-atienza/cms-api:local`            | ninguno                                    |
| `security-tool` (perfil `tools`)    | `cristian-atienza/cms-api:local`            | ninguno                                    |

Los datos de PostgreSQL viven en el volumen `cristian-atienza-cms_postgres-data-v62` (fase 6.2).
El volumen anterior, `cristian-atienza-cms_postgres-data`, no se declara en este archivo para
que ningún comando de Compose pueda borrarlo: se conserva como respaldo hasta verificar el nuevo.
Las instantáneas y el estado de la web viven en `cristian-atienza-cms_instantaneas` (la API
escribe, la web solo lee) y `cristian-atienza-cms_estado` (la web escribe, la API solo lee).
