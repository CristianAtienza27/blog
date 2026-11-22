# Infraestructura de ejecución (fase 6.3.5)

Cómo funcionan juntos PostgreSQL, la API (y el CMS que sirve) y la web pública en modo
instantáneas. La 6.3.5 implementa la **integración local** (`infra/local/compose.yaml`); la
producción (VPS, Caddy, TLS, Cloudflare, copias a B2) es de la **fase 6.9** y aquí solo queda su
diseño. Publicación: [`api/docs/publicacion.md`](../../api/docs/publicacion.md).

## 1. Componentes

```
 Equipo (solo 127.0.0.1)
   :4000 ──► web (Node, usuario node, uid 1000) ── red borde
   :8080 ──► api (Spring, usuario cms, uid 999) ── red borde
   :5432 ──► postgres ───────────────────────────── red postgres-local

 red datos (internal):        postgres ◄── api, migrate, herramientas      (la web no está)
 red publicacion (internal):  api ──HTTP──► web 10.203.63.10:4100           (/interno/*, no publicado)

 volúmenes: instantaneas (api rw, web ro) · estado (web rw, api ro) · postgres-data-v62
```

| Servicio                        | Imagen                                            | Usuario         | Papel                                                               |
| ------------------------------- | ------------------------------------------------- | --------------- | ------------------------------------------------------------------- |
| `postgres`                      | `postgres:18.6-trixie` (digest)                   | la de la imagen | Fuente de verdad                                                    |
| `migrate`                       | `cristian-atienza/cms-api:local`                  | `cms`           | Migraciones como `cms_migrator`; termina                            |
| `volumenes`                     | `cristian-atienza/web:local`                      | root, una vez   | Prepara las raíces de `instantaneas` y `estado`; termina            |
| `api`                           | `api/Dockerfile` (JRE 21, digest)                 | `cms` (999)     | CMS, publicación, reconciliación                                    |
| `web`                           | `infra/web/Dockerfile` (Node 26.13, digest)       | `node` (1000)   | Sirve la versión activa; solo escribe `estado/`                     |
| `publication-tool`              | `cristian-atienza/cms-api:local` (perfil `tools`) | `cms`           | `publicar`, `activar`, `purgar-instantaneas` con el login de la API |
| `content-tool`, `security-tool` | (perfil `tools`, sin cambios)                     | `cms`           | 6.1.3 y 6.2.6                                                       |

**Arranque:** `volumenes` y `postgres` (healthy) → `migrate` → `api`; `volumenes` → `web`. La API y
la web **no dependen entre sí**: la web sirve sin la API y la API reconcilia con reintentos si la
web aún no responde.

## 2. Imagen de la web

`infra/web/Dockerfile`, contexto la raíz del repositorio, lista de permitidos en
`infra/web/Dockerfile.dockerignore` (solo `package*.json`, `angular.json`, `tsconfig*.json`,
`public/` y `src/`).

- **Build:** `npm ci` y `npm run build:snapshot`. Con npm **11.6.2** fijado en esa etapa: el npm de la
  imagen (11.20.0) rechaza el `package-lock.json` actual (peers opcionales `@emnapi/*` de
  `@napi-rs/wasm-runtime`); fijar la herramienta evita tocar el lockfile y el build convencional.
- **Dependencias en ejecución (comprobado):** `server/server.mjs` está empaquetado y solo importa
  módulos internos de Node (`fs`, `path`, `crypto`, `url`, `stream`, `process`, `node:module`). La
  etapa final **no lleva `node_modules`, npm, npx, corepack ni yarn**: solo Node y `dist/…-instantaneas`
  (propiedad de root, solo lectura para `node`).
- El build de la imagen coincide con el local: 247 de 248 archivos idénticos; el otro
  (`angular-app-manifest.mjs`) solo cambia el orden de dos claves.

## 3. Redes y puertos

| Red              | Tipo                          | Miembros                             | Motivo                                                          |
| ---------------- | ----------------------------- | ------------------------------------ | --------------------------------------------------------------- |
| `datos`          | `internal`                    | postgres, api, migrate, herramientas | La web no alcanza PostgreSQL; sin salida a Internet             |
| `publicacion`    | `internal`, subred fija `/28` | api, web (IP fija)                   | El puerto interno solo escucha en la IP fija de la web aquí     |
| `borde`          | normal                        | api, web                             | Publicar sus puertos en 127.0.0.1 (una red `internal` no puede) |
| `postgres-local` | normal                        | postgres                             | Su puerto en 127.0.0.1 sin compartir red con la web             |

Puertos en el equipo, siempre en 127.0.0.1: 5432, 8080 y 4000 (configurables en `.env`). El 4100
no se publica nunca. En local, `borde` tiene salida a Internet; en producción será `internal` (§9).

## 4. Volúmenes y permisos

