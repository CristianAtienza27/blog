# Importación y exportación del contenido (fase 6.1.3)

Herramienta de línea de comandos que carga `src/content` en PostgreSQL y lo devuelve al mismo
contrato JSON. No hay endpoints HTTP de contenido ni autenticación. La web pública no cambia:
sigue leyendo `src/content`, que la herramienta solo lee.

## Operaciones

| Operación | Qué hace                                                                                             | Salida                 |
| --------- | ---------------------------------------------------------------------------------------------------- | ---------------------- |
| `import`  | Importación inicial de las 9 colecciones y los 12 cuerpos, en una sola transacción                   | 0, 2 (diferencias) o 1 |
| `export`  | Reconstruye el contrato desde la base de datos y lo escribe fuera del repositorio, en orden canónico | 0 o 1                  |
| `compare` | Comparación semántica de `src/content` con una exportación                                           | 0, 2 o 1               |

En local se lanzan con el servicio `content-tool` de Docker Compose (ver
[`infra/local/README.md`](../../infra/local/README.md)).

Las instantáneas publicadas de la fase 6.3 (`generar-instantanea`) reutilizan el lector y el formato
de la exportación: ver [`publicacion.md`](publicacion.md).

## Contrato (`ContentContract`)

- **Fuente:** las formas de `src/app/content/records/validate.ts` y `records.ts`, copiadas campo a campo, en el mismo orden: qué campos existen, cuáles son obligatorios, su tipo y los diez tipos de bloque.
- **Null:** cada campo declara si admite `null`. Hoy el contrato TypeScript no lo admite en ninguno, así que se rechaza en todos, con su ruta (`media[0].caption: null no admitido por el contrato`). Si el contrato lo admitiera en un campo, bastaría con marcarlo en ese campo.
- **Sincronía:** `ContentContractTests` analiza `validate.ts` y falla si las dos definiciones divergen (un campo de más o de menos, o un cambio de obligatoriedad).

## Importación

**1. Comprobación previa, sin conectar:**

- el contrato completo;
- cada cuerpo con su artículo, y al revés;
- claves JSON duplicadas;
- fechas `AAAA-MM-DD`;
- coordenadas según `CoordinatePolicy`: 6 decimales en lugares y 2 en la ubicación pública de una foto (PostgreSQL redondearía en silencio).

**2. Usos en el código (`CodeUsages`), con lectura estricta:**

- `RETIRED_MEDIA` de `src/app/editorial/retired-media.ts` (4 imágenes);
- `SECTIONS[].preview` de `src/app/layout/navigation.ts` (5 secciones, en orden).

Si el formato de esos archivos cambia, la lectura falla en lugar de adivinar. Toda imagen retirada o de vista previa debe existir, y ninguna vista previa puede estar retirada.

**3. Una transacción** con `SET LOCAL ROLE cms_importer`:

| Estado de la base de datos | Resultado                                                                                                    |
| -------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Vacía                      | Inserta el catálogo, luego los artículos con `import_published_article`, luego los usos reservados. Confirma |
| Con contenido              | Exporta lo que hay, lo compara con el origen y revierte: **sin cambios** (`0`) o **diferencias** (`2`)       |

- No sincroniza: si el origen ha cambiado, lista las diferencias y no escribe nada.
- La segunda importación lo comprueba con `pg_current_xact_id_if_assigned()`: la transacción no llega a tener identificador de escritura.
- Cualquier error revierte la transacción entera: no quedan filas en ninguna de las 18 tablas.
- La herramienta comprueba además que `src/content` no cambió durante la importación (SHA-256 de cada archivo).

### Artículos: `import_published_article`

Para cada artículo, la operación hace en la misma transacción:

1. Valida el registro: claves conocidas, obligatorias, sin null y tipos.
2. Crea el artículo.
3. Crea la revisión 1 (`origin = 'import'`) con el núcleo de revisiones de la 6.1.2: validación de bloques, referencias y política de imágenes retiradas.
4. Mueve `head_revision_id` y `published_revision_id` a esa revisión.

Si el artículo ya existe, falla: la importación inicial no actualiza artículos.

## Roles y permisos (migración V4)

