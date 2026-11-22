# Informe de cierre de la fase 6.3.4: servicio de publicación

Cerrada con una excepción explícita de rendimiento (abajo). Diseño completo en
[`api/docs/publicacion.md`](../../api/docs/publicacion.md).

## Qué se hizo

- **API (`content/publication/`):** publicar (P1–P4), activar o volver atrás, reconciliar con
  `estado/activa.json`, listar y purgar; bloqueo asesor con heartbeat y conexión propia; fencing con
  una época monotónica en PostgreSQL; endpoints `/api/cms/publicaciones` (ADMIN, passkey reciente y
  CSRF); herramientas `publicar`, `activar` y `purgar-instantaneas` con el login de la API; auditoría
  en la transacción del cambio de estado.
- **V10:** `content_publication` (matriz de transiciones en un trigger, una sola activa, índices de
  las claves foráneas), `content_publication_lease` (la época, solo crece) y
  `content_publication_check` (resultado de la reconciliación). Permisos mínimos para `cms_app`,
  comprobados uno a uno en `PublicationSchemaTests`. V1–V9 sin cambios.
- **Web (solo el puerto interno):** reservar la época, activar solo con la época reservada,
  `estado/control.json`, estado bloqueado y recuperación en dos pasos con un nonce de un solo uso.

## Pruebas

| Suite                           | Resultado                                                                                              |
| ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `mvnw verify` (API)             | 510 tests, 0 fallos, 0 errores, 1 omitido (la medición, solo con `-Dbench=true`)                       |
| Web (Vitest)                    | 628 de 628                                                                                             |
| ESLint y Prettier               | Sin errores; todo el repositorio formateado                                                            |
| `comprobar-web-instantanea.mjs` | Correcto: 215 archivos del build convencional idénticos a la referencia, 20 rutas idénticas, sin fugas |

Los 9 fallos de la primera ejecución completa: 1 defecto real (dos índices que faltaban en V10), 2
huecos de cobertura de seguridad (CSRF y matriz de autorización de los endpoints nuevos) y 6
expectativas desactualizadas por cambios aprobados (V10 como última migración y R6).

## Rendimiento: excepción aprobada

Comparación controlada de la activación (mismos builds alternos, misma máquina, misma carga de 8
clientes con la mitad sin caché). Resultados: `../blog-bench/resultados/6.3.4-comparacion-activacion.json`
y `6.3.4-publicacion-completa.json`.

| ms (p50 / p95 / p99)            | 6.3.3                  | 6.3.4                    |
| ------------------------------- | ---------------------- | ------------------------ |
| SSR sin carga                   | 73 / 91 / 105          | 70 / 86 / 99             |
| Activación sin carga            | 109 / 141 / 148        | 122 / 143 / 154          |
| Reserva de época bajo carga     | —                      | 438 / 523 / 547          |
| Activación bajo carga           | 9.313 / 9.909 / 10.133 | 9.072 / 9.904 / 9.927    |
| Publicación completa sin carga  | —                      | 678 / 788 / 788          |
| Publicación completa bajo carga | —                      | 10.658 / 11.955 / 11.955 |

La 6.3.4 no es una regresión: con el mismo entorno, las dos versiones tardan lo mismo; el build de
la 6.3.3 que en su sesión daba 4,4–4,6 s da ahora unos 9,3 s. **El criterio de activación en menos
de 5 s queda incumplido en el entorno local actual; el umbral se mantiene** y queda pendiente de
validación en la infraestructura real de la **fase 6.9** (el VPS y sus límites definitivos), con la
misma comparación. No se ha optimizado nada.

## Identidad en la auditoría de la publicación (confirmado en el código)

- **HTTP:** `Actor.api(AuthSessions.user(authentication).id())`: el usuario de la sesión
  autenticada en el servidor, nunca un dato del cliente.
- **Herramientas:** `Actor.tool(session_user de PostgreSQL, nombre de la máquina)`. La identidad
  autenticada y verificable es el login de PostgreSQL (el rol de la API, no una persona;
  `requested_by` queda vacío). La máquina es solo contexto (no autenticado, no usado como identidad).
  `PublicationTool` solo lee `cms.tool`, `cms.version`, `cms.confirmar` y `cms.tool.system-exit`:
  no hay ningún parámetro ni variable de operador.
- **Deuda independiente de la 6.2.6:** `SecurityTool`/`ToolAudit` siguen registrando el operador
  declarado en `CMS_OPERATOR`. La publicación no usa `ToolAudit` ni lee `CMS_OPERATOR`; queda
  documentada en `api/docs/seguridad.md` para una fase posterior.

## Riesgos pendientes

- Activación bajo saturación por encima de 5 s en local (validar en la 6.9).
- La recarga ante una versión retirada vuelve a la página actual (mejora no bloqueante).
- Si se pierde el volumen de estado de la web, reiniciarla antes de reactivar (documentado).
- La deuda de identidad de la herramienta de seguridad de la 6.2.6 (independiente).
