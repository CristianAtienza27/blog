# Informe de la fase 6.1.4: recursos y capacidad del stack

Fecha: 13–14 de noviembre de 2026. Base: `main` en `56b968d`, con `api/` e `infra/` sin commit.

> **Alcance de las conclusiones.** Son **mediciones locales en Docker Desktop (WSL2, AMD Ryzen 5
> 4600H, 12 hilos, 15,4 GB) con límites simulados** de CPU (`cpuset`) y memoria (cgroup v2, sin
> swap). No son rendimiento garantizado en OVHcloud: la CPU de un VPS es distinta y compartida.
> La validación definitiva es de la **fase 6.9**, que puede repetir estos mismos scripts en el VPS.
> Cada cifra va marcada como **medida** (M), **estimación** (E) o **hipótesis** (H).

## 1. Decisión

**Decisión aprobada (14 de noviembre de 2026): VPS-2 (4 vCores, 8 GB, 75 GB NVMe, ≈7,21 €/mes
sin IVA) como infraestructura objetivo inicial**, frente a VPS-1, por su margen de memoria y CPU.
La aprobación **no autoriza** contratar ni desplegar el VPS: la validación definitiva es de la
fase 6.9. Los cambios de configuración de §9 siguen como propuestas, sin aplicar.

| Criterio                                            | VPS-1 (2 vCores, 4 GB)               | VPS-2 (4 vCores, 8 GB)                          |
| --------------------------------------------------- | ------------------------------------ | ----------------------------------------------- |
| Uso real del stack bajo la carga objetivo (M)       | ≈1,0 GiB con una foto procesándose   | igual                                           |
| Memoria disponible para aplicaciones (E)            | ≈2,5 GiB                             | ≈5,9 GiB                                        |
| Suma de límites propuestos (§7)                     | 2,06 GiB                             | 2,75 GiB                                        |
| **Margen sobre los límites**                        | **≈0,44 GiB**                        | **≈3,1 GiB**                                    |
| CPU bajo la carga objetivo (M)                      | ≈1 núcleo en el peor momento (foto)  | igual; sobran 3                                 |
| Capacidad de la API y PostgreSQL (M)                | ≈2.100–4.000 pet/s, ≈2.300–4.300 tps | ≈7.000 pet/s, ≈7.700 tps                        |
| Capacidad del SSR por proceso (M)                   | ≈30 artículos/s                      | igual (un proceso Node usa un solo hilo)        |
| Margen para un 2.º proceso SSR, CMS, monitorización | muy justo                            | holgado                                         |
| Tolerancia si la vCPU de OVH es más lenta (H)       | baja                                 | alta                                            |
| Coste adicional                                     | —                                    | ≈3,40 €/mes (dentro del presupuesto de 15–25 €) |

**Justificación.**

- **VPS-1 cabe técnicamente** en el uso medido hoy: el stack usa ≈1 GiB con carga y la suma de
  límites seguros deja ≈0,44 GiB libres. Pero ese margen no cubre las incertidumbres que esta
  fase no puede medir: la velocidad real de los vCores de OVH, los endpoints de contenido de la
  API (6.2), el SSR de los artículos publicados desde el CMS (6.3), el CMS, las copias de seguridad
  simultáneas y la monitorización.
- **VPS-2 no está sobredimensionado:** el uso real es ≈1 GiB de 8 GB, pero el coste extra es bajo y
  compra lo que más importa con pocos datos reales: margen de memoria (≈3 GiB), CPU para un segundo
  proceso SSR o para procesar fotos sin afectar a la web, y 75 GB de disco.
- **VPS-3 no está justificado** por ninguna medición.
- **Pendiente de la 6.9:** confirmar en el VPS real las cifras de §3–§5 con los mismos scripts. Si
  se quisiera empezar con VPS-1, condiciones mínimas: los límites de §7, un solo proceso SSR,
  imágenes de una en una con un hilo de libvips, y comprobar antes en OVH si el cambio de gama
  (subir de VPS-1 a VPS-2) es posible sin reinstalar (no verificado aquí).

## 2. Qué se ha medido y qué no

