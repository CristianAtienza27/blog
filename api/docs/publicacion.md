# Publicación del contenido con instantáneas (fase 6.3)

La web pública dejará de llevar el contenido dentro del build: servirá **instantáneas versionadas
e inmutables** que genera la API desde PostgreSQL, validadas por la propia web antes de activarlas.
Este documento crece con cada subfase.

| Subfase | Estado                                                  |
| ------- | ------------------------------------------------------- |
| 6.3.1   | Hecha: contrato de instantánea y generador (API)        |
| 6.3.2   | Hecha: lectura de instantáneas en la web (build aparte) |
| 6.3.3   | Hecha: puerta, activación y caché (web)                 |
| 6.3.4   | Hecha: servicio de publicación (API, V10) y fencing     |
| 6.3.5   | Hecha: integración local y prueba de extremo a extremo  |
| 6.4     | Hecha: publicación de artículos desde el CMS (V11)      |

## Formato de una instantánea (6.3.1)

```
<carpeta de instantáneas>/
  v1/
    manifest.json
    categories.json  tags.json  authors.json  media.json  galleries.json
    destinations.json  trips.json  projects.json  articles.json
    articles/<slug>.json
  v2/
  .tmp-v3-…/        ← generación en curso o interrumpida: nunca es una versión
```

- **Contenido:** el contrato de `src/content`, reconstruido desde las **revisiones publicadas**
  (`published_revision_id`) con el mismo lector que la exportación (`ContentExporter.readPublished`,
  transacción de solo lectura). Un borrador (revisión de cabeza sin publicar) nunca aparece.
- **Formato:** orden canónico del contrato y el mismo formato JSON que la exportación (2 espacios,
  LF, números sin exponente). Respecto a `src/content`, el contenido es **idéntico** (0 diferencias
  con la comparación semántica) y solo cambia el formato de Prettier (listas cortas en una línea).
  Respecto a la exportación del mismo estado, es **idéntico byte a byte**.
- **`original` de las imágenes:** se conserva en `media.json`, porque es el contrato interno exacto
  y `validateContent()` lo exige. La web lo quita al servir el JSON al navegador (proyección
  pública, 6.3.2); el volumen de instantáneas nunca se publica tal cual.

### `manifest.json`

```json
{
  "schemaVersion": 1,
  "version": "v12",
  "createdAt": "2026-11-18T10:00:00Z",
  "generator": "cms-api/snapshot-1",
  "contentSha256": "…",
  "articles": 12,
  "files": [{ "path": "articles.json", "bytes": 1234, "sha256": "…" }]
}
```

- `schemaVersion`: versión del contrato. La web rechazará la que no conozca.
- `version`: igual que el nombre de la carpeta (`v1`, `v2`…: `^v[1-9][0-9]{0,8}$`).
- `files`: cada archivo de contenido con su tamaño y su SHA-256, ordenados por ruta.
- `contentSha256`: SHA-256 de las líneas `"<ruta> <sha256>\n"` de esos archivos. No depende de la
  versión ni de la fecha. Dos instantáneas del mismo estado publicado tienen:
  - **los archivos de contenido idénticos byte a byte** (las colecciones y los cuerpos);
  - **el mismo `contentSha256`**;
  - **manifiestos distintos solo en `version` y `createdAt`**: el resto del manifiesto coincide.

  Comprobado en la 6.3.1 con v1 y v2 generadas desde la base de datos local.

## Escritura atómica e inmutable

1. Se crea `.tmp-vN-<aleatorio>/`.
2. Cada archivo se crea (nunca se sobrescribe), se escribe entero y se fuerza al disco (`fsync`).
   Donde hay POSIX, con permisos explícitos: 0644 los archivos y 0755 las carpetas.
3. Se escribe el manifiesto al final y se hace `fsync` de las carpetas.
4. Se comprueba la carpeta temporal con `SnapshotVerifier` (abajo).
5. Se renombra a `vN` de forma atómica y se hace `fsync` de la carpeta raíz.

| Si falla o se cae…                         | Resultado                                                                       |
| ------------------------------------------ | ------------------------------------------------------------------------------- |
| Un error en los pasos 1 a 4                | Se borra la temporal. `vN` no existe y se puede volver a generar                |
| Una caída (kill, corte) en los pasos 1 a 4 | Queda `.tmp-vN-…`: nunca se lista como versión y `removeTemporaries()` la borra |
| Una caída tras el renombrado (5)           | `vN` existe completa y correcta                                                 |
| `vN` ya existe                             | Se rechaza sin tocarla: las versiones no se sobrescriben nunca                  |

