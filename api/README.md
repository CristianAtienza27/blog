# cms-api

Backend del CMS de la revista: Spring Boot 4.1.2 sobre Java 21 y PostgreSQL 18.

Estado (fase 6.4, backend del CMS): esquema de datos, importación del contenido y separación de
roles de la base de datos (6.1), seguridad (6.2), publicación con instantáneas (6.3) y la API
editorial (6.4). Las migraciones (Flyway) crean el modelo del contenido editorial (18 tablas,
revisiones inmutables, referencias protegidas) y las aplica **solo** el contenedor de
migraciones (perfil `migrate`, login `cms_migrator` con `SET ROLE cms_owner`). La API y la
herramienta de contenido **no migran**: al arrancar comprueban que el esquema está en la versión
de su código y, si no, se niegan a arrancar. Cada proceso usa su propio login de PostgreSQL,
nunca el superusuario. Una herramienta de línea de comandos importa `src/content`, lo exporta al
mismo contrato JSON y compara ambos. La API expone `/actuator/health` (con sus sondas `liveness` y `readiness`, 6.2.7.2) y la autenticación con
contraseña, sesión (6.2.2), passkeys y códigos de recuperación (6.2.3), con limitación de
intentos, auditoría y dispositivos de confianza (6.2.4). La API editorial (6.4) gestiona artículos,
revisiones, publicación y orden, etiquetas, autores, lugares, itinerarios y proyectos; publicar la
web es la publicación de la 6.3.

> **No exponer a Internet** hasta publicarla con su proxy y Cloudflare Access (6.9).

- Esquema: [`docs/modelo-de-datos.md`](docs/modelo-de-datos.md).
- Importación y exportación: [`docs/importacion-exportacion.md`](docs/importacion-exportacion.md).
- Seguridad: [`docs/seguridad.md`](docs/seguridad.md).
- Publicación con instantáneas: [`docs/publicacion.md`](docs/publicacion.md).
- API editorial del CMS (contrato para la 6.6): [`docs/cms-api.md`](docs/cms-api.md).
- Validación del contenido: [`docs/validacion.md`](docs/validacion.md).

## Requisitos

- JDK 21 (`JAVA_HOME` apuntando a él).
- Docker Desktop: los tests levantan un PostgreSQL 18 real con Testcontainers.
- Maven no hace falta instalado: el wrapper (`mvnw`) descarga Maven 3.9.14 y verifica su SHA-256.

## Comandos (desde `api/`)

```bash
./mvnw verify            # compila y ejecuta los tests contra PostgreSQL 18 (Testcontainers)
./mvnw -DskipTests package
```

En Windows (PowerShell o cmd): `mvnw.cmd verify`.

Para arrancar la API junto a PostgreSQL en local, ver [`infra/local/README.md`](../infra/local/README.md).

Herramienta de contenido (sin servidor web; código de salida 0, 2 si hay diferencias, 1 si hay error):

```bash
java -jar target/cms-api-*.jar --spring.main.web-application-type=none --cms.tool=import \
  --cms.content-dir=../src/content --cms.retired-file=../src/app/editorial/retired-media.ts \
  --cms.navigation-file=../src/app/layout/navigation.ts
java -jar target/cms-api-*.jar --spring.main.web-application-type=none --cms.tool=export --cms.export-dir=../../blog-export
java -jar target/cms-api-*.jar --spring.main.web-application-type=none --cms.tool=compare \
  --cms.content-dir=../src/content --cms.export-dir=../../blog-export
```

En local lo normal es lanzarla con el servicio `content-tool` de Docker Compose.

Herramienta de seguridad (fase 6.2.6; como `cms_security_tool`; la contraseña, siempre desde un
archivo; código de salida 0, 2 argumentos o contraseña no válidos, 3 usuario existente o inexistente,
1 error):

```bash
java -jar target/cms-api-*.jar --spring.main.web-application-type=none --cms.tool=crear-admin \
  --cms.usuario=<correo> --cms.contrasena-desde=<archivo>
java -jar target/cms-api-*.jar --spring.main.web-application-type=none --cms.tool=recuperacion-emergencia \
  --cms.usuario=<correo> --cms.nueva-contrasena-desde=<archivo>
```

En local, con el servicio `security-tool` de Docker Compose (procedimiento en
[`infra/local/README.md`](../infra/local/README.md)). Detalles en [`docs/seguridad.md`](docs/seguridad.md).

## Configuración

Toda la conexión llega por variables de entorno o secretos; no hay secretos en el repositorio:

| Variable o secreto             | Uso                                                                                                           |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| `CMS_DB_URL`                   | URL JDBC, por ejemplo `jdbc:postgresql://postgres:5432/cms`                                                   |
| `CMS_DB_USER`                  | Login propio del proceso: `cms_api`, `cms_tool`, `cms_security_tool` o `cms_migrator` (nunca el superusuario) |
| `/run/secrets/cms.db.password` | Contraseña, como secreto de Docker (se lee con `configtree`)                                                  |
| `CMS_DB_PASSWORD`              | Alternativa a lo anterior (tests)                                                                             |
| `CMS_OPERATOR`                 | Herramienta de seguridad: quién la ejecuta, para la auditoría (declarado, no verificado)                      |

Perfil `migrate` (`--spring.profiles.active=migrate`): sin servidor web, Flyway activado con
`SET ROLE cms_owner` en sus conexiones; aplica las migraciones pendientes y sale con código 0.

## Imagen Docker

`Dockerfile` en dos etapas con imágenes base de versión y digest fijados
(`eclipse-temurin:21.0.13_9-jdk-noble` para compilar y `21.0.13_9-jre-noble` para ejecutar).
La aplicación se ejecuta con un usuario sin privilegios (`cms`). Los tests no se ejecutan
dentro de la imagen: necesitan Docker y se lanzan con `./mvnw verify`.