| Componente                          | Medido (M)                                                                                                                                             | No medible en esta fase                                                                     |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| PostgreSQL                          | Reposo, `pgbench` con las consultas reales (artículo por slug, listado, imágenes), ×50                                                                 | —                                                                                           |
| API                                 | Reposo, `/actuator/health` bajo carga, JVM (NMT), OOM, `kill -9`                                                                                       | **Endpoints de contenido** (no existen; 6.2)                                                |
| SSR                                 | A: 20 rutas prerenderizadas · B: renderizado en cada petición (página 404) · B2: artículos reales con **contenido empaquetado**, sin PostgreSQL ni API | **C: artículos de las instantáneas publicadas** (6.3; ver la corrección de la incidencia 4) |
| Imágenes                            | `derive()` real sobre copias: HEIC 24,5 MP, JPEG 29,1 MP, panorámica 118,7 MP; umbral OOM; concurrencia; interrupción                                  | Procesamiento masivo (fuera de alcance)                                                     |
| Sistema operativo, `dockerd`, proxy | —                                                                                                                                                      | Solo en un Linux real (6.9); aquí, estimación                                               |

**Sobre `/actuator/health`:** comparte servidor con la API (verificado: un solo puerto, 8080; sin
`management.server.port`) y ejecuta `Connection.isValid()` contra PostgreSQL (verificado en el
bytecode de `DataSourceHealthIndicator`). Es el **suelo** del coste de la API (Tomcat, servlet,
pool, una ida y vuelta), **no** equivale a un endpoint de contenido: no hay consultas, transacciones
ni respuestas de decenas de KB.

## 3. Reposo (VPS-1, 10 min, una muestra por segundo)

Memoria del cgroup (actual), anónima (no recuperable) y pico del kernel (`memory.peak`).

| Servicio   | En frío: media / máx. | Tras la carga: media / máx. | Anónima tras la carga | Pico desde el arranque | CPU media |
| ---------- | --------------------- | --------------------------- | --------------------- | ---------------------- | --------- |
| PostgreSQL | 36 / 41 MiB           | 36 / 44 MiB                 | 19 MiB                | 70 MiB                 | 3–8 % (1) |
| API        | 156 / 157 MiB         | 206 / 206 MiB               | 204 MiB               | 230 MiB                | 0,2 %     |
| SSR        | 23 / 23 MiB           | 98 / 106 MiB                | 93 MiB                | 176 MiB                | 0 %       |
| **Total**  | **≈215 MiB**          | **≈340 MiB**                |                       |                        |           |

(1) Casi todo es el `healthcheck` del entorno de pruebas (`pg_isready` cada 2 s); el local usa 5 s.

- El SSR en frío no ha renderizado nada; la cifra útil es la de «tras la carga».
- **Reservado ≠ usado:** la JVM reserva 1,7 GB de espacio virtual (NMT) pero solo compromete
  140–176 MB; el límite del contenedor es lo que cuenta.

## 4. Carga

Modelo cerrado (usuarios simultáneos, 60 s por nivel, corte automático con >5 % de errores o
p95 >3 s). **Ningún escenario tuvo errores ni tiempos agotados.**

### 4.1 PostgreSQL (consultas reales, 60 % artículo, 30 % listado, 10 % imágenes)

| Clientes | VPS-1 tps   | VPS-1 p95  | VPS-2 tps | VPS-2 p95 | Memoria   |
| -------- | ----------- | ---------- | --------- | --------- | --------- |
| 1        | 937–2.267   | 0,7–1,7 ms | 2.136     | 0,9 ms    | 43–47 MiB |
| 5        | 2.286–4.475 | 2,1–4,2 ms | 7.025     | 1,3 ms    | 54–55 MiB |
| 10       | 2.260       | 9,4 ms     | 7.719     | 2,4 ms    | 69–70 MiB |

Satura la CPU disponible (2 o 4 núcleos) a partir de 5 clientes. Los rangos de VPS-1 son la
variación entre sesiones del portátil (§8).

### 4.2 API (`/actuator/health`)

| Usuarios | VPS-1 pet/s | VPS-1 p95   | VPS-2 pet/s | VPS-2 p95 | Memoria (VPS-1 / VPS-2) |
| -------- | ----------- | ----------- | ----------- | --------- | ----------------------- |
| 1        | 318         | 5,6 ms      | 652         | 2,8 ms    | 198 / 235 MiB           |
| 10       | 1.880–4.000 | 5,1–13,4 ms | 5.338       | 3,5 ms    | 198–204 / 244 MiB       |
| 40       | 2.134       | 39,7 ms     | 6.985       | 12,7 ms   | 227 / 266 MiB           |