El `fsync` de una carpeta solo es posible en Linux (el contenedor); en Windows se omite y el
renombrado sigue siendo atómico. El escritor no coordina a dos generadores a la vez: el servicio de
publicación (6.3.4) lo llamará dentro de su bloqueo.

**Rutas:** solo se escriben las nueve colecciones del contrato y `articles/<slug>.json` con un slug
válido (`^[a-z0-9]+(-[a-z0-9]+)*$`). La versión debe cumplir su patrón. Un campo que el contrato no
conoce se rechaza. La carpeta de instantáneas no puede estar dentro de un repositorio Git (en el
contenedor no se puede comprobar: el montaje debe estar fuera).

## Comprobación en Java (`SnapshotVerifier`)

Comprueba que lo escrito es exactamente lo generado: el nombre de la carpeta, `schemaVersion`, que
el manifiesto corresponda a la carpeta, que estén **solo** los archivos declarados (sin enlaces,
otras carpetas ni archivos de más o de menos), el tamaño y el hash de cada uno, `contentSha256`, la
forma del contrato (`ContentSource.check`, la misma que la importación) y el número de artículos.
No sustituye a la puerta autoritativa, que será `validateContent()` en la web (6.3.3).

## Orden de la herramienta (solo para pruebas en 6.3.1)

```bash
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm \
  -v "$(pwd)/../blog-instantaneas:/instantaneas" \
  content-tool --cms.tool=generar-instantanea --cms.snapshots-dir=/instantaneas
```

Genera la siguiente versión (o la de `--cms.snapshot-version=vN`), borra antes las temporales de
generaciones interrumpidas e imprime la versión, el número de archivos y `contentSha256`. Código de
salida: `0` correcto y `1` error (por ejemplo, una versión que ya existe). Se ejecuta como
`cms_tool` (rol `cms_importer`), igual que la exportación. El volumen de instantáneas del Compose
llega en la 6.3.5.

## Lectura de instantáneas en la web (6.3.2)

**Dos builds.** El modo instantánea es un build aparte, para que ningún bundle lleve contenido:

| Build                                            | Contenido                                        | Portada y artículos         |
| ------------------------------------------------ | ------------------------------------------------ | --------------------------- |
| `npm run build` (el de siempre)                  | `src/content` dentro del build, como hasta ahora | Prerenderizados             |
| `npm run build:snapshot` (`production,snapshot`) | Ninguno: la instantánea, en tiempo de ejecución  | Renderizados en el servidor |

El build de instantáneas sustituye dos archivos (`fileReplacements` de `angular.json`):
`json/content-source.ts` (el único que importa `src/content`) por
`snapshot/content-source.snapshot.ts`, y `content-mode.server.ts` por
`snapshot/content-mode.server.snapshot.ts`. Sale en `dist/cristian-atienza-instantaneas` y solo
se combina con `production` (en desarrollo, el navegador validaría la proyección pública, que no
lleva `original`). Las páginas estáticas (secciones, archivo y «Sobre mí») se siguen
prerenderizando: no consultan contenido en el servidor.

**Arranque** (`npm run serve:snapshot`):

| Variable                | Uso                                                                        |
| ----------------------- | -------------------------------------------------------------------------- |
| `CONTENT_SNAPSHOTS_DIR` | Carpeta de instantáneas (solo lectura)                                     |
| `CONTENT_STATE_DIR`     | Carpeta `estado/` con `activa.json` (6.3.3; la única en la que escribe)    |
| `WEB_INTERNAL_BIND`     | `IP:puerto` del puerto interno de activación (6.3.3; sin ella, no se abre) |

(En la 6.3.2, `CONTENT_SNAPSHOT_VERSION` fijaba la versión; en la 6.3.3 desaparece: manda
`activa.json`.)

Al cargar una versión se comprueba **entera**, archivo a archivo y sin guardar los cuerpos en
memoria: `schemaVersion`, que el manifiesto sea de esa carpeta, que estén exactamente los archivos
declarados (sin enlaces, carpetas ni archivos de más o de menos), el tamaño y el SHA-256 de cada
uno, `contentSha256` y la correspondencia entre artículos y cuerpos. Un artículo alterado no pasa
aunque nadie lo haya pedido todavía. Cada cuerpo se vuelve a comprobar al leerlo (como mucho 64
analizados en memoria).

**SSR.** `server.ts` fija la versión al llegar cada petición (`REQUEST_CONTEXT`); la aplicación de
esa petición la usa entera (nunca mezcla dos) y la transfiere al navegador (TransferState,
`content:snapshot-version`). El SSR usa el contrato interno completo; el dominio, el HTML y
TransferState no llevan `original` (`catalog.toMedia` lo descarta).