| Volumen        | API                 | Web                 | Raíz           | Contenido                                                                                          |
| -------------- | ------------------- | ------------------- | -------------- | -------------------------------------------------------------------------------------------------- |
| `instantaneas` | lectura y escritura | solo lectura        | 999:999 0755   | `vN/`: archivos 0644 y carpetas 0755 (6.3.1)                                                       |
| `estado`       | solo lectura        | lectura y escritura | 1000:1000 0755 | `activa.json` y `control.json`, 0644 (sin secretos: el nonce de recuperación solo vive en memoria) |

**`volumenes` (D4, `infra/local/volumenes/preparar.sh`):** root solo durante la preparación, sin red,
sistema de archivos de solo lectura, `cap_drop: ALL` y solo `CHOWN`, `no-new-privileges`. Para cada
raíz: si ya es propietario:grupo y 0755, no toca nada; si no, `chown 0:0`, `chmod 0755` y `chown`
al dueño (el `chmod` mientras el dueño es root, para no necesitar `FOWNER`). **No es recursivo:**
nunca cambia el contenido (las instantáneas ya generadas conservan sus permisos). Termina enseguida.

## 5. Configuración y secretos

Ningún secreto nuevo. Variables (no secretas), con valores por defecto en `compose.yaml` y ejemplo en
`infra/local/.env.example`:

- **Web:** `PORT=4000`, `CONTENT_SNAPSHOTS_DIR=/instantaneas`, `CONTENT_STATE_DIR=/estado`,
  `WEB_INTERNAL_BIND=<IP fija>:4100`, `PUBLIC_SITE_URL` y `MEDIA_BASE_URL` (vacías en local).
- **API y `publication-tool`:** `CMS_PUBLICATION_SNAPSHOTSDIR`, `CMS_PUBLICATION_STATEDIR`,
  `CMS_PUBLICATION_WEBINTERNALURL` (`cms.publication.*`).
- **Equipo:** `CMS_WEB_PORT`, `CMS_PUBLICACION_SUBNET`, `CMS_WEB_INTERNAL_IP`.

## 6. Seguridad y aislamiento

- **Endurecimiento (D3)** de `api`, `web` y `publication-tool`: `cap_drop: [ALL]` sin ninguna
  capacidad añadida, `no-new-privileges`, `read_only: true` y `/tmp` en `tmpfs` con `noexec,nosuid`
  (API 64 MiB: Tomcat y hsperfdata de la JVM; web 16 MiB). Comprobado: Java y Node funcionan así, y
  cada uno solo escribe en sus volúmenes autorizados y en su `/tmp`.
- La web no alcanza PostgreSQL (ni lo resuelve); el puerto interno no es alcanzable desde el equipo
  ni desde la red `borde`; las redes `datos` y `publicacion` no tienen salida a Internet.
- Cookies y CSRF sin cambios (6.2): `__Host-SESSION`, `__Host-XSRF-TOKEN`, `__Host-DEVICE`, `Secure` y
  `SameSite=Strict`.

## 7. Salud, reinicio, límites y registros

| Servicio | Salud                                      | Reinicio         | Límites                | Parada             |
| -------- | ------------------------------------------ | ---------------- | ---------------------- | ------------------ |
| postgres | `pg_isready`                               | `unless-stopped` | 1 GiB, 2 CPU           | 60 s               |
| api      | `/actuator/health/liveness` (curl)         | `unless-stopped` | 768 MiB, 3 CPU (6.2.7) | 30 s (graceful)    |
| web      | `/_salud/vida` (`node -e fetch`, sin curl) | `unless-stopped` | 512 MiB, 2 CPU         | 10 s, `init: true` |

- `/_salud/listo` no se usa como salud del contenedor: 503 sin versión activa es legítimo (es la
  sonda de Caddy, 6.9). Docker no reinicia un contenedor `unhealthy`; el reinicio llega cuando el
  proceso termina (`ExitOnOutOfMemoryError`, fallo de Node).
- `init: true` en la web: Node recibe SIGTERM y termina sin esperar al SIGKILL; las escrituras del
  estado son atómicas.
- Registros `json-file` con rotación: 10 MB por 3 archivos por servicio.

## 8. Operación

```bash
# Arrancar (o actualizar tras cambios: reconstruye las imágenes)
docker compose --env-file infra/local/.env -f infra/local/compose.yaml up --build -d
# Publicar y volver atrás
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=publicar
docker compose --env-file infra/local/.env -f infra/local/compose.yaml run --rm publication-tool --cms.tool=activar --cms.version=v3
# Parar sin borrar nada (NUNCA «down -v»: borraría los volúmenes)
docker compose --env-file infra/local/.env -f infra/local/compose.yaml stop
```

- **Migraciones:** solo el servicio `migrate` (`cms_migrator`), que se ejecuta en cada `up` antes de
  la API. La web no depende de él.
- **Actualizar:** `up --build -d`. Mientras la API se reinicia, la web sigue sirviendo.
- **Ante caídas:**