### 4.3 SSR

| Escenario (VPS-1)                            | 1 usuario: pet/s · p95 | 10 usuarios: pet/s · p95 | Máximo       | Memoria máx. |
| -------------------------------------------- | ---------------------- | ------------------------ | ------------ | ------------ |
| A. Prerenderizadas (20 rutas)                | 373 · 3,3 ms           | 1.242–1.416 · 16 ms      | ≈1.480 pet/s | 85 MiB       |
| B. Renderizado en cada petición (página 404) | 52 · 32 ms             | 54–67 · 190–251 ms       | ≈67/s        | 176 MiB      |
| B2. Artículos reales, contenido empaquetado  | 29 · 53 ms             | 30–33 · 395–416 ms       | ≈33/s        | 180 MiB      |
| Mezcla (70 % A, 20 % estáticos, 10 % B)      | 337 · 16 ms            | 414–468 · 53–59 ms       | ≈470 pet/s   | 163 MiB      |

- **Con 4 CPU el SSR rinde igual** (B2: ≈33/s; B: ≈67/s): Node renderiza en un solo hilo. Escalar
  el SSR exige más procesos (cada uno ≈100–180 MiB) o caché, no más vCores.
- **B2 no es el escenario futuro (C):** los artículos saldrán de la API. **Estimación (E)** para C:
  el coste de B2 (≈30 ms de CPU por artículo) más la petición a la API (≈2–5 ms con la base de datos
  local, por §4.1–4.2), es decir, del orden de **25–30 artículos/s por proceso**. Se medirá en 6.2
  y se validará en 6.9.

### 4.4 Carga combinada (carga objetivo de la revista)

Modelo abierto durante 180 s: web (70 % prerenderizadas, 20 % estáticos, 10 % renderizado), API,
`pgbench` a 20 tps y **una foto procesándose a la vez** (desde el segundo 30).

| Escenario                                    | pet/s logradas | Descartadas | p95 renderizado | p95 prerender | p95 health | p95 BD | Foto   |
| -------------------------------------------- | -------------- | ----------- | --------------- | ------------- | ---------- | ------ | ------ |
| VPS-1: 20 web + 5 API + 20 tps + HEIC        | 25             | 0           | 27,6 ms         | 2,4 ms        | 2,1 ms     | 1,9 ms | 35,7 s |
| VPS-1: 60 web + 10 API + 20 tps + panorámica | 70             | 0           | 29,8 ms         | 3,2 ms        | 3,0 ms     | 1,9 ms | 9,1 s  |
| VPS-2: 20 web + 5 API + 20 tps + HEIC        | 25             | 0           | 28,8 ms         | 2,4 ms        | 2,1 ms     | 2,0 ms | 40,1 s |

Memoria máxima simultánea de PostgreSQL, API y SSR: **364–373 MiB**, más la foto (387–662 MiB).
**Stack completo bajo la carga objetivo: ≈0,75–1,03 GiB (M).** Sin OOM ni errores.

## 5. Procesamiento de imágenes

Código real (`derive()` del media-worker) sobre **copias** en `../blog-bench/imagenes-prueba/`
(montadas en solo lectura; el contenedor no ve `media-src/`, `public/media/` ni `src/content/`).
Salida en `/tmp` del contenedor. Ajustes aprobados (AVIF q60 e3, WebP q78 e5; 4 anchos).

Tres medidas de memoria distintas, todas del cgroup del contenedor:

- **Memoria total del contenedor** (`memory.current` y su pico, `memory.peak`): lo que el límite de
  Docker compara. Incluye la anónima y la caché.
- **Anónima** (`anon`): memoria de trabajo del proceso (libvips, sharp, Node). Sin swap, **no se
  puede recuperar**: si no cabe en el límite, el kernel mata el proceso (OOMKill).
- **Caché** (`file`): páginas de archivos. Aquí es casi toda el máster temporal sin comprimir
  (`.v`) que `derive()` escribe en `/tmp` y vuelve a leer para cada ancho. El kernel **la recupera
  bajo presión** (escribiéndola antes al disco si aún no lo está), a costa de más E/S.