**Navegador.** Después de la hidratación, el contenido llega por HTTP desde `/content/vN/` con la
versión transferida; en una página prerenderizada, la pregunta antes a `/content/version.json`.
Cada cuerpo se descarga una vez.

**`/content/`** (solo `GET` y `HEAD`; lo demás, 405):

| Ruta                               | Respuesta                                              |
| ---------------------------------- | ------------------------------------------------------ |
| `/content/version.json`            | `{"version":"vN"}`, `Cache-Control: no-cache`          |
| `/content/vN/<colección>.json`     | Las 9 colecciones; `media.json` **sin `original`**     |
| `/content/vN/articles/<slug>.json` | El cuerpo                                              |
| Cualquier otra                     | 404 (`no-store`): el manifiesto, otras versiones, etc. |

Las respuestas de contenido llevan `Cache-Control: public, max-age=31536000, immutable` y
`X-Content-Type-Options: nosniff`. La ruta se comprueba **sin decodificar** contra una lista
cerrada (versión, colección del contrato o slug válido): `..`, `%2e`, `%252e`, `\`, `%5c`, `%00`,
mayúsculas o barras dobles no coinciden y nada de `/content/` llega a los archivos estáticos ni al
SSR.

**Comprobación completa** (con los dos builds hechos):

```bash
node infra/scripts/comprobar-web-instantanea.mjs ../blog-instantaneas v1 [<build de referencia>]
```

Compara el build convencional con uno de referencia (archivos del navegador idénticos byte a byte y
páginas idénticas salvo la numeración de las plantillas de hidratación, que Angular no genera de
forma determinista), el HTML de las 20 rutas entre los dos builds (con TransferState comparado sin
el orden de las claves: la instantánea usa el orden canónico del contrato), la ausencia de valores
privados, de la clave `original` y de hashes en los bundles, en `/content/`, en el HTML y en
TransferState, y que las rutas fuera de la lista cerrada den 404.

**Enlaces simbólicos.** Los enlaces de carpeta (la versión o una carpeta dentro) se prueban en
Windows. Los enlaces a archivos (Windows exige privilegios para crearlos y el test se omite allí)
se probaron en Linux (contenedor `node:26.13.0` sin red, uid 1000, raíz de solo lectura) con el
almacén y el manejador reales y con el servidor del build. Siete variantes (cuerpo, colección y
manifiesto; destino absoluto y relativo; una copia exacta, contenido ajeno, `/etc/passwd` y un
archivo ilegible) se rechazan al cargar **sin abrir el destino**, y nada del destino se sirve. El
servidor del build no arranca con una versión así.

**Hallazgo H1 (corrección propuesta para la 6.3.3, pendiente de aprobación).** Si, con una versión ya cargada, alguien con
escritura en el volumen sustituye un cuerpo por un enlace, la lectura bajo demanda lo sigue: con
contenido distinto el hash no coincide y responde 500 (no se sirve nada); con una copia exacta
(mismos bytes) lo sirve. No sale nada ajeno, pero se abre un archivo fuera de la versión. En la
6.3.3 la lectura se haría sin seguir enlaces.

## Puerta, activación y caché en la web (6.3.3)

Solo en el build de instantáneas. Código en `src/app/content/snapshot/`: `activation.ts`,
`internal-server.ts`, `html-cache.ts` y `content-mode.server.snapshot.ts`.

### `estado/activa.json`

```json
{
  "schemaVersion": 1,
  "version": "v12",
  "contentSha256": "…",
  "activatedAt": "2026-11-20T10:00:00.000Z",
  "previous": "v11"
}
```

Es **la referencia de la versión que sirve la web**. Solo la escribe la web (tras activar) y la
leerá la API para reconciliar PostgreSQL (6.3.4).

### Activar una versión

1. Cargarla con la comprobación completa del almacén.
2. **La puerta:** `validateContent()` con el contrato interno completo (con `original`) y las mismas
   opciones que el test del contenido del build: `externalMedia` (las fotos del menú) y
   `retiredMedia` (`RETIRED_MEDIA`).
3. Escribir `activa.json`: un archivo temporal en `estado/`, `fsync`, renombrado atómico y `fsync`
   de la carpeta.
4. Solo entonces cambiar la versión en memoria, de una vez: la activa pasa a ser la anterior.

Si 1 o 2 fallan, nada cambia y se devuelven las incidencias. Si 3 falla, nada cambia (el temporal
se borra). Si el proceso cae entre 3 y 4, al arrancar manda `activa.json`. Las activaciones se hacen
de una en una. Activar la versión activa no cambia nada, ni siquiera reescribe el puntero.

### Arranque, salud y recuperación

Al arrancar se lee `activa.json`, se carga la versión, pasa la puerta y se comprueba que su huella
es la del puntero; la anterior se sirve si sigue siendo correcta. **Sin versión activa válida**
(sin puntero, puntero dañado, huella distinta o versión que ya no pasa la puerta), la web arranca
igual y **no vuelve atrás sola** (N3):

| Ruta                              | Sin versión activa            | Con versión activa |
| --------------------------------- | ----------------------------- | ------------------ |
| `/_salud/vida`                    | 200 `{"status":"UP"}`         | 200                |
| `/_salud/listo`                   | 503 `{"status":"DOWN"}`       | 200 `UP`           |
| Páginas y `/content/version.json` | 503 con `Retry-After: 5`      | Normal             |
| Archivos estáticos                | Se sirven                     | Se sirven          |
| Puerto interno (`/interno/…`)     | **Funciona: así se recupera** | Funciona           |

La salud solo dice el estado (nada de versiones ni incidencias: eso está en el puerto interno).

### Puerto interno

Escucha solo en `WEB_INTERNAL_BIND` (nunca `0.0.0.0`), aparte del puerto público:

| Petición                                    | Respuesta                                                                          |
| ------------------------------------------- | ---------------------------------------------------------------------------------- |
| `POST /interno/activar` `{"version":"vN"}`  | 200 `activada` / `sin cambios` con el puntero; 422 `rechazada` con las incidencias |
| `GET /interno/estado`                       | `listo`, `activa`, `anterior`, `contentSha256`, `activadaEn`, `incidencias`        |
| Cualquier otra (ruta cruda exacta, sin `?`) | 404                                                                                |

El cuerpo: `application/json` (si no, 415), como mucho 1 KB (si no, 413), exactamente
`{"version":"vN"}` con el patrón de versión (si no, 400). Nunca recibe contenido ni rutas.

### Versiones servidas (Q1) y navegador

Se sirven la activa y la anterior. Una ruta bien formada de otra versión recibe **410**: el
navegador recarga la página **una vez** (marca en `sessionStorage`; sin `sessionStorage`, no
recarga). La recarga es de la URL en la que está el visitante: si el 410 llega al navegar a otro
artículo, vuelve a la página de partida con la versión nueva y tiene que volver a pulsar.

### Caché de HTML (Q3)

En memoria, LRU de 500 entradas y 64 MB. Solo `GET` sin parámetros de consulta de la portada y los
artículos, con estado 200 y HTML. Clave: la versión fijada en la petición más la ruta (al activar
otra versión, las páginas viejas salen solas). El HTML se sirve con `Cache-Control: no-cache` (M9)
y una cabecera `X-Cache: HIT|MISS`. Sin seguimiento de `activa.json` (Q4): con una sola instancia,
lo escribe la propia web.

### Lectura sin seguir enlaces (H1)

| Sistema                 | Protección                                                        | Garantía                                                                                                           |
| ----------------------- | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Linux (el entorno real) | `open()` con `O_NOFOLLOW` y `fstat()` sobre el descriptor abierto | Un archivo cambiado por un enlace **no se abre** (ELOOP); no hay intervalo entre comprobar y abrir                 |
| Windows (desarrollo)    | `lstat()` y después `open()`                                      | **No equivalente:** queda un intervalo (TOCTOU) en el que un enlace podría colarse y el destino llegaría a abrirse |

En los dos, `fstat()` comprueba además que el archivo abierto es **el mismo** (dispositivo e inodo)
que se comprobó al cargar: aunque se sustituya la carpeta `articles/` por un enlace (`O_NOFOLLOW`
solo protege el último componente) o un cuerpo por otro archivo con los mismos bytes, no se sirve.

### Comprobaciones

```bash
node infra/scripts/comprobar-activacion.mjs ../blog-instantaneas
node infra/scripts/comprobar-web-instantanea.mjs ../blog-instantaneas v1 [<build de referencia>]
```

## Servicio de publicación en la API (6.3.4)

Código en `api/src/main/java/com/cristianatienza/cms/content/publication/`; tablas en
`V10__publicacion.sql`. La web solo cambia en su puerto interno (la época y la recuperación).

### Historial y matriz de transiciones (V10)

`content_publication` guarda una fila por versión. `estado/activa.json` (lo escribe la web) es la
referencia de lo que se sirve; esta tabla es el historial y se reconcilia con él.

| Desde \ Hacia | candidata                              | activa                            | retirada       | rechazada         | fallida                           |
| ------------- | -------------------------------------- | --------------------------------- | -------------- | ----------------- | --------------------------------- |
| (nueva)       | — (se inserta solo como **generando**) |                                   |                |                   |                                   |
| **generando** | generada y comprobada (huella)         | ✗                                 | ✗              | ✗                 | interrumpida (sin huella)         |
| **candidata** | —                                      | la web la activa / reconciliación | ✗              | la web la rechaza | no se activó (conserva la huella) |
| **activa**    | ✗                                      | —                                 | se activa otra | ✗                 | ✗                                 |
| **retirada**  | ✗                                      | volver atrás / reconciliación     | —              | ✗                 | ✗                                 |
| **rechazada** | ✗                                      | ✗ (estado final)                  | ✗              | —                 | ✗                                 |
| **fallida**   | ✗                                      | si tiene huella y archivos        | ✗              | ✗                 | —                                 |

El trigger rechaza cualquier ✗, y además:

- la huella solo puede faltar en `generando` y en `fallida` sin completar;
- ninguna versión sin huella o con los archivos purgados se activa;
- la huella y los datos del manifiesto solo se fijan al completar la generación;
- `activated_at` y `previous_version`, solo al pasar a `activa`; las incidencias, solo al rechazar
  o fallar;
- la purga solo rellena `files_purged_at` (nunca de la activa);
- una sola `activa` (índice único).

**Permisos de `cms_app`** (la API y sus herramientas, mismo login):

| Permiso                                                                                                                         | Para qué                                                                      |
| ------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `SELECT` de `content_publication`                                                                                               | Leer estados y huellas                                                        |
| `INSERT (version, state, requested_by, requested_via)`                                                                          | P1                                                                            |
| `UPDATE (state, content_sha256, files, bytes, articles, generated_at, issues, activated_at, previous_version, files_purged_at)` | P2, P4, volver atrás, rechazos, fallos y purga                                |
| `SELECT, UPDATE (epoch, holder, acquired_at)` de `content_publication_lease`                                                    | Tomar el control y bloquear la fila en cada transacción                       |
| `SELECT, UPDATE (status, detail, pointer_version, checked_at)` de `content_publication_check`                                   | Resultado de la reconciliación (fila sembrada: sin `INSERT`)                  |
| `EXECUTE` de `article_revision_content`                                                                                         | Leer lo publicado sin `SET ROLE` (no es `SECURITY DEFINER`: sin acceso nuevo) |

Nunca `version`, `requested_by`, `requested_via` ni `created_at`; sin `DELETE` ni `TRUNCATE`;
`updated_at` lo fija el trigger. Ningún permiso nuevo para `cms_tool` ni otros roles.

### Control: bloqueo asesor y época (fencing)

1. **Bloqueo asesor** en una conexión propia, fuera del pool (cerrarla termina la sesión y lo
   libera). Plazo global de **15 s** con reloj monotónico: la conexión solo se intenta con al
   menos 1 s restante (`connectTimeout` y `loginTimeout` iguales al tiempo restante redondeado
   hacia abajo); cada intento (`pg_try_advisory_lock`, que no bloquea) lleva `setNetworkTimeout` y
   `statement_timeout` iguales al tiempo restante; entre intentos, el mínimo de 200 ms y lo que
   quede.
2. **Heartbeat** cada 10 s por esa conexión: comprueba en `pg_locks` que esta sesión sigue teniendo
   el bloqueo. `idle_session_timeout` de 60 s (al menos 3 latidos) y keepalives TCP. Si falla, la
   sesión queda perdida, se interrumpe al hilo dueño y se cierra la conexión. La conexión la usan
   solo adquirir, el heartbeat y liberar, nunca a la vez (un `ReentrantLock`); el hilo de
   publicación no la toca.
3. **Época nueva:** `epoch = greatest(epoch, época de activa.json) + 1` (cubre una restauración de
   PostgreSQL).
4. **Toda transacción que cambia el estado** empieza con `SELECT epoch … FOR UPDATE` y aborta si no
   es la suya; además comprueba en `pg_locks`, desde su propia conexión, que la sesión del bloqueo
   lo sigue teniendo (detección inmediata). El bloqueo de la fila ordena al publicador antiguo y al
   nuevo: no hay intervalo entre comprobar y escribir.
5. **En la web:** la API reserva su época (`/interno/reservar`) al tomar el control y otra vez, ya
   con la fila bloqueada, justo antes de activar con esa época (`/interno/activar`). La web solo
   activa con la época reservada: una petición antigua que llegue tarde recibe 409.

### Publicar (P1–P4)

| Paso       | Qué                                                                                                                    | Transacción                                                         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Inicio     | Tomar el control, reservar la época en la web (sin garantías) y reconciliar                                            | —                                                                   |
| Lectura    | Lo publicado (como `cms_app`) y su huella en memoria. «Sin cambios» solo si está CONCILIADO y coincide con activa.json | —                                                                   |
| P1         | Fila `generando` con el número siguiente al mayor de PostgreSQL y de la carpeta                                        | Protegida                                                           |
| Generación | `.tmp-vN`, fsync y comprobación                                                                                        | —                                                                   |
| P2         | `candidata` con la huella **y** renombrado a `vN`                                                                      | Protegida, la misma                                                 |
| P3/P4      | Reservar (recuperar la época si la web está bloqueada), activar en la web y registrar el resultado con su auditoría    | Protegida, la misma (fila de la época bloqueada durante la llamada) |
| Fin        | Reconciliar                                                                                                            | Protegida                                                           |

Si se pierde el bloqueo después de P2 y antes de la web, PostgreSQL tiene `candidata` con huella y
`vN` completa; la siguiente reconciliación la pasa a `fallida` (conserva huella y archivos, se
puede activar después) o, si activa.json ya la señala, a `activa`. Si se corta PostgreSQL entre el
renombrado y el COMMIT, la reconciliación adopta la carpeta con la huella de su manifiesto.

### Reconciliación

Al arrancar la API (reintenta cada 30 s si PostgreSQL no está; no afecta a readiness) y, dentro del
control, antes de cada operación. Solo aplica transiciones de la matriz:

| Situación                                                                                      | Resultado                                                           |
| ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Puntero válido a una `activa` con su huella                                                    | CONCILIADO                                                          |
| Puntero a `candidata`, `retirada` o `fallida` con huella (misma huella)                        | Pasa a `activa` (la anterior, a `retirada`), un evento → CONCILIADO |
| Puntero a `rechazada`, `fallida` sin huella o purgada, `generando`, con otra huella o sin fila | **INCONSISTENTE**, sin cambios                                      |
| Sin activa.json y sin nada activado nunca                                                      | VACÍO                                                               |
| Sin activa.json con versiones activadas, o activa.json dañado                                  | **INCONSISTENTE**, sin cambios                                      |

Además, las `generando` abandonadas pasan a `fallida` (o se adoptan si su carpeta está completa) y,
con un puntero válido, las `candidata` que no son la del puntero pasan a `fallida`. El resultado se
guarda en `content_publication_check` y se audita **solo cuando cambia**. La recuperación de una
inconsistencia es explícita: activar una versión o publicar. Si se perdió el volumen de estado de la
web, hay que reiniciarla antes (mientras sigue en marcha, recuerda la versión que tenía activa y
activarla de nuevo no reescribe el puntero).

### Endpoints, herramientas y auditoría

| Ruta                                     | Política                        | Respuestas                                                                               |
| ---------------------------------------- | ------------------------------- | ---------------------------------------------------------------------------------------- |
| `POST /api/cms/publicaciones`            | ADMIN + passkey reciente + CSRF | 201 activada, 200 sin cambios, 422 rechazada, 503 fallida o sin configurar, 409 en curso |
| `POST /api/cms/publicaciones/vN/activar` | Ídem                            | 200, 404, 409, 422 o 503                                                                 |
| `GET /api/cms/publicaciones`             | ADMIN                           | Conciliación, época y las 50 últimas                                                     |

Herramientas, con el login de la API (`cms_api`): `--cms.tool=publicar`, `--cms.tool=activar
--cms.version=vN` y `--cms.tool=purgar-instantaneas [--cms.confirmar=true]`. Sin confirmar, la
purga solo simula; nunca toca la activa, la anterior ni las 20 últimas; borra archivos y conserva
las filas. **Quién:** solo hechos verificables, el login de PostgreSQL (`session_user`) y la
máquina; no aceptan un operador declarado. La identidad personal verificada solo la da la vía HTTP.

Eventos, en la transacción del cambio de estado y sin contenido: `content.published`,
`content.activated`, `content.publish_rejected`, `content.activation_rejected`,
`content.publish_failed`, `content.reconciled`, `content.reconciliation_failed`,
`content.web_epoch_recovered` y `content.snapshot_purged`.

### Época en la web y recuperación de control.json

La web guarda la época reservada en `estado/control.json` y la usada en cada activación en
`activa.json` (`epoch`). Si `control.json` está dañado, o falta habiendo `activa.json`, la web queda
**bloqueada**: responde 423 a reservas y activaciones (y sigue sirviendo el contenido) hasta una
recuperación en dos pasos. `/interno/recuperacion/iniciar` da un nonce aleatorio (anula el
anterior; solo en memoria) y `/interno/recuperacion/completar` lo valida, lo **consume** y escribe
`control.json`, todo en la misma sección crítica (la cola de la web). La época debe superar la de
`activa.json`. La API la hace automáticamente, con su época y dentro de su transacción protegida.

### Pruebas

| Prueba                                                                                                                    | Dónde                                                                 |
| ------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Matriz, restricciones y permisos con logins reales                                                                        | `PublicationSchemaTests`                                              |
| Flujo, rechazos, web caída, respuesta perdida, recuperación de la época, purga y concurrencia                             | `PublicationServiceTests`                                             |
| Interrupciones P1–P4, reconciliación (idempotente, 3 veces) y PostgreSQL caído al arrancar                                | `PublicationRecoveryTests`                                            |
| Fencing, pérdida real del bloqueo, espera de 15 s y publicación de más de 120 s                                           | `PublicationFencingTests`                                             |
| Conexión del bloqueo: un uso a la vez, liberar espera al latido, propagación, proceso congelado y plazo de cada operación | `PublicationLockTests`                                                |
| Autorización HTTP y CSRF                                                                                                  | `PublicationEndpointTests`                                            |
| Herramientas con `cms_api`                                                                                                | `PublicationToolTests`                                                |
| Extremo a extremo con el servidor Node real                                                                               | `PublicationWebEndToEndTests` (se omite sin `npm run build:snapshot`) |
| Época y recuperación en la web real (incluidos dos `completar` simultáneos)                                               | `control.spec.ts` e `internal-server.spec.ts` (Vitest)                |

### Rendimiento de la activación: 6.3.3 frente a 6.3.4

Comparación controlada (`infra/bench/scripts/comparar-activacion.mjs`): los dos builds en la
misma máquina, en tres rondas alternas, sin ninguna otra carga y con la misma carga sintética (8
clientes, la mitad esquivando la caché). Resultados en
`../blog-bench/resultados/6.3.4-comparacion-activacion.json` y `6.3.4-publicacion-completa.json`.

| Medida (ms)                     | 6.3.3 p50 / p95 / p99  | 6.3.4 p50 / p95 / p99    |
| ------------------------------- | ---------------------- | ------------------------ |
| SSR sin carga                   | 73 / 91 / 105          | 70 / 86 / 99             |
| SSR bajo carga                  | 182 / 229 / 269        | 180 / 225 / 250          |
| Reserva sin carga               | —                      | 9 / 12 / 25              |
| Reserva bajo carga              | —                      | 438 / 523 / 547          |
| Activación sin carga            | 109 / 141 / 148        | 122 / 143 / 154          |
| Activación bajo carga           | 9.313 / 9.909 / 10.133 | 9.072 / 9.904 / 9.927    |
| Reserva + activación bajo carga | 9.313 / 9.909 / 10.133 | 9.505 / 10.405 / 10.442  |
| Publicación completa sin carga  | —                      | 678 / 788 / 788          |
| Publicación completa bajo carga | —                      | 10.658 / 11.955 / 11.955 |

**Diagnóstico.** La activación de la 6.3.4 no es más lenta que la de la 6.3.3: con el mismo
entorno, las dos tardan unos 9 s bajo esta carga. La 6.3.4 solo añade la reserva de la época (unos
10 ms sin carga y unos 440 ms bajo carga). El build de la 6.3.3, que en su sesión daba 4,4–4,6 s,
da ahora unos 9,3 s: el entorno se ha degradado (el SSR de un artículo tarda ahora unos 70 ms frente
a unos 33 ms de referencia de la 6.1.4). Bajo saturación, la activación espera detrás de los
renderizados en el único proceso de Node. **El criterio de 5 s no se cumple con esta carga en esta
máquina, ni con la 6.3.3 ni con la 6.3.4**.

**Excepción aprobada al cerrar la 6.3.4.** El criterio queda incumplido en el entorno local actual.
**El umbral de 5 s se mantiene** y no se ha optimizado nada: queda **pendiente de validación en la
infraestructura real de la fase 6.9** (el VPS y sus límites definitivos de CPU y memoria), con la
misma comparación (`comparar-activacion.mjs`) y la misma carga.

## Ejecución conjunta en contenedores (6.3.5)

La API, la web y PostgreSQL funcionan juntos en `infra/local/compose.yaml`. El diseño completo (redes,
volúmenes, permisos, endurecimiento, límites, copias y producción) está en
[`infra/docs/ejecucion.md`](../../infra/docs/ejecucion.md). Lo que afecta a la publicación:

- **Volúmenes:** `instantaneas` (la API escribe, la web solo lee) y `estado` (la web escribe, la API
  solo lee). El servicio `volumenes` prepara solo sus raíces, sin tocar las versiones ya generadas.
- **Red `publicacion`** (interna, subred fija): la web solo escucha su puerto interno en su IP fija
  de esa red (`WEB_INTERNAL_BIND`); la API lo usa con `CMS_PUBLICATION_WEBINTERNALURL`. No se
  publica ni es alcanzable desde el equipo ni desde la red `borde`.
- **Herramientas:** el servicio `publication-tool` (perfil `tools`), con el login de la API.
- **Comprobado** con `infra/scripts/comprobar-integracion.mjs`, en un proyecto Compose aislado:
  - un cambio se ve sin build en unos 6,6 s, casi todo el arranque de la JVM de la herramienta;
  - la vuelta atrás funciona;
  - una instantánea manipulada en disco se rechaza (422) y la web sigue con la anterior;
  - respuesta perdida reproducida con un tiempo de espera de 30 ms: la herramienta informa
    `fallida`, la web sí la activó y la reconciliación la pasa a `activa` (`content.reconciled`);
  - con la web en pausa la publicación no se activa y lo servido no cambia;
  - con la API caída, la web sirve, también tras reiniciarse;
  - PostgreSQL reiniciado: la API vuelve y publica.

## Del CMS a la web (fase 6.4)

La 6.4 no cambia nada de lo anterior: ni el generador, ni la puerta, ni P1–P4, ni la web. Añade la
forma de decidir **qué** está publicado sin la importación (V11, [`modelo-de-datos.md`](modelo-de-datos.md)).

| Paso               | Quién y cómo                                                                          | Qué cambia                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Editar             | ADMIN o EDITOR: crear, guardar y restaurar revisiones (`cms-api.md`)                  | Solo la revisión de trabajo. Lo publicado no cambia                                               |
| Marcar o desmarcar | ADMIN: `…/publicacion` o `…/despublicacion` → `publish_article` o `unpublish_article` | Solo `published_revision_id` (y, la primera vez, `published_at` y el orden). **La web no cambia** |
| Publicar la web    | ADMIN con passkey reciente: `POST /api/cms/publicaciones` (P1–P4)                     | Una instantánea nueva con lo marcado en ese momento, que la web activa entera o no activa         |

- **Una sola lectura:** el generador lee lo publicado con `ContentExporter.readPublished` (como
  `cms_app`), que sigue los punteros `published_revision_id`. La edición y la publicación no comparten
  ningún otro código: la gestión editorial nunca llama a `PublicationService`.
- **Sin estados intermedios:** lo marcado solo llega a la web en la siguiente instantánea, que es
  atómica (P1–P4). Una revisión de trabajo sin marcar no entra: si nada marcado ha cambiado, publicar
  responde 200 `sin cambios` y no crea versión.
- **Despublicar:** el artículo sale de la siguiente instantánea; su página da 404 y no está en
  `articles.json` de la versión activa. La versión **anterior** se sigue sirviendo por su ruta
  `/content/vN/…` (Q1, para el 410 y la recarga del navegador) hasta que deja de ser la anterior.
- **Reglas de conjunto:** las aplica solo la puerta, sobre la instantánea entera. La instantánea lleva
  **todas** las imágenes y galerías y solo los artículos publicados, así que **despublicar el único
  artículo que usa una imagen o una galería** la deja «sin usar» y la puerta **rechaza** la siguiente
  instantánea (422 `rechazada`). Es atómico: la web sigue sirviendo la versión activa. Con el
  contenido real pasa con `hora-azul-notas-de-exposicion` (`paisaje-hora-azul-dique`) y
  `plantilla-de-relato` (`naturaleza-niebla-bosque`). Hasta que la 6.5 permita retirar imágenes, la
  salida es volver a marcar el artículo (publicar da entonces `sin cambios`). Limitación pendiente de
  decisión: ver el informe de cierre de la 6.4.

### Pruebas (6.4.8)

| Prueba                                                                                                                   | Dónde                                                                  |
| ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Crear, guardar, marcar, publicar v2, la web real sirve página y JSON; revisión sin marcar no entra; despublicar, v3, 404 | `CmsToWebEndToEndTests` (API y web reales; se omite sin el build)      |
| Despublicar el único artículo que usa una imagen: instantánea rechazada, la web sigue en la activa                       | `CmsToWebEndToEndTests`                                                |
| Exportar, editar por la API, volver a exportar: solo cambia lo editado, en los archivos y en la base de datos            | `EditorialExportRoundTripTests`                                        |
| Marcar y desmarcar: fecha, orden, versión, conflictos y concurrencia                                                     | `EditorialWriteEndpointTests` y `ArticlePublishingSchemaTests` (6.4.5) |
