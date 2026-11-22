# Informe de la fase 6.2.7: endurecimiento y aceptación en contenedor (cerrada; recolector y memoria pendientes del VPS, 6.9)

Fecha: 16–17 de noviembre de 2026. Volumen de medición: `cms-bench_v627` (proyecto `cms-bench`).
Resultados en `../blog-bench/resultados/*-627-*`, `*-6272-*` y `*-6273-*` (fuera del repositorio).

> **Alcance de las conclusiones.** Mediciones locales en Docker Desktop (WSL2, AMD Ryzen 5 4600H)
> con CPU fijadas (envolvente VPS-2: 4 CPU, API 768 MiB, sin swap). **No son rendimiento garantizado
> del servidor real.** El propio equipo varió **2–3×** de velocidad durante las sesiones (referencia
> de `pgbench` con 5 clientes entre 2.644 y 7.934 tps): por eso solo se comparan mediciones cercanas
> en el tiempo, alternadas, y se dan rangos. La validación definitiva es de la fase 6.9, en el VPS.

| Subfase                          | Estado                                                                  |
| -------------------------------- | ----------------------------------------------------------------------- |
| 6.2.7.1 Mediciones de referencia | Hecha                                                                   |
| 6.2.7.2 Tiempos y sondas         | Hecha (aplicada: ver `api/docs/seguridad.md`)                           |
| 6.2.7.3 JVM y recolector         | **Comparación hecha; decisión pendiente de validación en el VPS (6.9)** |
| 6.2.7.4 Fallos y reinicios       | Hecha (recortada a lo esencial, a petición)                             |

## 1. Mediciones de referencia (6.2.7.1)

Sobre el código de la 6.2.6, sin cambios de producción.

- **Un Argon2id** (misma clase y parámetros que la API, JDK de la imagen): p50 ≈ 92 ms y p95 ≈ 165 ms
  con 1, 2 o 4 CPU (criterio 6: «un hash entre 50 y 500 ms con 2 CPU»: **cumple**). En aislado,
  ParallelGC o G1 suben el rendimiento con 4 hilos de 18,7 a 26 verificaciones/s.
- **20 inicios de sesión simultáneos, 60 s** (4 CPU): 15–16,7 por segundo y p95 de 1,46–1,62 s con
  768 MiB (pico 544 MiB, 71 %); con 512 MiB, pico de 452 MiB (88 %). Sin errores ni OOM.
- **Cadena de seguridad y sesión:** `csrf` 1,4 ms, `health` 2,7 ms y `/me` con sesión 8,4 ms (p50, un
  usuario); el filtro del sello H7 es el 4–7 % del coste de `/me`. Spring Session escribe el último
  acceso en cada petición.
- **Passkeys:** paso a F, 31 ms sin el inicio de sesión; el step-up se limita a ≈84 por segundo por
  el semáforo de 2 operaciones del bloqueo por usuario (diseño), sin errores.
- **PostgreSQL pausado:** `/actuator/health` tardaba 32 s en dar DOWN y las peticiones esperaban
  hasta 60 s (incidencia 1 de la 6.1.4, reproducida).
- **Recuperación de emergencia** con 20 step-up en marcha sobre la misma cuenta: 13,4 s, frente a
  5,5 s sin carga (espera del bloqueo y competencia por CPU).

## 2. Tiempos y sondas (6.2.7.2)

Aplicado y documentado en `api/docs/seguridad.md` («Disponibilidad de la base de datos»).

| PostgreSQL pausado 60 s                         | 6.2.7.1 | 6.2.7.2                                |
| ----------------------------------------------- | ------- | -------------------------------------- |
| `/actuator/health` en DOWN                      | 32 s    | 6 s                                    |
| Readiness en DOWN                               | —       | 5,9 s                                  |
| Liveness                                        | —       | 60/60 UP, máx. 9 ms                    |
| Petición más larga (10 usuarios, `health`)      | 60,4 s  | 5,0 s                                  |
| Peticiones en curso al detenerse (como mucho 5) | —       | 35–45 s, siempre con 503 `unavailable` |
| Recuperación al reanudar, sin reiniciar         | 0,7 s   | 0,4–0,6 s                              |

Con Docker (Compose local): la API siguió `healthy`, sin reinicios, con PostgreSQL pausado 45 s.

## 3. JVM y recolector (6.2.7.3)

**Variantes**, con las opciones candidatas solo en el entorno de medición (`API_JAVA_OPTS`; los
archivos definitivos no cambian): 768 MiB, `MaxRAMPercentage=45`, `MaxMetaspaceSize=128m` y
`cms.security.argon2.max-concurrent=4`, con SerialGC (S, el actual, como control), ParallelGC (P) y
G1 (G). Registro del recolector (`-Xlog:gc`) y NMT en todas por igual.