| Imagen (VPS-1, 1 hilo)   | Tiempo (3 rep.) | Total del contenedor (pico) | Anónima (máx.) | Caché (máx.) | Funciona con | OOMKill con | Salida pública |
| ------------------------ | --------------- | --------------------------- | -------------- | ------------ | ------------ | ----------- | -------------- |
| A. HEIC 24,5 MP (iPhone) | 38,5–39,7 s     | 357–392 MiB                 | 274–303 MiB    | 75–82 MiB    | 320 MiB      | 256 MiB     | 4,4 MiB        |
| B. JPEG 29,1 MP          | 6,0–6,4 s       | 272–283 MiB                 | 182–192 MiB    | ≈84 MiB      | (no probado) | —           | 0,6 MiB        |
| C. Panorámica 118,7 MP   | 8,7–10,1 s      | 635–651 MiB                 | 281–298 MiB    | 368–400 MiB  | 384 MiB      | 256 MiB     | 0,8 MiB        |

**La panorámica, límite a límite (M):**

| Límite del contenedor | Total (máx.) | Anónima (máx.) | Caché (máx.) | En el instante del pico total | Resultado        |
| --------------------- | ------------ | -------------- | ------------ | ----------------------------- | ---------------- |
| 3 GiB                 | 651 MiB      | 298 MiB        | 376 MiB      | 298 anónima + 340 caché       | correcto, 10,1 s |
| 1 GiB                 | 640 MiB      | 286 MiB        | 368 MiB      | 286 + 340                     | correcto, 8,7 s  |
| 768 MiB               | 634 MiB      | 281 MiB        | 399 MiB      | 281 + 340                     | correcto, 8,9 s  |
| 512 MiB               | 512 MiB      | 315 MiB        | 365 MiB      | 315 + 192                     | correcto, 11,5 s |
| 384 MiB               | 384 MiB      | 290 MiB        | 260 MiB      | 228 + 151                     | correcto, 11,8 s |
| 256 MiB               | 256 MiB      | 248 MiB        | 134 MiB      | 244 + 8                       | **OOMKill**      |

- **Los 651 MiB no son la memoria que la panorámica necesita.** Son el total del contenedor sin
  presión: ≈300 MiB anónimos más los ≈340 MiB del máster en caché. Los máximos de anónima y caché
  de cada fila no coinciden en el mismo instante, por eso no se suman.
- **Con 384 MiB funciona** porque el total no supera el límite: el kernel recupera la caché del
  máster (de ≈340 a ≈150 MiB en el pico) y la anónima, ≈230–290 MiB, sigue cabiendo. Cuesta un
  ≈20 % más de tiempo (11,8 s frente a 8,7–10,1 s) por la E/S de recuperar y releer el máster.
- **Con 256 MiB, OOMKill:** ya casi no queda caché que recuperar (8 MiB en el último pico) y la
  memoria anónima, que no se puede recuperar, alcanza el límite.
- **Necesidad real:** ≈300 MiB anónimos más un margen de caché de trabajo. El límite propuesto
  (§7) se calcula sobre el **total**, para que no haya presión de memoria ni ralentización en el
  caso normal.
- **La panorámica no es el peor caso de tiempo:** JPEG se decodifica en flujo (2,5 s).
- **El HEIC es el más lento:** decodificación HEVC de un solo hilo (8,3 s), AVIF 11,7 s y
  `exiftool`. La referencia de la PoC (125,6 s) usaba ajustes anteriores: **no aplica**.
- **Concurrencia de libvips:** sharp usa **1 hilo por defecto en Linux con glibc**. Con 2 hilos,
  −17 a −21 % de tiempo y +3 % de memoria. Con 4 CPU y 4 hilos, el HEIC baja a 30,5 s.
- **Dos imágenes a la vez** (2 CPU): B+B en 7,2 s (frente a 12,4 s seguidas), 513 MiB; A+C en
  38,7 s, 634 MiB. Sin OOM.
- **Interrupciones** (6 `kill -9` en distintos momentos de la codificación): **27 archivos, todos
  válidos, ninguno truncado** (AVIF y WebP se escriben de una vez al final). Sí queda **huérfano el
  máster temporal** (73 MB por interrupción).
- **Originales, `public/media` y `media.json` sin cambios** (SHA-256 antes y después).

