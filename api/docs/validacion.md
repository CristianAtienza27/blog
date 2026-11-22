# Validación del contenido de un artículo (fase 6.4.1)

El backend valida un artículo y su cuerpo con **las mismas reglas, en el mismo orden y con los
mismos mensajes** que los validadores TypeScript de la web. Es la barrera al guardar desde el CMS
(desde la 6.4.3). La puerta TypeScript de la publicación (6.3) sigue siendo la última comprobación.

Código: `api/src/main/java/com/cristianatienza/cms/content/validation/`.

| Clase              | Puerto de                                                        | Qué valida                                                                                                                                                                                   |
| ------------------ | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Rules`            | primitivas de `records/validate.ts`                              | `text`, `slug`, `url`, `isoDate`, `oneOf`, `num`, `bool`, `list`, `shape`, `union`, `optional`, con sus mensajes exactos                                                                     |
| `ArticleRules`     | `article`, `block` y `articleBody` de `validate.ts`              | Campos, tipos, patrones, enumerados, rangos y reglas de cada uno de los 10 bloques                                                                                                           |
| `RichTextPolicy`   | `rich-text.ts` (`htmlIssues`, `isAllowedHref`, `isInternalPath`) | Lista blanca de etiquetas, formas canónicas, enlaces y sus destinos                                                                                                                          |
| `ArticleValidator` | la parte de un artículo de `records/integrity.ts`                | Referencias existentes, imágenes retiradas, destinos repetidos, fecha de actualización, capítulos (reservados, prefijo, repetidos, destino), anclas, enlaces a artículos y tiempo de lectura |
| `ReadingTime`      | `records/reading-time.ts`                                        | Palabras y minutos (200 palabras por minuto, mínimo 1)                                                                                                                                       |
| `Js`               | —                                                                | Semántica de JavaScript que Java no tiene igual (ver abajo)                                                                                                                                  |

**Orden, como en `validateContent`:** primero la forma del artículo y la del cuerpo; las relaciones
solo si la forma es correcta.

**Fuera del validador:** las reglas de conjunto de `integrity.ts` siguen en la puerta de publicación,
porque no son de un artículo:

- ids y slugs repetidos;
- orden repetido en una sección;
- imágenes y galerías sin usar;
- autor de las fotos propias;
- secciones exactas.

## Semántica de JavaScript (`Js`)

Traducir las expresiones tal cual daría resultados distintos en los bordes. Por eso:

- **`\s` y `trim()`** usan la lista de espacios de ECMAScript: Zs, NBSP, BOM, U+2028/2029 y los
  controles ASCII. U+0085 y U+180E **no** son espacio en JS, y tampoco aquí.
- **`$`** de JS solo casa al final de la cadena: las expresiones se evalúan con `matches()`. Así
  `"2024-01-01\n"` no es una fecha válida, como en JS.
- **`.`** de JS (sin la bandera `s`) sí casa con U+0085; el de Java no. Las expresiones portadas
  usan la clase explícita `Js.DOT`.
- **`String(valor)`:**
  - números con el formato de `Number::toString` (`1e+21`, `1.5e-7`, `5e-324`; comprobado con
    20.000 doubles aleatorios frente a Node, sin diferencias);
  - listas unidas con comas y objetos como `[object Object]`;
  - una clave ausente es `undefined`.
- **Números JSON** como los ve `JSON.parse`: un double, `Infinity` si no cabe (`1e400`).
- **`Object.keys`:** primero las claves que son índices de lista.
- **`FIGURES_COUNT[layout]`:** la clave se convierte con `String()`, así que `["diptych"]` encuentra
  la de `diptych`, como en JS.
- **Rutas internas:** `new URL(href, ORIGEN).origin` se reproduce con los pasos del analizador
  WHATWG que deciden el origen (recorte de controles, eliminación de tabuladores y saltos, barra
  relativa frente a autoridad), no con una expresión nueva.

## Corpus compartido y paridad

`api/src/test/resources/contract-cases/`:

| Archivo                  | Casos                                                                                 |
| ------------------------ | ------------------------------------------------------------------------------------- |
| `articulos.json`         | 109 sobre artículos reales: forma, bloques e integridad                               |
| `texto-enriquecido.json` | 81 HTML y 49 destinos de enlace                                                       |
| `tiempo-de-lectura.json` | 20                                                                                    |
| `catalogo.json`          | 46 sobre autores, lugares, itinerarios y proyectos reales: forma e integridad (6.4.7) |

- **Java:** `ArticleValidatorParityTests` comprueba los cuatro archivos, los 12 artículos reales
  (sin incidencias y con el tiempo de lectura calculado) y los registros reales del catálogo (sin
  incidencias). El catálogo usa `CatalogRules` (forma, de `validate.ts`) y `CatalogIntegrity`
  (referencias, de `integrity.ts`); cada caso cambia un registro real y compara sus incidencias, con
  el prefijo de la colección normalizado a «registro».
- **TypeScript:** `src/app/content/records/contract-parity.spec.ts` comprueba lo mismo con los
  validadores de la web.
- **Condición:** los dos lados dan **exactamente** las incidencias de `esperado`.
- **Casos nuevos:** se añaden sin `esperado`; se vuelca lo que da TypeScript (la referencia) con
  `CONTRATO_VOLCAR=<archivo> npx ng test --watch=false --include=src/app/content/records/contract-parity.spec.ts`,
  se revisa y se fija.
- **Desfases:** `ArticleRulesTests` vigila las constantes (frente a los archivos TS) y los campos del
  artículo y de cada bloque (frente a `ContentContract`).
- **Seguridad:** `RichTextPolicyTests` es una red independiente del corpus para lo peligroso
  (esquemas, codificaciones, atributos y enlaces).

### Divergencias documentadas (Java es más estricto)

TypeScript consulta el **prototipo de `Object`** en `key in fields` (shape),
`variants[variant]` (union) y `FIGURES_COUNT[layout]`. Hay seis casos en los corpus (cinco en los
artículos y uno en el catálogo, 6.4.7), con su resultado de TS en `esperadoTs`:

| Caso                                             | TypeScript                                                                 | Java                 |
| ------------------------------------------------ | -------------------------------------------------------------------------- | -------------------- |
| Clave `__proto__` o `constructor` en el artículo | Sin incidencias                                                            | `campo desconocido`  |
| Clave `constructor` en un bloque                 | Sin incidencias                                                            | `campo desconocido`  |
| Clave `constructor` en un autor (catálogo)       | Sin incidencias                                                            | `campo desconocido`  |
| Bloque `"type": "constructor"`                   | Sin incidencias (también lo rechaza PostgreSQL)                            | `tipo desconocido`   |
| Composición `"layout": "constructor"`            | **Lanza `TypeError`**: la puerta fallaría con un error, no con incidencias | `valor no permitido` |

Corregirlo en la web es un cambio de `validate.ts` (fuera de esta fase). El backend no deja pasar
ninguna de esas formas.

## Uso en la API (6.4.3)

`ArticleValidation` valida el contenido de un artículo contra el catálogo real (login de la API,
solo lectura) con las rutas del cuerpo de la petición (`content.title`, `content.blocks[2].html`):

- **Errores** (bloquean el guardado, 422): todo lo que el validador rechaza con el catálogo completo.
- **Avisos** (no bloquean): lo que solo falla mirando lo publicado; hoy, los enlaces a artículos que
  existen pero aún no están publicados. La publicación de un artículo (6.4.5) los trata como errores.
- `readingMinutes` lo calcula el servidor con `ReadingTime`; el cliente no lo envía.

Corrección hecha en la 6.4.3: las incidencias de integridad de los bloques usaban siempre la ruta
«articles/<slug>.json» en vez de la ruta de cuerpo recibida. En el corpus de paridad las dos
coinciden, así que no cambia ningún resultado; con las rutas de la API se notaba.

## Catálogo en la API (6.4.7)

`CatalogService` valida cada registro de autores, lugares, itinerarios y proyectos con
`CatalogRules` y `CatalogIntegrity` contra el catálogo real, con rutas sin prefijo (`name`,
`stops[1].destination`). La API añade dos restricciones que el contrato no tiene, las mismas que en
otras escrituras: slug de como mucho 100 caracteres (6.4.3) y coordenadas de como mucho 6 decimales
(`CoordinatePolicy`, porque `NUMERIC(9,6)` redondearía en silencio). Cambiar las paradas de un
itinerario vuelve a validar, con `ArticleValidation` y las paradas nuevas, los artículos que lo usan.

La etiqueta (6.4.6) ya no tiene un límite propio de 100 caracteres en el texto: no existía en
TypeScript ni en la base de datos, y se quitó para que Java y TypeScript acepten lo mismo.