| Rol                 | Tipo                                                                 | Puede                                                                                                                                                                                       |
| ------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cms_importer`      | NOLOGIN, sin superusuario                                            | `SELECT` en las 18 tablas; `INSERT` en las 13 del catálogo; `EXECUTE` en `import_published_article` y `article_revision_content`. Nada más: ni `UPDATE`, ni `DELETE`, ni tablas de revisión |
| `cms_import_writer` | NOLOGIN, sin superusuario; propietario de `import_published_article` | `SELECT, INSERT` en `article`; `UPDATE (published_revision_id)`; `EXECUTE` en el núcleo de revisiones. Nadie es miembro de este rol                                                         |

**`import_published_article`:**

- `SECURITY DEFINER`, con `search_path = pg_catalog, public, pg_temp`;
- `EXECUTE` revocado a `PUBLIC`, concedido solo a `cms_importer`.

### El puntero publicado

Lo protege la **identidad**, no una variable de sesión. El trigger `article_pointers_guard` (en `INSERT` y en `UPDATE` de los dos punteros):

| Situación                         | Regla                                                                                                                      |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Artículo nuevo                    | Nace sin punteros                                                                                                          |
| Cambio de `head_revision_id`      | Solo si `current_user = 'cms_revision_writer'`, es decir, dentro de `create_article_revision` o `restore_article_revision` |
| Cambio de `published_revision_id` | Solo si `current_user = 'cms_import_writer'`, es decir, dentro de `import_published_article`                               |

Así no pueden mover el puntero publicado:

- `cms_app`;
- `cms_importer` fuera de la operación;
- `PUBLIC`;
- el propietario de las tablas, aunque fije cualquier variable de sesión.

`cms_importer` tampoco puede hacer `SET ROLE cms_import_writer`, porque no es miembro.

### Pruebas con usuarios reales

Los tests de permisos se conectan con usuarios de base de datos **reales**, con contraseña y sin superusuario. No usan `SET ROLE` desde el superusuario:

| Usuario              | Miembro de          |
| -------------------- | ------------------- |
| `cms_importer_login` | solo `cms_importer` |
| `cms_app_login`      | solo `cms_app`      |
| `cms_nobody_login`   | ninguno             |

La pertenencia se verifica en `pg_auth_members`.

## Exportación

- **Lectura:** en una transacción de solo lectura con `cms_importer`. Los artículos salen de `article_revision_content(published_revision_id)` más los campos del artículo.
- **Orden canónico:**
  - claves, en el orden del contrato;
  - colecciones, por `catalog_position`;
  - listas, por su posición.
- **Formato:** JSON con 2 espacios, `"clave": valor`, finales de línea LF y salto final. Los números salen en su valor decimal exacto, sin ceros sobrantes (`41.7197`, `42`). Dos exportaciones seguidas son idénticas byte a byte.

**Destino seguro:**

- Se rechaza cualquier carpeta dentro de un repositorio Git (con un `.git` en ella o en un ancestro).
- La carpeta debe estar vacía o contener una exportación anterior, reconocible por el marcador `.cms-export`. En ese caso solo se borran los archivos conocidos; si hay archivos ajenos, no se toca nada.
- Con Docker Compose, el destino es un montaje: dentro del contenedor no se ve el repositorio, así que `CMS_EXPORT_DIR` debe apuntar fuera de él (por defecto, la carpeta hermana `blog-export`).

## Comparación semántica

| Aspecto                      | Criterio                                                                                 |
| ---------------------------- | ---------------------------------------------------------------------------------------- |
| Orden de las claves          | No importa                                                                               |
| Orden de las listas          | Sí importa                                                                               |
| Números                      | Por su valor decimal exacto: `41.7197` = `41.719700`, pero `0.1` ≠ `0.10000000000000001` |
| Ausente, `null`, `{}` y `[]` | Son distintos                                                                            |
| Textos                       | Idénticos carácter a carácter                                                            |
| Archivos de más o de menos   | Son diferencias                                                                          |

## Garantías comprobadas por los tests

Contra PostgreSQL 18.6 real (Testcontainers):

- **`ContentRoundTripTests`:**
  - contenido real importado y exportado con 0 diferencias;
  - dos exportaciones idénticas byte a byte;
  - origen sin cambios (SHA-256);
  - segunda importación sin escrituras;
  - 4 imágenes retiradas y 5 vistas previas;
  - 12 artículos con una sola revisión importada, de trabajo y publicada;
  - `demo` registro a registro;
  - contrato completo sintético con 0 diferencias.
- **`ContentImporterTests`:**
  - origen modificado detectado sin escribir;
  - reversión completa ante un error en el último artículo o en el catálogo;
  - rechazos previos (null, campos desconocidos u obligatorios, cuerpos sin artículo, decimales, fechas, claves duplicadas, vistas previas);
  - sin pertenecer a `cms_importer` no se importa nada.
- **`ImportOperationTests`:**
  - la operación;
  - todos los intentos de saltarse los permisos, con usuarios reales;
  - los atributos de seguridad de la función.
- **`ContentContractTests`, `CodeUsagesTests`, `ContentComparatorTests`, `ContentExporterTests`:** las piezas por separado.