## 6. JVM en contenedor

| Límite             | 256 MiB | 512 MiB | 640 MiB | 768 MiB | 1 GiB  | 1,5 GiB |
| ------------------ | ------- | ------- | ------- | ------- | ------ | ------- |
| Heap máximo (45 %) | 126     | 232     | 288     | 346     | 462    | 692 MiB |
| Recolector         | Serial  | Serial  | Serial  | Serial  | Serial | Serial  |

- **Detección correcta** del límite de memoria y de las CPU (1, 2 y 4) por la JVM 21.
- Por debajo de 1.792 MB elige **SerialGC** (adecuado para heaps pequeños). Metaspace **sin
  límite**; code cache reservada 240 MiB; memoria directa = heap.

**Desglose real (NMT, VPS-1, límite 640 MiB):**

| Área                      | Reposo      | Tras 40 usuarios |
| ------------------------- | ----------- | ---------------- |
| Heap comprometido (usado) | 49 MB (≈28) | 63 MB (≈35)      |
| Metaspace + clases        | 37 + 7 MB   | 42 + 8 MB        |
| Code cache                | 15 MB       | 29 MB            |
| Hilos                     | 20 (2 MB)   | 31 (3 MB)        |
| Total NMT                 | 140 MB      | 176 MB           |
| **cgroup (anónima)**      | **165 MB**  | **225 MB**       |

- **El heap apenas se usa** porque aún no hay endpoints de contenido: el tamaño definitivo del heap
  no se puede decidir hasta la 6.2.
- La mayor parte del proceso es memoria fuera del heap, más ≈25–50 MB nativos fuera de NMT.
- **Con 200 MiB, la API sufrió 2 OOMKill del kernel** bajo carga (RSS anónima ≈202 MiB). La JVM
  **no** lanzó `OutOfMemoryError` (se desbordó memoria fuera del heap), así que
  `ExitOnOutOfMemoryError` no actúa: el kernel la mata y Docker la reinicia.
- **`MaxRAMPercentage=45` es razonable por ahora:** en el peor caso (288 MB de heap + ≈150 MB
  fuera del heap) cabe en 512–640 MiB. Se propone acotar Metaspace (§9) y fijar el heap en 6.2.

## 7. Presupuesto de memoria con valores medidos

Límite = pico medido con margen (pico < 80 % del límite), redondeado; nunca por debajo del umbral
de OOM observado.

| Concepto                                   | VPS-1         | VPS-2        | Base                                                                                                            |
| ------------------------------------------ | ------------- | ------------ | --------------------------------------------------------------------------------------------------------------- |
| Memoria no visible (kernel, firmware)      | ≈0,25 GiB     | ≈0,3 GiB     | E (6.9)                                                                                                         |
| Sistema operativo                          | 256 MiB       | 256 MiB      | E                                                                                                               |
| Docker Engine y containerd                 | 160 MiB       | 160 MiB      | E                                                                                                               |
| Proxy inverso con TLS (futuro)             | 64 MiB        | 64 MiB       | E                                                                                                               |
| Copias de seguridad y sincronización R2/B2 | 256 MiB       | 256 MiB      | E                                                                                                               |
| Caché de páginas y margen libre            | 512 MiB       | 1.024 MiB    | reserva                                                                                                         |
| **Disponible para aplicaciones**           | **≈2,5 GiB**  | **≈5,9 GiB** |                                                                                                                 |
| PostgreSQL                                 | 384 MiB       | 512 MiB      | pico 70 MiB (M) + `shared_buffers` de 128 MB y conexiones                                                       |
| API                                        | 512 MiB       | 768 MiB      | pico 230–266 MiB (M); OOM a 200 MiB (M); margen para 6.2                                                        |
| SSR (un proceso)                           | 320 MiB       | 512 MiB      | pico 180 MiB (M); OOM a 112 MiB (M)                                                                             |
| media-worker (una imagen, 1 hilo)          | 896 MiB       | 1.024 MiB    | total 651 MiB (2 CPU) y 698 MiB (4 CPU), de ellos ≈300–343 anónimos (M); funciona con 384 MiB recuperando caché |
| **Suma de límites**                        | **2,06 GiB**  | **2,75 GiB** | ≤ disponible                                                                                                    |
| **Margen**                                 | **≈0,44 GiB** | **≈3,1 GiB** | crecimiento: CMS, 2.º proceso SSR, monitorización                                                               |

