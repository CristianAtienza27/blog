# API editorial del CMS (fase 6.4)

Contrato **cerrado** del backend del CMS (subfases 6.4.1–6.4.8) para el frontend de la 6.6. Solo
figura lo implementado y probado. Lo que no hace, y por qué, está en
[Restricciones y limitaciones](#restricciones-y-limitaciones-conocidas).

## Endpoints

| Método y ruta                                                                                                                  | Política                                 | CSRF | Subfase |
| ------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------- | ---- | ------- |
| `GET /api/cms/articulos`, `GET /api/cms/articulos/{id}`                                                                        | ADMIN o EDITOR                           | —    | 6.4.2   |
| `GET /api/cms/articulos/{id}/revisiones`, `GET /api/cms/articulos/{id}/revisiones/{numero}`                                    | ADMIN o EDITOR                           | —    | 6.4.2   |
| `GET /api/cms/{catálogo}`: `categorias`, `etiquetas`, `autores`, `lugares`, `itinerarios`, `proyectos`, `imagenes`, `galerias` | ADMIN o EDITOR                           | —    | 6.4.2   |
| `POST /api/cms/articulos`, `POST /api/cms/articulos/validacion`, `POST /api/cms/articulos/{id}/revisiones`                     | ADMIN o EDITOR                           | sí   | 6.4.3   |
| `PUT /api/cms/articulos/{id}/metadatos`                                                                                        | ADMIN o EDITOR; si está publicado, ADMIN | sí   | 6.4.3   |
| `POST /api/cms/articulos/{id}/revisiones/{numero}/restaurar`                                                                   | ADMIN o EDITOR                           | sí   | 6.4.4   |
| `POST /api/cms/articulos/{id}/publicacion`, `POST /api/cms/articulos/{id}/despublicacion`                                      | solo ADMIN                               | sí   | 6.4.5   |
| `PUT /api/cms/secciones/{id}/orden`                                                                                            | solo ADMIN                               | sí   | 6.4.5   |
| `POST /api/cms/etiquetas`, `PUT /api/cms/etiquetas/{id}`                                                                       | ADMIN o EDITOR                           | sí   | 6.4.6   |
| `DELETE /api/cms/etiquetas/{id}`                                                                                               | solo ADMIN                               | sí   | 6.4.6   |
| `GET /api/cms/{colección}/{id}`: `autores`, `lugares`, `itinerarios`, `proyectos`                                              | ADMIN o EDITOR                           | —    | 6.4.7   |
| `POST /api/cms/{colección}`, `PUT /api/cms/{colección}/{id}`                                                                   | ADMIN o EDITOR                           | sí   | 6.4.7   |
| `DELETE /api/cms/{colección}/{id}`                                                                                             | solo ADMIN                               | sí   | 6.4.7   |
| `POST /api/cms/publicaciones`, `POST /api/cms/publicaciones/vN/activar` (publicar la web, [`publicacion.md`](publicacion.md))  | solo ADMIN con passkey reciente          | sí   | 6.3.4   |
| `GET /api/cms/publicaciones`                                                                                                   | solo ADMIN                               | —    | 6.3.4   |

## Autenticación, autorización y CSRF

Lo de la 6.2 ([`seguridad.md`](seguridad.md)), sin cambios:

- **Sesión:** acceso completo (contraseña y passkey del mismo usuario). Sin sesión, 401
  `unauthenticated`; con una sesión a medias (solo contraseña, registro de passkey pendiente…), 403.
- **Roles:** `@CmsEditor` admite ADMIN y EDITOR; `@CmsAdmin`, solo ADMIN (403 `forbidden`). Lo que
  depende del recurso lo decide el servicio: los metadatos de un artículo publicado, solo ADMIN.
- **Passkey reciente** (`@RecentPasskey`, 5 minutos): solo para publicar o activar una instantánea
  (6.3). Marcar o desmarcar un artículo, reordenar y borrar del catálogo no la piden (D3).
- **Cerrado por defecto:** todo endpoint tiene política; `EndpointProtectionTests` recorre la matriz
  entera con estados de sesión reales y falla si aparece uno sin clasificar.
- **CSRF:** todo `POST`, `PUT` y `DELETE` exige la cabecera `X-XSRF-TOKEN` con el valor de la cookie
  `__Host-XSRF-TOKEN`, que emite `GET /api/auth/csrf`. Sin token, 403 `csrf` y sin ningún efecto
  (`CsrfCoverageTests`: 38 rutas que cambian estado, 23 de ellas editoriales).
- **Base de datos:** la API entra como `cms_api` (rol `cms_app`), nunca como superusuario. Su único
  privilegio nuevo en la 6.4 es `EXECUTE` sobre `publish_article` y `unpublish_article` (V11).
- **Respuestas:** JSON con `Cache-Control: no-store` y las cabeceras de la 6.2.5.
- **Nombres:** los campos del contenido usan los del contrato de la web (D7); las rutas y los
  parámetros van en castellano.

## Errores (RFC 9457)

`application/problem+json` con `title`, `status`, `detail`, `code` y `correlationId` (6.2.5). Si hay
incidencias por campo o parámetro, además `errors: [{ "path", "message" }]`.

| Estado  | `code`                                 | Cuándo                                                                                                                                |
| ------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| 400     | `invalid_parameters`                   | Un parámetro de consulta desconocido, repetido o mal formado. `errors` trae **todas** las incidencias                                 |
| 404     | `not_found`                            | El recurso no existe, o su identificador no puede existir (no se distingue)                                                           |
| 401/403 | `unauthenticated`, `forbidden`, `csrf` | Los de la 6.2                                                                                                                         |
| 400     | `bad_request`                          | JSON mal formado, claves duplicadas o basura tras el objeto (6.4.3)                                                                   |
| 403     | `forbidden`                            | El rol no permite la operación sobre ese recurso: metadatos de un publicado siendo EDITOR (6.4.3)                                     |
| 409     | `slug_taken`                           | El slug es el slug o el id de otro artículo (6.4.3), de otra etiqueta (6.4.6) o de otro registro de la colección (6.4.7)              |
| 409     | `id_taken`                             | El id de un autor nuevo ya existe (6.4.7)                                                                                             |
| 409     | `version_conflict`                     | La `version` enviada no es la actual; trae `currentVersion` (6.4.3)                                                                   |
| 409     | `revision_conflict`                    | La revisión base no es la de trabajo; trae `currentRevisionId` (6.4.3)                                                                |
| 409     | `in_use`                               | Borrar una etiqueta (6.4.6) o un registro del catálogo (6.4.7) que se usa, o renombrar un autor que firma fotos propias; trae `usage` |
| 409     | `breaks_articles`                      | Cambiar las paradas de un itinerario dejaría inválidos artículos que lo usan; trae `articles: [{article, revision, errors}]` (6.4.7)  |
| 409     | `published_locked`                     | Cambiar el slug o la sección de un artículo publicado alguna vez (D4, 6.4.3)                                                          |
| 413     | `payload_too_large`                    | Cuerpo de más de 1 MiB (6.4.3)                                                                                                        |
| 415     | `unsupported_media_type`               | El cuerpo no es `application/json` (6.4.3)                                                                                            |
| 422     | `invalid_content`                      | El cuerpo no cumple el contrato; `errors` trae **todas** las incidencias, con su ruta (6.4.3)                                         |

Ningún error lleva datos internos (SQL, trazas, nombres de tablas). Ejemplos:

```json
{
  "title": "…",
  "status": 422,
  "detail": "contenido no válido",
  "code": "invalid_content",
  "correlationId": "…",
  "errors": [
    { "path": "content.blocks[2].html", "message": "…" },
    { "path": "content.tags[0]", "message": "etiqueta inexistente «no-existe»" }
  ]
}
```

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "el registro ha cambiado desde que se leyó",
  "code": "version_conflict",
  "correlationId": "…",
  "currentVersion": "123456"
}
```

Propiedades extra de cada 409: `currentVersion` (`version_conflict`), `conflicts: [{id, currentVersion}]`
(`version_conflict` al reordenar), `currentRevisionId` (`revision_conflict`), `usage` (`in_use`) y
`articles` (`breaks_articles`).

## Lectura (6.4.2)

### `GET /api/cms/articulos`

| Parámetro           | Valores                                                                                          | Por defecto   |
| ------------------- | ------------------------------------------------------------------------------------------------ | ------------- |
| `estado`            | `borrador` · `publicado` · `con-cambios`                                                         | todos         |
| `seccion`           | `viajes` · `fotografia` · `motor` · `experiencias` · `tecnologia`                                | todas         |
| `etiqueta`          | id de una etiqueta (en la revisión de trabajo)                                                   | —             |
| `q`                 | 1–100 caracteres, en el título de la revisión de trabajo o en el slug, sin distinguir mayúsculas | —             |
| `demo`, `destacado` | `true` · `false`                                                                                 | —             |
| `orden`             | `actualizado` (revisión de trabajo más reciente) · `publicado` · `titulo` · `seccion`            | `actualizado` |
| `page`              | 0–10000                                                                                          | 0             |
| `size`              | 1–100                                                                                            | 20            |

- **Estado derivado**, sin columna en la base de datos:
  - `borrador`: sin revisión publicada;
  - `publicado`: la publicada es la de trabajo;
  - `con-cambios`: hay una publicada distinta de la de trabajo.
- **Desempate:** por `id`, así que las páginas son estables. El total y la página salen de la misma
  transacción.

```json
{
  "items": [
    {
      "id": "alicante-desde-el-puerto",
      "slug": "alicante-desde-el-puerto",
      "category": "viajes",
      "state": "publicado",
      "title": "Alicante desde el puerto",
      "format": "nota",
      "author": "cristian-atienza",
      "cover": "viajes-alicante-panorama",
      "tags": ["mediterraneo", "ciudades"],
      "order": 4,
      "featured": false,
      "demo": true,
      "publishedAt": "2026-08-05",
      "updatedAt": null,
      "version": 0,
      "headRevision": {
        "id": 1,
        "number": 1,
        "origin": "import",
        "restoredFrom": null,
        "createdAt": "…Z"
      },
      "publishedRevision": {
        "id": 1,
        "number": 1,
        "origin": "import",
        "restoredFrom": null,
        "createdAt": "…Z"
      }
    }
  ],
  "page": 0,
  "size": 20,
  "total": 12
}
```

- **`version`:** el `lock_version` del artículo (un entero); lo exigen los metadatos, la publicación, la
  despublicación y la reordenación.
- **Ausencias:** un borrador sin publicar tiene `publishedRevision`, `order` y `publishedAt` a `null`.

### `GET /api/cms/articulos/{id}`

`{ "article": <el mismo resumen>, "content": <contenido de la revisión de trabajo>, "validation": { "errors": [...], "warnings": [...] } }`.

`content` es el contrato de la revisión, en el orden de `validate.ts`: `title`, `dek`, `excerpt`,
`tags`, `format`, `cover`, `author`, `readingMinutes`, `trip`, `destinations`, `project`, `seo` y
`blocks`. Un opcional ausente no aparece. Los bloques van en su orden canónico y con los números
exactos.

`validation` (6.4.3, campo añadido; el resto no cambia) es la validación del contenido de trabajo
contra el catálogo **actual**, con las rutas de las escrituras (`content.title`,
`content.blocks[2].html`):

- `errors`: lo que hoy impediría guardarlo. Puede aparecer si el catálogo cambió después de guardar
  (por ejemplo, una imagen retirada).
- `warnings`: lo que solo falla mirando lo publicado. Hoy, los enlaces a artículos que existen pero
  aún no están publicados. No bloquean el guardado; la publicación de un artículo (6.4.5) los trata
  como errores.

### `GET /api/cms/articulos/{id}/revisiones` (`page`, `size`)

Historial de la más reciente a la primera:

```json
{
  "id": 1,
  "number": 1,
  "origin": "import",
  "restoredFrom": null,
  "createdAt": "…Z",
  "head": true,
  "published": true
}
```

- `origin` es `import`, `edit` o `restore`.
- `restoredFrom` es el **número** de la revisión recuperada.

### `GET /api/cms/articulos/{id}/revisiones/{numero}`

`{ "revision": <entrada del historial>, "content": <su contenido> }`. Es de solo lectura: las
revisiones son inmutables.

### Catálogos (para los selectores)

Listas completas en el orden del catálogo, sin parámetros:

| Ruta                       | Campos                                                                                      |
| -------------------------- | ------------------------------------------------------------------------------------------- |
| `GET /api/cms/categorias`  | `id`, `slug`, `name`, `description`                                                         |
| `GET /api/cms/etiquetas`   | `id`, `slug`, `label`; desde la 6.4.6, `version` (token opaco) e `inUse`                    |
| `GET /api/cms/autores`     | `id`, `name`, `role`, `bio`, `avatar`                                                       |
| `GET /api/cms/lugares`     | `id`, `slug`, `name`, `country`, `region`, `cover`, `demo`                                  |
| `GET /api/cms/itinerarios` | `id`, `slug`, `title`, `stops` (destinos en orden), `cover`, `demo`                         |
| `GET /api/cms/proyectos`   | `id`, `slug`, `name`, `status`, `year`, `cover`, `demo`                                     |
| `GET /api/cms/imagenes`    | `id`, `path`, `width`, `height`, `alt`, `retired`, `demo`; **nunca** los datos del original |
| `GET /api/cms/galerias`    | `id`, `slug`, `title`, `layout`, `items` (número de fotos), `demo`                          |

Solo la lista de etiquetas trae `version`. Para editar un autor, lugar, itinerario o proyecto, el
cliente lee antes su detalle (`GET /api/cms/{colección}/{id}`, 6.4.7). Las categorías son de solo
lectura. La biblioteca de imágenes y las galerías, con su paginación y su gestión, son de la 6.5.

## Escrituras (6.4.3)

Requisitos comunes:

- Cuerpo `application/json` de como mucho 1 MiB, sin claves duplicadas.
- CSRF: la cabecera `X-XSRF-TOKEN`.
- Ningún parámetro de consulta.
- Todas las incidencias del cuerpo llegan juntas en un 422.
- La respuesta es el **detalle** del artículo (con `validation`), el mismo de la lectura.
- Cada escritura y su evento de auditoría van en la misma transacción (`content.article_created`,
  `content.revision_created`, `content.revision_restored`, `content.article_metadata_updated`,
  `content.article_published`, `content.article_unpublished`, `content.section_reordered`). El evento lleva el usuario de la
  sesión e ids y números, nunca contenido.

### `POST /api/cms/articulos` → 201 con `Location`

```json
{ "slug": "nuevo-articulo", "category": "viajes", "featured": false, "demo": true, "content": { … } }
```

- Crea el artículo y su revisión 1 (`origin = edit`), en estado `borrador`.
- El slug pasa a ser también el `id`, que es inmutable: minúsculas, números y guiones simples, como
  mucho 100 caracteres, y que no sea el slug ni el id de otro artículo (409 `slug_taken`).
- Sin orden ni fecha: los fija la publicación (6.4.5).

### `content` (crear y guardar)

- **Campos:** `title`, `dek`, `excerpt`, `tags`, `format`, `cover`, `author`, `trip`,
  `destinations`, `project`, `seo` y `blocks`.
- **`readingMinutes` lo calcula el servidor:** enviarlo es un error (`content.readingMinutes`).
  Enviar un metadato (`slug`, `category`…) también lo es.
- **Reglas:** se valida con las de la web (6.4.1, `api/docs/validacion.md`) y el catálogo real. Las
  cinco divergencias documentadas del artículo (claves del prototipo de `Object`) se rechazan.

### `POST /api/cms/articulos/{id}/revisiones` → 201 o 200

```json
{ "baseRevisionId": 123, "content": { … } }
```

- **201:** crea la revisión siguiente a partir de `baseRevisionId`, que debe ser la de trabajo (si no,
  409 `revision_conflict` con `currentRevisionId`; nunca hay ramas). EDITOR también puede hacerlo en
  un artículo publicado: la revisión publicada no cambia hasta publicar.
- **200:** si el contenido es el mismo de la revisión de trabajo, no se crea ninguna revisión.

### `POST /api/cms/articulos/{id}/revisiones/{numero}/restaurar` → 201 o 200 (6.4.4)

```json
{ "baseRevisionId": 123 }
```

- **201:** recupera el contenido de la revisión `numero` como una revisión **nueva** (N+1,
  `origin = restore`, `restoredFrom = numero`). La revisión recuperada y el resto del historial no
  cambian: son inmutables.
- **Base:** `baseRevisionId` debe ser la de trabajo; si no, 409 `revision_conflict` con
  `currentRevisionId`.
- **Catálogo:** el contenido recuperado se valida con el catálogo **actual**. Si usa algo que hoy no
  vale (una imagen retirada después, por ejemplo), 422 `invalid_content` con sus rutas y no se crea
  nada.
- **200:** si el contenido es el mismo que el de la revisión de trabajo, no se crea ninguna revisión.
- **404:** si el artículo o la revisión no existen.
- **Publicación:** la revisión publicada no cambia. El estado se deriva de los punteros, así que
  restaurar la revisión publicada en una nueva deja el artículo en `con-cambios` hasta publicar
  (6.4.5).
- **Auditoría:** `content.revision_restored` con `article`, `revision`, `restoredFrom` y `base`, en la
  misma transacción.

### `PUT /api/cms/articulos/{id}/metadatos` → 200

```json
{
  "version": 0,
  "slug": "…",
  "category": "…",
  "featured": false,
  "demo": true,
  "publishedAt": null,
  "updatedAt": null
}
```

Todas las claves son obligatorias (las fechas pueden ser `null`).

| Regla                                                                                                       | Error                  |
| ----------------------------------------------------------------------------------------------------------- | ---------------------- |
| `version` debe ser la actual; sube en 1 si algo cambia (sin cambios: 200 y la misma versión)                | 409 `version_conflict` |
| Artículo publicado (con revisión publicada): solo ADMIN                                                     | 403 `forbidden`        |
| Publicado alguna vez (`publishedAt`): el slug y la sección no cambian (D4)                                  | 409 `published_locked` |
| `publishedAt`: null en un artículo que nunca se publicó (la fija la publicación); obligatoria si se publicó | 422                    |
| `updatedAt`: null, o una fecha igual o posterior a `publishedAt`                                            | 422                    |
| Slug nuevo ocupado                                                                                          | 409 `slug_taken`       |

El `id` no cambia nunca. El orden editorial lo gestionan la publicación y la reordenación (6.4.5).

### `POST /api/cms/articulos/validacion` → 200

Valida sin guardar nada:

- `{ "content": { … }, "article": "<id>" }` para un artículo existente;
- `{ "content": { … }, "slug": "…", "category": "…" }` para uno nuevo.

Responde `{ "errors": [...], "warnings": [...], "readingMinutes": n }`, con 200 aunque haya errores,
porque es una comprobación.

## Publicación de artículos y orden (6.4.5)

Solo **ADMIN** (`@CmsAdmin`, D3), sin verificación reciente. Esto decide qué entra en la
**siguiente** instantánea; publicar la web sigue siendo `POST /api/cms/publicaciones` (6.3: ADMIN
con passkey reciente). Las operaciones de la base de datos son `publish_article` y
`unpublish_article` (V11, `modelo-de-datos.md`).

### `POST /api/cms/articulos/{id}/publicacion` → 200

```json
{ "revisionId": 123, "version": 4 }
```

- **Qué publica:** la revisión de **trabajo** pasa a ser la publicada.
- **Comprobaciones:**
  - `version`: si no es la actual, 409 `version_conflict` con `currentVersion`;
  - la revisión tiene que ser del artículo (si no, 422 en `revisionId`);
  - y su revisión de trabajo (si no, 409 `revision_conflict` con `currentRevisionId`).
- **El contenido tiene que pasar la validación con lo publicado:** los **avisos** de guardar
  (enlaces a artículos sin publicar) aquí son **errores**: 422 `invalid_content`.
- **La primera vez**, `publishedAt` es la fecha de hoy en Europe/Madrid (la real, la fija la base de
  datos) y, si no tiene orden, el siguiente de su sección. Republicar conserva los dos.
- **Sin cambios:** si esa revisión ya era la publicada, 200, sin versión nueva ni auditoría.

### `POST /api/cms/articulos/{id}/despublicacion` → 200

`{ "version": 4 }`.

- El artículo queda sin revisión publicada (estado `borrador`) y sale de la siguiente instantánea.
- Conserva `publishedAt` (el slug y la sección siguen congelados, D4) y su orden.
- Si no estaba publicado, 200 sin cambios.

### `PUT /api/cms/secciones/{id}/orden` → 200

```json
{
  "articles": [
    { "id": "a", "version": 2 },
    { "id": "b", "version": 0 }
  ]
}
```

- **La lista:** la completa de los artículos **con orden** de la sección (publicados o publicados
  alguna vez; los borradores no tienen), en el orden nuevo. Se renumera de 1 a n.
- **Errores:**
  - si sobra, falta o se repite alguno, 422;
  - si alguna versión no es la actual, 409 `version_conflict` con `conflicts: [{id, currentVersion}]`;
  - una sección inexistente da 404.
- **Versiones:** sube la `version` de cada artículo que cambia de puesto.
- **Respuesta:** los artículos de la sección (`orden=seccion`).

## Etiquetas (6.4.6)

- **Reglas:** la forma es la de `tag` en `validate.ts`: `slug` con las reglas de los slugs (como
  mucho 100 caracteres) y `label`, un texto no vacío. Desde la 6.4.7 el texto **no** tiene más límite
  de longitud que el del contrato (`text()` de `validate.ts` y el dominio `d_text`): el límite de 100
  caracteres que tenía en la 6.4.6 no existía en TypeScript y se quitó para no divergir.
- **Identidad:** el `id` es el slug inicial y **no cambia** (los artículos y proyectos la
  referencian por id). El slug y el texto sí se editan, porque ninguna URL pública usa el slug.
- **Slug único:** no puede ser el slug ni el id de otra etiqueta (409 `slug_taken`).
- **`version`:** es `xmin` de PostgreSQL, usado **solo como token opaco** (D8). Se envía tal cual
  como texto; no es un número ni un contador. Si no es la actual, 409 `version_conflict` con
  `currentVersion`.

| Endpoint                         | Política       | Cuerpo                   | Respuesta                                           |
| -------------------------------- | -------------- | ------------------------ | --------------------------------------------------- |
| `POST /api/cms/etiquetas`        | ADMIN o EDITOR | `{slug, label}`          | 201 con `Location` y la etiqueta                    |
| `PUT /api/cms/etiquetas/{id}`    | ADMIN o EDITOR | `{version, slug, label}` | 200 con la etiqueta (sin cambios: la misma versión) |
| `DELETE /api/cms/etiquetas/{id}` | solo ADMIN     | `{version}`              | 204                                                 |

- **Borrar:** una etiqueta en uso no se borra (409 `in_use` con `usage`). Cuenta su uso en
  **cualquier** revisión de un artículo, también las antiguas (que son inmutables), o en un proyecto.
  La clave foránea RESTRICT es la última barrera.
- **Auditoría:** `content.tag_created`, `content.tag_updated` (con los campos cambiados) y
  `content.tag_deleted`, en la misma transacción.
- **Base de datos:** sin migración. `cms_app` ya tiene `INSERT`, `UPDATE` y `DELETE` en `tag` desde V3.

## Autores, lugares, itinerarios y proyectos (6.4.7)

Las mismas comprobaciones que las etiquetas, para cada colección. Rutas explícitas, una por colección
(`autores`, `lugares`, `itinerarios`, `proyectos`):

| Endpoint                           | Política       | Cuerpo                                     | Respuesta                                          |
| ---------------------------------- | -------------- | ------------------------------------------ | -------------------------------------------------- |
| `GET /api/cms/{colección}/{id}`    | ADMIN o EDITOR | —                                          | 200 `{record, version, usage}`                     |
| `POST /api/cms/{colección}`        | ADMIN o EDITOR | el registro del contrato (ver «Identidad») | 201 con `Location` y el detalle                    |
| `PUT /api/cms/{colección}/{id}`    | ADMIN o EDITOR | `{version, …registro sin id}`              | 200 con el detalle (sin cambios: la misma versión) |
| `DELETE /api/cms/{colección}/{id}` | solo ADMIN     | `{version}`                                | 204                                                |

- **`record`:** el registro **exacto** del contrato de contenido (`ContentContract`), el mismo que
  exporta la instantánea (6.3): los campos opcionales vacíos no aparecen y las paradas y la pila van
  en orden. Un PUT envía el registro **completo** (sin `id`); lo que falte se borra si es opcional o
  es un error si es obligatorio.
- **Reglas:** las de `validate.ts` (forma de cada colección) e `integrity.ts` (referencias), portadas
  en `CatalogRules` y `CatalogIntegrity` y comprobadas con el corpus compartido
  `contract-cases/catalogo.json` (ver `validacion.md`). Además, como en las escrituras de artículos,
  el slug tiene como mucho 100 caracteres, y las coordenadas de un lugar como mucho **6 decimales**
  (`NUMERIC(9,6)` redondearía en silencio).
  - **Autor:** `{id, name, role, bio?, avatar?}`; `avatar`, una imagen existente y no retirada.
  - **Lugar:** `{slug, name, country, region?, location: {lat, lng}, elevation?, summary, cover?,
demo}`. `country`, ISO de 2 letras; `elevation`, un entero entre −500 y 9000.
  - **Itinerario:** `{slug, title, summary, stops, cover?, demo}`. `stops`, al menos 2, cada una
    `{order, destination, note?, distanceKm?, mode?}`: `order` de 1 a n sin huecos, `distanceKm` ≥ 0 y
    `mode` uno de `coche`, `moto`, `tren`, `barco` o `a-pie`.
  - **Proyecto:** `{slug, name, summary, status, year, stack, repositoryUrl?, cover?, demo}`.
    `status`, `activo`, `pausado` o `archivado`; `year`, de 1990 a 2100; `stack`, ids de etiquetas.
- **Identidad:** el `id` **no cambia**. En lugares, itinerarios y proyectos es el slug inicial (no se
  envía; 422 si se envía) y el slug se edita después. En autores no hay slug: el `id` se elige al crear
  (409 `id_taken` si existe).
- **`version`:** `xmin` de la fila principal como **token opaco** (D8), igual que en las etiquetas.
  Cada edición con cambios actualiza la fila principal aunque solo cambien las paradas o la pila, para
  que la versión cambie siempre. Versión vieja: 409 `version_conflict` con `currentVersion`.
- **Lo que una edición podría romper:**
  - Renombrar a un autor que firma fotos **propias** (`credit.source = propia`, cuyo `credit.author`
    debe ser un autor) es 409 `in_use` con `usage.media`. Hoy no hay ninguna.
  - Cambiar las paradas de un itinerario comprueba, con las paradas nuevas, la revisión de trabajo y la
    publicada de cada artículo que lo usa (`trip`): si alguna deja de ser válida, 409
    `breaks_articles` con los artículos y sus incidencias, y no se guarda nada.
- **Borrar:** lo que se usa no se borra (409 `in_use` con `usage`): autor, por artículos (en cualquier
  revisión) y fotos propias; lugar, por artículos e itinerarios; itinerario y proyecto, por artículos.
  Las paradas y la pila de un proyecto se borran con él. Las claves foráneas RESTRICT son la última
  barrera.
- **Concurrencia:** cerrojo consultivo del catálogo (objeto 67) y la fila bloqueada (`FOR UPDATE`);
  dos PUT con la misma versión: uno 200 y otro 409.
- **Auditoría:** `content.{author|destination|trip|project}_{created|updated|deleted}` (la edición, con
  los campos cambiados), en la misma transacción.
- **Base de datos:** sin migración. `cms_app` ya tiene `INSERT`, `UPDATE` y `DELETE` en `author`,
  `destination`, `trip`, `trip_stop`, `project` y `project_stack_item` desde V3.

Detalle (`GET /api/cms/lugares/alicante`):

```json
{
  "record": {
    "id": "alicante",
    "slug": "alicante",
    "name": "Alicante",
    "country": "ES",
    "region": "Comunidad Valenciana",
    "location": { "lat": 38.3452, "lng": -0.481 },
    "summary": "Ciudad portuaria del Mediterráneo extendida a los pies del monte Benacantil.",
    "cover": "viajes-alicante-panorama",
    "demo": true
  },
  "version": "123456",
  "usage": { "articles": 1, "trips": 0 }
}
```

Editar (`PUT /api/cms/lugares/alicante`): el mismo registro sin `id`, con la `version` leída:

```json
{
  "version": "123456",
  "slug": "alicante",
  "name": "Alicante",
  "country": "ES",
  "region": "Comunidad Valenciana",
  "location": { "lat": 38.3452, "lng": -0.481 },
  "summary": "Otro resumen.",
  "cover": "viajes-alicante-panorama",
  "demo": true
}
```

`usage` por colección: autores, `{articles, media}`; lugares, `{articles, trips}`; itinerarios y
proyectos, `{articles}`. Un 409 `breaks_articles` trae, además del problema común:

```json
{
  "code": "breaks_articles",
  "articles": [
    {
      "article": "itinerario-costa-brava",
      "revision": 1,
      "errors": [
        {
          "path": "content.blocks[…]",
          "message": "«begur» no es una parada ni un destino del artículo"
        }
      ]
    }
  ]
}
```

## Concurrencia y versiones

| Recurso                                       | Token                                     | Si no es el actual                                          |
| --------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------- |
| Artículo (metadatos, publicación, orden)      | `version` = `lock_version` (entero)       | 409 `version_conflict` con `currentVersion` (o `conflicts`) |
| Contenido (guardar, restaurar, publicar)      | `baseRevisionId` / `revisionId`           | 409 `revision_conflict` con `currentRevisionId`             |
| Etiqueta, autor, lugar, itinerario y proyecto | `version` = `xmin` como texto (opaco, D8) | 409 `version_conflict` con `currentVersion`                 |

- **Tokens en el cuerpo** (D8), no en `ETag` ni `If-Match`. Se devuelven tal cual.
- **`xmin` es solo un token de comparación:** texto que se compara por igualdad; nunca se suma, se
  ordena ni se interpreta. Cambia con cualquier edición real; una edición sin cambios lo conserva.
- **Siempre en la base de datos:** fila bloqueada (`FOR UPDATE`) y comprobación en la misma
  transacción que la escritura, con cerrojos consultivos donde hay altas o renumeraciones (clase
  `0x434D53`: 64 slugs de artículos, 65 orden, 66 etiquetas, 67 catálogo). Nunca hay escrituras a
  medias: un 409 o un 422 no deja ningún cambio ni auditoría.
- **Historial lineal:** guardar o restaurar desde una base que no es la de trabajo es 409; nunca hay
  ramas ni sobrescrituras.

## Del CMS a la web

Publicar es **dos pasos**, y solo el segundo cambia la web:

1. **Marcar** la revisión publicada de un artículo (`…/publicacion` o `…/despublicacion`, ADMIN). Solo
   mueve el puntero `published_revision_id` (V11). La web no cambia.
2. **Publicar la web** (`POST /api/cms/publicaciones`, ADMIN con passkey reciente, 6.3): genera una
   instantánea con lo marcado en ese momento, la pasa por la puerta y la activa en la web.

- Lo que entra en una instantánea es exactamente lo marcado: una revisión de trabajo sin marcar no
  entra (publicar de nuevo da 200 `sin cambios`), y un artículo desmarcado sale.
- La gestión editorial nunca llama a la publicación de la web; están separadas (F del alcance).
- `CmsToWebEndToEndTests` lo recorre entero con la web real (el servidor Node del build de
  instantáneas): crear, guardar, marcar, publicar v2, que la web sirva la página y el JSON del
  artículo, despublicar, publicar v3 y que la página dé 404. Comprueba también que marcar no cambia
  la web, que la publicación queda conciliada y que una instantánea rechazada no cambia nada.

## Restricciones y limitaciones conocidas

**Restricciones de la API aprobadas** (más estrictas que el contrato TypeScript, a propósito):

- **Slug: como mucho 100 caracteres** (6.4.3), en artículos, etiquetas, lugares, itinerarios y
  proyectos, y en los ids de autor.
- **Coordenadas: como mucho 6 decimales** (`CoordinatePolicy`, del modelo de la 6.1): la columna
  `NUMERIC(9,6)` redondearía en silencio un valor más preciso.
- **Cuerpo:** como mucho 1 MiB, `application/json`, sin claves duplicadas ni basura tras el objeto.
- **Seis divergencias** con `validate.ts` por las claves del prototipo de `Object` (cinco en el
  artículo y una en el catálogo): Java las rechaza ([`validacion.md`](validacion.md)).
- El texto de una etiqueta **no** tiene más límite que el del contrato (se quitó el de 100 en la 6.4.7).

**Limitaciones aceptadas en el plan de la 6.4:**

- Las reglas de conjunto (imágenes y galerías sin usar, orden repetido…) solo las aplica la puerta de
  la 6.3, sobre la instantánea entera, que lleva todas las imágenes y galerías y solo los artículos
  publicados. Por eso **despublicar el único artículo que usa una imagen o una galería hace que la
  siguiente instantánea se rechace** (422 `rechazada`, «imagen sin usar»), sin cambiar la web. Con el
  contenido real: `hora-azul-notas-de-exposicion` y `plantilla-de-relato`. Hasta poder retirar
  imágenes (6.5), la salida es volver a marcarlo. Ver [`publicacion.md`](publicacion.md).
- Los metadatos de un artículo publicado (destacado, demo, fechas) no se versionan: entran en la
  siguiente instantánea. Por eso son solo de ADMIN.
- El historial no dice quién hizo cada revisión: eso está en la auditoría, que `cms_app` no lee.
- `breaks_articles` comprueba la revisión de trabajo y la publicada. Una revisión antigua que deje de
  valer se rechaza al restaurarla (422).
- Tras despublicar y publicar la web, la versión **anterior** de la instantánea se sigue sirviendo
  por su ruta `/content/vN/…` (6.3, Q1: activa y anterior), aunque la página del artículo ya da 404.
- Las listas del catálogo no se paginan.

**Aplazado (no es de la 6.4):** imágenes y galerías, con su gestión y paginación (6.5); el frontend y
la comprobación manual en Chromium (6.6); programar publicaciones, el estado propio de despublicado,
el borrado lógico de artículos, `slug_redirect`, la previsualización y la comparación de revisiones
(6.7, D9); el simulacro de copia de seguridad (6.9).

## Pruebas

| Área                                        | Pruebas                                                                                              |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Validación y paridad con TypeScript         | `ArticleValidatorParityTests`, `RichTextPolicyTests`, `CatalogRulesTests`, `contract-parity.spec.ts` |
| Lectura                                     | `EditorialReadEndpointTests`                                                                         |
| Escrituras, revisiones, publicación y orden | `EditorialWriteEndpointTests`, `ArticlePublishingSchemaTests` (V11)                                  |
| Etiquetas y catálogo                        | `TagEndpointTests`, `CatalogEntityEndpointTests`                                                     |
| Roles y CSRF                                | `EndpointProtectionTests`, `CsrfCoverageTests`                                                       |
| Del CMS a la web real                       | `CmsToWebEndToEndTests` (se omite si falta el build de instantáneas)                                 |
| Exportación tras editar                     | `EditorialExportRoundTripTests`                                                                      |