| Situación                         | Comportamiento (comprobado en `comprobar-integracion.mjs`)                           |
| --------------------------------- | ------------------------------------------------------------------------------------ |
| La API cae                        | La web sigue sirviendo la versión activa                                             |
| La web se reinicia, con o sin API | Lee `activa.json`, verifica la integridad y sirve                                    |
| La web no responde al publicar    | La publicación no se activa y nada cambia; el reintento posterior funciona           |
| Respuesta perdida                 | La API lo resuelve con `activa.json` (activada) y la reconciliación queda CONCILIADO |
| PostgreSQL se reinicia            | La API vuelve a estar lista y publica                                                |
| El proceso de la web termina      | Docker lo reinicia                                                                   |
| Se pierde el volumen `estado`     | Reiniciar la web antes de reactivar (6.3.4)                                          |

## 9. Producción (diseño para la fase 6.9; no implementado)

- **Exposición:** solo Caddy publica 80 y 443 (y SSH 22 en el host). Ningún otro servicio publica
  puertos: los puertos publicados por Docker **se saltan ufw**.
- **Redes:** `borde` pasa a `internal` (Caddy, API y web) y Caddy tiene además una red `externa`: la
  API y la web se quedan sin salida a Internet. `postgres-local` desaparece.
- **Caddy:** TLS, `dominio` → web:4000 y `admin.dominio` → api:8080, cabeceras de seguridad de la
  web, bloqueo de `/_salud/*` y `/actuator/*` desde fuera (salvo lo que necesite el chequeo externo).
- **API:** `forward-headers-strategy: native` y `server.tomcat.remoteip.internal-proxies` con la IP
  fija de Caddy (HSTS y la IP real del cliente, 6.2.5); orígenes WebAuthn de `admin.`.
- **Web:** `allowedHosts` del dominio real (preferiblemente en tiempo de ejecución, solo en el modo
  instantáneas, para no alterar el build convencional); `PUBLIC_SITE_URL` y `MEDIA_BASE_URL`; sin
  caché HTML en Cloudflare (M9).
- **Pendiente de medir allí:** límites definitivos, SerialGC y metaspace de la JVM, OOM y la
  activación en menos de 5 s (excepción de la 6.3.4).

## 10. Copias de seguridad (procedimiento previsto; simulacro en la 6.9)

**Rol.** `cms_maintenance` **no puede** hacer la copia (comprobado en la 6.3.5: no lee ninguna de las
36 tablas ni secuencias de `public`; `pg_dump` falla con `permission denied for table
flyway_schema_history`). No se usa `cms_migrator` como alternativa. Los privilegios de `cms_maintenance` **no se amplían** (decisión al cerrar la 6.3.5): el simulacro queda para la 6.9. **Decisión pendiente para la
6.9:** un rol de copia de solo lectura (por ejemplo, miembro de `pg_read_all_data`) en el script de
roles, sin tocar V1–V10. La copia contiene datos sensibles (sesiones, passkeys, auditoría): siempre
cifrada (restic, 6.0).

**Consistencia.** Una copia junta tres cosas que cambian en momentos distintos: PostgreSQL (la
verdad: versiones, estado y la época de `content_publication_lease`), `instantaneas` (derivado e
inmutable una vez confirmado) y `estado` (el puntero de la web y su época). Copiarlas por separado
mientras se publica podría capturar una carpeta temporal o una `vN` a medias, una fila `candidata`
sin activar o un `activa.json` que señale una versión que la copia de PostgreSQL no tiene como
activa. El procedimiento lo impide:

1. **Congelar la publicación:** tomar el bloqueo asesor de la publicación durante toda la copia (la
   misma clave que `PublicationLock`; no requiere privilegios). Ningún publicador puede empezar
   (esperaría 15 s y fallaría «ocupado») y ninguno está a medias. La web sigue sirviendo.
2. Con el bloqueo, por este orden: `pg_dump -Fc` (instantánea coherente por MVCC), copia de `estado/`
   y copia de `instantaneas/` (solo carpetas `vN` confirmadas, nunca las temporales).
3. Soltar el bloqueo.
4. **Validar antes de dar la copia por buena:** la versión de `activa.json` es la `activa` del dump,
   su carpeta está en la copia y su manifiesto tiene la misma `content_sha256`; la época de
   `control.json` no supera la de `content_publication_lease`.

**Restauración:** PostgreSQL, después `instantaneas`, después `estado`; arrancar la web (verifica la
integridad antes de estar lista) y la API (reconcilia: debe quedar CONCILIADO). Si `estado` falta o
no coincide, restaurar sin él, reiniciar la web y reactivar la versión activa con
`publication-tool --cms.tool=activar`. Si se pierden las instantáneas, `publicar` regenera el
contenido actual; las versiones antiguas solo vuelven desde la copia.