Uso real medido bajo la carga objetivo: ≈1,0 GiB (§4.4), muy por debajo de la suma de límites.

## 8. Cuellos de botella

1. **SSR renderizado en cada petición:** ≈30 artículos/s por proceso, sin mejora con más CPU. Hoy
   las rutas de artículos se prerenderizan (≈1.400 pet/s). La web pública seguirá sirviendo
   instantáneas publicadas, no consultará la API en cada petición (corrección de la 6.2.8, abajo);
   la medición con el flujo de publicación real es de la 6.3. Para una revista personal (hipótesis: picos de 20 pet/s con un
   10 % renderizado = 2 renderizados/s) hay ≈15× de margen por proceso.
2. **Procesamiento de un HEIC:** ≈40 s y ≈1 núcleo. No afecta a la web en la carga combinada.
3. **Calentamiento tras reiniciar:** el primer minuto, la API rinde ≈50 % y el SSR ≈12 % menos (JIT).
4. **Base de datos:** no es un cuello de botella (p95 < 3 ms incluso con ×50).

## 9. Incidencias detectadas y fase propuesta

Ninguna se ha corregido en esta fase. Ningún archivo de producción se ha modificado.

| #   | Incidencia (M)                                                                                                                                    | Impacto                                                                                               | Propuesta                                                                                                                                                                       | Fase                          |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| 1   | Con PostgreSQL pausado, `/actuator/health` tarda **30 s** en dar DOWN; el pool se agota (5/5, hasta 36 en espera); peticiones colgadas hasta 60 s | Un healthcheck no detecta la caída a tiempo; peticiones bloqueadas                                    | `connectionTimeout` de Hikari de 3–5 s, `connectTimeout`/`socketTimeout` del driver, separar liveness (sin BD) y readiness (con BD), timeout en el healthcheck de Docker        | 6.2                           |
| 2   | OOM de la API fuera del heap: el kernel la mata sin `OutOfMemoryError`                                                                            | Reinicios silenciosos si el límite es bajo                                                            | Límites de §7, `restart: unless-stopped`, alertas de OOM en la monitorización                                                                                                   | 6.2 (límites) / 6.9 (alertas) |
| 3   | Metaspace sin límite y heap por porcentaje sin validar con endpoints reales                                                                       | Riesgo de crecimiento no acotado                                                                      | `-XX:MaxMetaspaceSize=128m` (3× lo medido) y fijar el heap tras medir los endpoints                                                                                             | 6.2                           |
| 4   | SSR de un solo hilo (≈30 artículos/s por proceso)                                                                                                 | Capacidad limitada si las páginas de artículo se renderizaran en cada petición (corregido: ver abajo) | La web pública consume instantáneas publicadas, no la API al servir; medir en la 6.3 con el flujo de publicación real. Caché HTTP o un 2.º proceso solo si esa medición lo pide | 6.3 (medición) / 6.9          |
| 5   | `angular.json` solo permite `localhost` (`allowedHosts`): con otro Host el SSR responde 400                                                       | La web no funcionaría tras el proxy con el dominio real                                               | Permitir el dominio de producción (`allowedHosts` o `NG_ALLOWED_HOSTS`)                                                                                                         | 6.9                           |
| 6   | Node como PID 1 ignora `SIGTERM`: `docker stop` lo mata a los 10 s (código 137)                                                                   | Paradas y despliegues no ordenados                                                                    | `init: true` en Compose o gestionar `SIGTERM` en el servidor                                                                                                                    | 6.9                           |
| 7   | Sin `memswap_limit`, Docker da a cada contenedor otro tanto de swap                                                                               | Límites de memoria menos efectivos si el VPS tiene swap                                               | `memswap_limit = mem_limit` en el Compose de producción                                                                                                                         | 6.9                           |
| 8   | El media-worker deja el máster temporal huérfano tras una interrupción y escribe con el nombre final                                              | Basura en `/tmp`; riesgo pequeño de archivo truncado (no observado)                                   | Escribir a un nombre temporal y renombrar; limpiar `/tmp` al arrancar                                                                                                           | fase de imágenes del CMS      |
| 9   | sharp usa 1 hilo de libvips por defecto (glibc)                                                                                                   | Hasta un 20 % más lento                                                                               | Decidir explícitamente: 1 hilo en VPS-1 (memoria) o 2 en VPS-2 (tiempo)                                                                                                         | fase de imágenes del CMS      |
| 10  | Rendimiento reducido el primer minuto tras reiniciar (JIT)                                                                                        | Latencias altas justo tras un despliegue                                                              | Readiness tras calentamiento; despliegues fuera de picos                                                                                                                        | 6.9                           |