**Método.** Tres rondas con el orden rotado (S P G · P G S · G S P) y una referencia de `pgbench` al
principio y al final de cada una. Cada ejecución parte de condiciones equivalentes: PostgreSQL y la
API recreados (mismo volumen), estado de autenticación vacío como `cms_api` (sesiones, contadores de
intentos, fallos por sesión y challenges: `bench.mjs reset`) y usuarios nuevos (sin dispositivos ni
contadores previos). La auditoría y la procedencia de las aserciones, de solo inserción, crecen igual
para todas porque el orden rota. Cargas: 30 s de calentamiento; 20 y 40 inicios de sesión
simultáneos; `/me` con 10 usuarios; `csrf` con 40; step-up con 10.

**Regla de elección** (aprobada antes de medir, aplicada por `scripts/gc-decision.mjs`):

1. Condiciones duras en todas las rondas: ningún estado inesperado (5xx u otros), ningún OOMKill ni
   reinicio, y pico de la API por debajo del 80 % del límite (614 MiB).
2. Menor p95 del inicio de sesión con 20 usuarios (mediana de las rondas).
3. Si varias quedan dentro del 15 % de la mejor: menor pico de memoria y, después, menor pausa
   máxima del recolector (los dos, el peor valor de las rondas).

**Resultados de las tres rondas:**

| Métrica                                         | SerialGC     | ParallelGC    | G1            |
| ----------------------------------------------- | ------------ | ------------- | ------------- |
| p95 del inicio de sesión, 20 usuarios (mediana) | 863 ms       | 738 ms        | 874 ms        |
| Peor pico de la API                             | **540 MiB**  | **617 MiB ✗** | 591 MiB       |
| Peor pausa del recolector                       | 273 ms       | 189 ms        | **92 ms**     |
| p95 de las pausas                               | 108–198 ms   | ≈61 ms        | 13–16 ms      |
| Tiempo en pausas por ejecución                  | 45–57 s      | ≈42 s         | 23–34 s       |
| Heap comprometido / memoria nativa del GC       | ≈295 / 1 MiB | ≈340 / 25 MiB | ≈110 / 44 MiB |
| `/me` p95 · step-up p95 (rondas rápidas)        | 16 · 124 ms  | 16 · 120 ms   | 16 · 126 ms   |
| Metaspace (límite 128 MiB)                      | 55 MiB       | 55 MiB        | 55 MiB        |
| Errores, OOM, reinicios, 429 (también con 40)   | 0            | 0             | 0             |

**Aplicación de la regla:** ParallelGC no cumple las condiciones duras (pico de 617 MiB en la
ronda 2). SerialGC y G1 quedan dentro del 15 % en p95 y decide el menor pico: **SerialGC**.

**Criterio 6 (p95 < 1,5 s con 20 inicios de sesión): no validable en este equipo.** SerialGC lo
superó (1.548 ms) en la ronda 1, justo cuando la máquina iba a la mitad de velocidad. Una
confirmación alternada de los dos finalistas (S G · G S · S G) muestra que el resultado depende del
estado del equipo, no del recolector:

| Estado del equipo (referencia de `pgbench`) | SerialGC                   | G1                         |
| ------------------------------------------- | -------------------------- | -------------------------- |
| Rápido (≈7.300–7.900 tps)                   | 862 · 863 · 882 ms ✓       | 767 · 770 · 771 · 874 ms ✓ |
| Lento (≈2.600–3.400 tps)                    | 1.548 · 1.766 · 1.938 ms ✗ | 2.070 ms ✗                 |

En estado lento, SerialGC llegó a pausas de 418–429 ms.

**Decisión (17 de noviembre de 2026, opción B): la configuración definitiva no cambia** (decisión
J6: ningún recolector cumple los criterios de aceptación de forma demostrable). No se promueve
ningún recolector ni los valores candidatos, y los criterios de aceptación no cambian.

Conclusiones provisionales, para la validación en el VPS (6.9), con los mismos scripts:

- **SerialGC es el candidato preferido**, por su menor consumo de memoria.
- **G1** tiene pausas mucho menores, pero más consumo (30–50 MiB más de pico) y no demuestra una
  ventaja suficientemente consistente (dentro del 15 % con el equipo rápido; peor con el lento).
- **ParallelGC queda descartado** por superar el límite de memoria.
- **Los resultados locales no permiten validar el criterio 6** por la variabilidad del equipo.
- Siguen pendientes de esa validación, junto con el recolector, los valores aprobados
  `MaxMetaspaceSize=128m`, `cms.security.argon2.max-concurrent=4` y el límite de 768 MiB del
  Compose: no se han aplicado a los archivos definitivos.

