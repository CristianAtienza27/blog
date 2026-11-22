# Informe de la fase 6.3.5: infraestructura de ejecución local

**Cerrada** con dos excepciones documentadas:

- **Simulacro de copias:** aplazado a la fase 6.9 porque `cms_maintenance` no tiene permisos de lectura. Sus privilegios **no se amplían**.
- **Test de medición de la API** (`PublicationBenchmarkTests`): consta como **omitido**, no como superado. Solo se ejecuta con `-Dbench=true`.

Diseño completo en [`infra/docs/ejecucion.md`](../docs/ejecucion.md). Resultados de la integración:
`../blog-bench/resultados/6.3.5-integracion.json`.

## Qué se hizo

- **Imagen de la web** (`infra/web/Dockerfile` y `Dockerfile.dockerignore`): build multietapa sobre
  `node:26.13.0-trixie-slim` fijada por digest.
  - La etapa final solo lleva Node y el build de instantáneas, como usuario `node` (uid 1000).
  - No lleva `node_modules`: el servidor está empaquetado y solo importa módulos internos de Node.
  - Tampoco lleva npm, npx, corepack ni yarn.
- **`infra/local/compose.yaml`:**
  - servicios `web`, `volumenes` y `publication-tool`;
  - redes `datos` y `publicacion` (internas, subred fija), `borde` y `postgres-local`;
  - volúmenes `instantaneas` y `estado`;
  - variables de publicación de la API;
  - endurecimiento de la API, la web y la herramienta: `cap_drop: ALL`, `no-new-privileges`,
    `read_only` y `/tmp` en `tmpfs` con `noexec`;
  - límites (D6 y los de partida de la web y PostgreSQL), tiempos de parada, `init` en la web y
    rotación de registros.
- **`volumenes`** (`infra/local/volumenes/preparar.sh`):
  - root solo durante la preparación, sin red, solo con `CHOWN`;
  - idempotente y no recursivo;
  - termina enseguida.
- **`infra/scripts/comprobar-integracion.mjs`:** 55 comprobaciones en un proyecto Compose aislado
  (`cristian-atienza-cms-e2e`), que borra solo sus propios volúmenes y comprueba que los demás siguen
  intactos.
- Documentación:
  - `infra/docs/ejecucion.md`;
  - `infra/local/README.md` y `.env.example`;
  - la sección 6.3.5 de `api/docs/publicacion.md`.

Sin cambios en V1–V10, en el código de la API o de la web, en `angular.json`, `package*.json` ni en
los secretos.

## Las cuatro precisiones

1. **Volúmenes:** solo las raíces; si ya están bien, no cambia nada.
   - Comprobado: una segunda ejecución deja idénticas las 196 entradas (raíces, versiones y estado).
   - Con una raíz en 0700, solo corrige la raíz.
2. **Copias:** `cms_maintenance` no lee ninguna de las 36 tablas y `pg_dump` falla.
   - Como acordamos, sin recurrir a `cms_migrator`, **el simulacro queda para la 6.9**.
   - El procedimiento previsto, con su consistencia, está en `ejecucion.md` §10:
     - congelar la publicación con su bloqueo asesor;
     - por este orden: `pg_dump`, `estado` e `instantaneas`;
     - validar el puntero, la huella y la época.
   - Decisión pendiente para la 6.9: un rol de copia de solo lectura.
3. **Imagen de la web:** las dependencias están concretadas arriba.
   - El build de la imagen coincide con el local en 247 de 248 archivos; el otro solo cambia el
     orden de dos claves.
   - npm 11.6.2 va fijado solo en la etapa de build, para no tocar el lockfile (§2 de `ejecucion.md`).
4. **Endurecimiento:** sin ninguna capacidad añadida en la API ni en la web.
   - Java y Node funcionan con el sistema de archivos raíz en solo lectura.
   - Cada uno escribe solo en sus volúmenes y en su `/tmp`.

## Pruebas

| Suite                           | Resultado                                               |
| ------------------------------- | ------------------------------------------------------- |
| `comprobar-integracion.mjs`     | 55 de 55                                                |
| `mvnw verify` (API)             | 510 tests, 0 fallos, 0 errores, 1 omitido (la medición) |
| Web (Vitest)                    | 628 de 628                                              |
| ESLint y Prettier               | Sin errores                                             |
| `comprobar-web-instantanea.mjs` | Correcto: 215 archivos idénticos, 20 rutas, sin fugas   |

Las tres primeras ejecuciones de la integración fallaron por **errores del propio script**, no de la
infraestructura:

- leía el campo `active` del puerto interno, que se llama `activa`;
- luego no reconocía la respuesta perdida en su camino diferido.

Se corrigió el script; el sistema no se tocó.

## Mediciones (Docker Desktop en Windows; informativas)

| Medida                                          | Valor                                                     |
| ----------------------------------------------- | --------------------------------------------------------- |
| Arranque desde volúmenes vacíos hasta «healthy» | 24 s (la API, 11 s después de migrar)                     |
| Cambio visible sin build                        | 6,6 s (6,5 s son el arranque de la JVM de la herramienta) |
| Parada de la web con SIGTERM                    | 0,9 s (sin init habría esperado 10 s)                     |
| Memoria en reposo: web / API / PostgreSQL       | 29 / 238 / 34 MiB                                         |
| Pico con 8 clientes (4 sin caché)               | 100 / 238 / 37 MiB                                        |
| SSR sin caché bajo carga, p50 / p95 / p99       | 167 / 2.543 / 7.321 ms                                    |

- **Límite de la web:** el pico de 100 MiB queda muy por debajo de los 512 MiB. Lo mantengo como
  margen para la caché HTML (64 MB) y lo dejo para fijar en la 6.9.
- **Latencias altas en la cola (p95 y p99):** la ventana de carga incluye una publicación (JVM de la
  herramienta más la activación). Es coherente con la excepción de rendimiento de la 6.3.4, pero
  **no lo he verificado aquí**: no se han registrado tiempos para separar las peticiones durante la
  activación. No se ha optimizado nada; el umbral de 5 s sigue pendiente de la 6.9.

## Riesgos y pendientes

- **Copias:** falta un rol de copia de solo lectura y el simulacro completo (6.9).
- **Lockfile:** el npm actual (11.20) lo rechaza. Regenerarlo es un cambio aparte que necesita
  aprobación.
- **Producción (6.9):**
  - `borde` interna y Caddy como único puerto publicado (Docker se salta ufw);
  - `allowedHosts` del dominio real;
  - `forward-headers` en la API;
  - límites definitivos y la activación en menos de 5 s.
- **Usuarios:** en la imagen de la API, el uid 1000 se llama `ubuntu`. No afecta a nada: la API
  monta `estado` en solo lectura.