**Corrección documental de la incidencia 4 (fase 6.2.8, aprobada en el plan de la 6.2).** La
incidencia suponía que, con el CMS, los artículos se renderizarían en el SSR a partir de la API en
cada petición. No es así: la web pública consume **instantáneas publicadas** (el contenido que el CMS
publica), no consulta la API al servir una página. El escenario C («artículos renderizados desde la
API») no aplica tal como estaba descrito, y la medición del SSR con el flujo de publicación real es de
la **fase 6.3**. Solo cambia este informe: ni el código, ni los contratos, ni el contenido, ni la
configuración de la web pública.

**Cambios de configuración propuestos (no aplicados; requieren tu aprobación):**

- `infra/local/compose.yaml`: `mem_limit` y `memswap_limit` de §7, y `restart: unless-stopped` en
  `postgres` y `api`.
- `api/Dockerfile`: añadir `-XX:MaxMetaspaceSize=128m` a `JAVA_TOOL_OPTIONS`.

## 10. Recuperación (solo en `cms-bench`)

| Prueba                                                   | Resultado (M)                                                                                                  |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| API: `SIGKILL` bajo 20 usuarios                          | Reinicio automático; **UP en 12 s** (≈10 s sin servicio)                                                       |
| API: límite de 200 MiB bajo carga                        | 2 OOMKill del kernel, reinicio automático; ≈41 s sin servicio en 120 s. Restaurada a 640 MiB: UP en 8 s        |
| SSR: límite de 112 MiB bajo renderizado                  | 11 OOMKill en 120 s, 39 % de peticiones fallidas. Restaurado a 384 MiB: sirve en 1 s y **20/20 rutas con 200** |
| PostgreSQL: `SIGKILL` tras escrituras confirmadas        | Recuperación desde el WAL en 0,01 s; lista en ≈5 s; `pg_amcheck` limpio; comparación con 0 diferencias         |
| PostgreSQL: `SIGKILL` a mitad de una migración Flyway    | V2 revertida por completo (sin esquema a medias); Flyway reanuda y aplica V2–V4; `pg_amcheck` limpio           |
| PostgreSQL: `SIGKILL` con 600 artículos confirmados      | Lista en ≈4 s; `pg_amcheck` limpio (393 relaciones); 0 diferencias en 600 cuerpos                              |
| Imágenes: `kill -9` y OOMKill a mitad de la codificación | Ningún archivo truncado; máster temporal huérfano                                                              |

**Limitación:** el `SIGKILL` _a mitad de la transacción de importación_ no se capturó (la
importación terminó antes del corte). La reversión de una importación interrumpida descansa en su
transacción única, ya verificada en la 6.1.3 con errores provocados al final.

## 11. Crecimiento

| Contenido         | Artículos | Base de datos | `pgbench` 5 clientes (VPS-1) | Memoria de PostgreSQL | Exportación (con arranque de la JVM, ≈5 s) |
| ----------------- | --------- | ------------- | ---------------------------- | --------------------- | ------------------------------------------ |
| Actual (×1)       | 12        | 9,8 MB        | 4.272 tps · p95 2,2 ms       | 55 MiB                | 6 s                                        |
| Sintético ×50 (M) | 600       | 13 MB         | 3.408 tps · p95 2,9 ms       | 63 MiB                | 10–11 s                                    |

El ×50 reutiliza las imágenes del catálogo: mide artículos y revisiones, no fotografías.

**Estimación de disco (E):** cada foto genera 0,6–4,4 MiB de versiones públicas (media actual
≈1,2 MiB) y su original pesa 4–80 MB (media ≈16 MB).

| Fotos | Versiones públicas | Originales (en R2/B2 según la 6.0) |
| ----- | ------------------ | ---------------------------------- |
| 100   | 0,1–0,4 GB         | ≈1,6 GB                            |
| 500   | 0,6–2,2 GB         | ≈8 GB                              |
| 1.000 | 1,2–4,4 GB         | ≈16 GB                             |