## 4. Fallos y reinicios (6.2.7.4)

Pruebas en `scripts/fallos.mjs` (carga mixta `k6/fallos.js`: inicios de sesión, `/me` y step-up,
registrando cada petición con su `X-Correlation-Id`) y `RestartPersistenceTests` (repositorio). La
configuración definitiva de la API, sin los valores candidatos de la 6.2.7.3. Recortada a petición: R8
no se repitió sobre la imagen con la corrección del 503; R1 se comprobó sobre ella con una sola
ejecución.

| Prueba                                                          | Resultado                                                                                                                      |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| R1: SIGKILL de la API con carga (4 + 1 con la imagen corregida) | Vuelve sola en 7–9 s (20,7 s con el equipo en estado lento); las sesiones siguen valiendo con las mismas cookies; 0 duplicados |
| R2: auditoría frente a respuestas                               | Cada éxito, exactamente un evento; «auditadas con el resultado perdido» 0–2 (siempre ≤ interrumpidas)                          |
| R3: SIGKILL con el bloqueo por usuario tomado                   | Las conexiones desaparecen en 2,4 s; la fila queda libre (`NOWAIT`); recuperación en 8,9 s, salida 0                           |
| R4: SIGKILL de la herramienta esperando el bloqueo              | Nada cambia; su espera en el servidor termina por el `lock_timeout` (28,6 s); la repetición funciona                           |
| R5: contadores de intentos                                      | La pareja sigue bloqueada (71/71 intentos con 429) tras matar la API y reiniciar PostgreSQL; filas idénticas                   |
| R6: reinicio de PostgreSQL (`restart` y SIGKILL, 9 en total)    | Sesiones y operaciones confirmadas intactas; challenges consumidos nunca disponibles; solo 503 (ver abajo)                     |
| R7: integridad tras SIGKILL del postmaster                      | `pg_amcheck` limpio; exportación y comparación sin diferencias                                                                 |
| R8: 384 MiB solo en el contenedor del bench                     | **No se produjo ningún OOM real.** Comprobado solo el aislamiento del límite y su restauración a 768 MiB                       |
| R9: tareas programadas tras reiniciar                           | Purgas a los 5 min sin errores; CSRF agregado una vez; los rechazos aún sin volcar se pierden (E5)                             |

**Corrección encontrada (R6).** Con PostgreSQL reiniciándose justo al guardar la sesión, el rollback
fallaba con `Connection is closed` de Hikari, sin SQLSTATE, y la respuesta era un 500. Ahora un fallo
de commit o rollback por pérdida de la conexión es «base de datos no disponible» (503). Tras la
corrección: 0 errores 5xx distintos de 503 en 5 reinicios (2 ordenados y 3 por SIGKILL).

**Comportamientos documentados:**

- Una sesión cuya rotación (step-up) estaba en curso al fallar puede perderse: el cliente recibe 401 y
  vuelve a iniciar sesión. Nunca queda una sesión a medias.
- Un proceso que muere mientras espera un bloqueo deja su espera en el servidor hasta su
  `lock_timeout` (30 s la herramienta, 10 s la API): PostgreSQL no comprueba el cliente durante la
  espera (`client_connection_check_interval`, desactivado). No cambia nada.
- **R8 no valida la recuperación ante OOM.** Con 384 MiB y 60 inicios de sesión por segundo la API no
  agotó la memoria (ni OOMKill del kernel ni `OutOfMemoryError`; 0 reinicios). Lo comprobado es solo:
  el límite de 384 MiB se aplicó únicamente al contenedor del entorno de medición (402.653.184 bytes),
  se restauró después a 768 MiB (805.306.368 bytes), y los archivos definitivos (Dockerfile,
  `application.yaml` y los dos Compose) no cambiaron (huellas SHA-256 idénticas antes y después). La
  recuperación ante un OOM real de esta versión de la API queda sin validar (la 6.1.4 lo observó con
  una versión anterior, sin seguridad).

**Comprobación de R1 sobre la imagen corregida** (una ejecución, imagen `63da9b5c…`, que contiene la
corrección del 503): correcta. Las 5 sesiones de `/me` y las 5 de step-up siguieron valiendo con las
mismas cookies; 0 duplicados, 0 éxitos sin auditoría, 0 eventos huérfanos; ningún 5xx distinto de 503. La API tardó 20,7 s en volver (frente a 7–9 s), con el equipo en su estado lento: un tercio de
los step-up de las otras ejecuciones en el mismo tiempo.