Los artículos no son un factor de crecimiento relevante (600 artículos = 13 MB). El disco de
VPS-1 (40 GB) o VPS-2 (75 GB) basta si los originales viven en el almacenamiento externo.

## 12. Reproducibilidad y limitaciones

| Escenario (3 repeticiones, VPS-1, 10 usuarios) | Dispersión pet/s | Dispersión p95 | ¿≤15 %? |
| ---------------------------------------------- | ---------------- | -------------- | ------- |
| PostgreSQL (5 clientes)                        | 4,1 %            | 5,9 %          | sí      |
| SSR A                                          | 8,9 %            | 10,4 %         | sí      |
| SSR B2                                         | 7,9 %            | 12,5 %         | sí      |
| Mezcla                                         | 10,5 %           | 12,3 %         | sí      |
| SSR B (404)                                    | 13,4 %           | 22,1 %         | no (1)  |
| API                                            | 54 %             | 138 %          | no (1)  |
| Memoria (todos)                                | < 2 %            | —              | sí      |
| Imágenes (A y B)                               | 3–6 %            | —              | sí      |

(1) La primera repetición se hizo justo tras reiniciar los servicios (JIT frío). Sin ella:
API 12 %, SSR B 2 %.

- **Variación entre sesiones del portátil:** el mismo escenario dio hasta 2× de diferencia en
  rendimiento absoluto entre sesiones (carga de Windows y frecuencia de la CPU). Una prueba de
  control en otras CPU (2–3) descartó que las CPU 0–1 fueran más lentas. Para el
  dimensionamiento se han usado los valores **peores**.
- **Anomalías descartadas:** dos carpetas de carga combinada incompletas (`20261114-004125` y
  `20261114-004452`), por un error del orquestador ya corregido; las primeras pruebas de
  recuperación del SSR y de PostgreSQL, inválidas por errores del script (corregidos y repetidas).
- **Memoria libre de la máquina virtual:** no se registró en las primeras ejecuciones (clave de
  `/proc/meminfo` mal leída; corregido). No afecta a las cifras por contenedor.

## 13. Evidencias y reproducción

- Scripts y documentación: `infra/bench/` ([README](README.md)).
- Resultados en bruto (fuera del repositorio): `../blog-bench/resultados/` (≈60 ejecuciones, cada
  una con `meta.json`, resumen de carga y muestras del cgroup) y `resultados/RESUMEN.md` (tabla
  completa generada con `scripts/resumen.mjs`).
- Huellas de verificación: `../blog-bench/huellas/` (originales, `public/media`, `src/content`,
  volumen real antes y después). Copia `pg_dump` del volumen real: `../blog-bench/backup/`.
- Herramientas: k6 2.3.0 (imagen con digest fijado), `pgbench` y `pg_amcheck` (imagen de
  PostgreSQL 18.6 ya usada), NMT y `jcmd` (imagen JDK 21 ya fijada en el Dockerfile). Ninguna
  dependencia nueva en el proyecto.

## 14. Estado final

**Fase 6.1.4 cerrada y aprobada** el 14 de noviembre de 2026. Las incidencias de §9 quedan
pendientes en las fases indicadas. Resultados, huellas, copia `pg_dump` y volúmenes de pruebas se
conservan hasta nueva indicación.

- **Sin cambios** en la web pública, `src/`, `public/`, contratos TypeScript, migraciones,
  configuración de producción, originales ni `public/media`. Volumen real idéntico (filas, huellas
  de artículos, revisiones, media y migraciones).
- **Archivos nuevos:** `infra/bench/` (`compose.yaml`, `README.md`, `informe-6.1.4.md`, `k6/*.js`,
  `media/bench.mjs`, `scripts/*.mjs`, `scripts/recuperacion.sh`). Sin commits.
- **Entorno de pruebas detenido** (`docker compose stop`): contenedores `cms-bench-*` parados y
  volúmenes `cms-bench_pgdata`, `cms-bench_fallo-importacion` y `cms-bench_fallo-importacion-x50`
  conservados, junto con un volumen anónimo vacío de una sonda inicial (`069f45…`). Pueden
  eliminarse cuando lo autorices.
