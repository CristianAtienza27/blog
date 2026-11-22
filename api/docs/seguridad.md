# Seguridad del CMS (fase 6.2)

Diseño aprobado en la planificación de la fase 6.2. Este documento crece con cada subfase.

| Subfase | Estado                                                              |
| ------- | ------------------------------------------------------------------- |
| 6.2.1   | Hecha: roles y credenciales de la base de datos (V5, V6)            |
| 6.2.2   | Hecha: usuarios, contraseña y sesión (V7)                           |
| 6.2.3   | Hecha: passkeys y recuperación (V8)                                 |
| 6.2.4   | Hecha: fuerza bruta, auditoría y dispositivos de confianza (V9)     |
| 6.2.5   | Hecha: autorización por roles, cabeceras y errores                  |
| 6.2.6   | Hecha: herramienta de seguridad (alta y recuperación de emergencia) |
| 6.2.7   | Hecha (recolector pendiente del VPS, 6.9)                           |
| 6.2.8   | Hecha: secretos, volumen nuevo y cierre de la fase 6.2              |

> **⚠️ No exponer a Internet** hasta que se apruebe la 6.2.4 (limitación de intentos, auditoría y
> dispositivos de confianza, implementados en ella) y la publicación se haga con su proxy y
> Cloudflare Access (6.9). Argon2id y su semáforo acotan el coste de cada petición, pero no
> sustituyen a la limitación de intentos.

**Fase 6.2 cerrada (6.2.8).** Pendiente de fases posteriores:

- **6.6 (frontend del CMS):** la comprobación manual en Chromium (registrar una passkey, iniciar
  sesión, cerrar sesión y usar un código de recuperación), con la pantalla de acceso del CMS. En el
  plan antiguo era de la «6.4»; la 6.4 actual es solo el backend.
- **6.9 (VPS):** el recolector (SerialGC, candidato preferido), `MaxMetaspaceSize=128m`,
  `argon2.max-concurrent=4` y el límite de 768 MiB en el Compose; el criterio 6 (p95 < 1,5 s); la
  recuperación ante un OOM real; el límite de 3 CPU; el proxy, Cloudflare Access y HSTS.
- **Primer administrador:** se crea con `crear-admin` justo antes del primer uso real del CMS
  (ventana de P1), no antes.
- **Comprobación de secretos:** `node infra/scripts/comprobar-secretos.mjs` (tras compilar la API)
  busca los valores reales de los secretos en todo el repositorio y en el JAR, sin imprimirlos.

## Topología: mismo origen

El CMS y la API comparten origen (`admin.<dominio>`, fase 6.9): el proxy sirve el CMS en `/` y
reenvía `/api/*` a la API. Sin CORS (ninguna respuesta lleva cabeceras `Access-Control-*`), cookies
del propio host y CSRF de Angular con rutas relativas.

## Estados de autenticación

| Estado | Autoridades                                            | Cuándo                                                      |
| ------ | ------------------------------------------------------ | ----------------------------------------------------------- |
| A      | —                                                      | Sin sesión                                                  |
| P1     | `FACTOR_PASSWORD` + `STATE_ENROLL`                     | Contraseña, registro pendiente y ninguna passkey            |
| P2     | `FACTOR_PASSWORD`                                      | Contraseña; falta la passkey (o un código de recuperación)  |
| R      | `FACTOR_PASSWORD` + `FACTOR_RECOVERY` + `STATE_ENROLL` | Código de recuperación canjeado: solo registrar una passkey |
| F      | `FACTOR_PASSWORD` + `FACTOR_WEBAUTHN` + `ROLE_<rol>`   | Contraseña y passkey del mismo usuario                      |

Qué puede llamar cada estado está en la [matriz de endpoints](#matriz-de-endpoints-623). Cualquier
otra ruta está denegada: 401 sin sesión y 403 con ella. Solo el despacho interno de errores
(`DispatcherType.ERROR`) está permitido, para que un 400 o un 415 producido antes del controlador
se muestre como tal (sin trazas ni mensajes).

## Endpoints (6.2.2)

| Endpoint                             | Respuesta                                                                                 |
| ------------------------------------ | ----------------------------------------------------------------------------------------- |
| `GET /api/auth/csrf`                 | Emite el token CSRF (cookie y cuerpo). El CMS lo llama al cargar                          |
| `POST /api/auth/login`               | JSON `{email, password}`. 200 con `{state}`; 401 `credenciales no válidas`; 400; 415; 429 |
| `GET /api/auth/me`                   | `{state}`, sin datos personales                                                           |
| `POST /api/auth/logout`              | 204. Borra la sesión y caduca la cookie                                                   |
| `POST /api/auth/sessions/revoke-all` | 204. Borra todas las sesiones del usuario, también la actual (solo en F)                  |

- **Mismo 401** para contraseña incorrecta, usuario inexistente y usuario desactivado, y siempre
  con un cálculo completo de Argon2id (contra un hash ficticio si el usuario no existe).
- El correo no distingue mayúsculas.
- Límites de entrada: correo de hasta 254 caracteres y contraseña de hasta 1024.

## Passkeys y recuperación (6.2.3)

### Ceremonias propias sobre webauthn4j (D1)

Spring Security 7.1.2 trae filtros WebAuthn (`http.webAuthn()`), pero no se usan: su
`Webauthn4JRelyingPartyOperations.authenticate()` compara el contador con el del **registro** (el
del objeto de atestación guardado), no con el actual, y después guarda el recibido sin condición.
Una regresión pasaría sin detectarse y el contador bajaría. La política aprobada (credencial
bloqueada, evidencia y procedencia) no puede aplicarse ahí.

- **Criptografía:** `WebAuthnManager` de webauthn4j 0.31.10, con nuestro manejador de anomalías
  del contador (no lanza: decide la política del paso 3).
- **JSON:** los tipos y módulos Jackson de `spring-security-webauthn` 7.1.2: el formato
  `PublicKeyCredential.toJSON()` de los navegadores, con un mapeador propio.
- **Rutas** bajo `/api/auth/` (D3): el proxy de la 6.9 solo tiene que reenviar `/api/*`. Las de
  Spring (`/webauthn/*`, `/login/webauthn`) no existen (denegadas por la matriz).
- **Esquema propio** (D2): `passkey_credential` con clave foránea a `app_user`, `CHECK` y triggers.

### Verificación

| Comprobación      | Registro                                                              | Autenticación y step-up                                             |
| ----------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Tipo              | `webauthn.create`                                                     | `webauthn.get`                                                      |
| Challenge         | El consumido, de un solo uso                                          | El consumido, de un solo uso                                        |
| Origen            | Exacto, de `allowed-origins`                                          | Exacto; `crossOrigin=true` y `topOrigin` se rechazan                |
| Hash del RP ID    | SHA-256 de `rp-id`                                                    | SHA-256 de `rp-id`                                                  |
| UP y UV           | Obligatorios en el servidor                                           | Obligatorios en el servidor (aunque el cliente cambie las opciones) |
| Credencial        | No registrada por ninguna cuenta                                      | Del usuario de la sesión, no suspendida y permitida al emitir       |
| `userHandle`      | —                                                                     | Si llega, el del usuario                                            |
| Firma y algoritmo | ES256, EdDSA o RS256; atestación «none» pedida, no usada para confiar | Firma con la clave guardada                                         |
| `BE` / `BS`       | Se guardan                                                            | `BE` no cambia; `BS` se actualiza                                   |

- **Opciones:** `user.id` es un identificador aleatorio de 32 bytes (nunca el correo);
  `userVerification: required`, `residentKey: preferred`, `attestation: none` y 5 min.
- **Una passkey ajena** al completar el acceso (P2) cierra la sesión: solo se ofrecen las del
  usuario. En el step-up se rechaza y cuenta como fallo.

### Challenges de un solo uso

Tabla `webauthn_challenge` (V8), como mucho uno vivo por sesión y ceremonia, ligado a la sesión
por `session_hash` (HMAC de su identificador; el identificador, que equivale a la cookie, no se
copia). Caducan con el reloj de PostgreSQL (`cms.security.webauthn.challenge-ttl`, 5 min).

| Paso                          | Transacción                                             | Qué hace                                                        |
| ----------------------------- | ------------------------------------------------------- | --------------------------------------------------------------- |
| 1. Consumo                    | Propia (`REQUIRES_NEW`), **confirmada antes de seguir** | `DELETE … RETURNING`: identificador, emisión, contadores        |
| 2. Verificación criptográfica | Ninguna                                                 | Lo de la tabla anterior                                         |
| 3. Contador y procedencia     | Otra propia, con la credencial bloqueada (`FOR UPDATE`) | Clasificación, actualización e `INSERT` en `webauthn_assertion` |

Un fallo de firma, una excepción o la reversión de otra transacción no devuelven el challenge. Las
emisiones simultáneas de una misma sesión y ceremonia se serializan con un bloqueo consultivo de la
transacción (`pg_advisory_xact_lock`). Con varias instancias funciona igual: la tabla es la única
autoridad.

### Política del contador

| Recibido `n` frente a guardado `s`                               | Clasificación        | Acción                                           |
| ---------------------------------------------------------------- | -------------------- | ------------------------------------------------ |
| `n = 0` y `s = 0`                                                | Sin contador         | Aceptar                                          |
| `n > s`                                                          | Creciente            | Aceptar y guardar `n`                            |
| `n ≤ s` con una aserción B **aceptada** que lo demuestra (abajo) | Solapada o retrasada | Rechazar por obsoleta; informativo, no cuenta    |
| `n = s ≠ 0` sin esa prueba                                       | Anomalía débil       | Rechazar y contar; **2 en 30 días → suspendida** |
| `n < s`, o `n = 0` con `s > 0`, sin esa prueba                   | Anomalía fuerte      | Rechazar y **suspender de inmediato**            |

- **Evidencia:** B, de la misma credencial y aceptada, con `A.emisión < B.verificación < A.verificación`,
  `n_B > n_A` y `n_A > A.contador_al_emitir`. Solapada si B se emitió antes o a la vez que A;
  retrasada si después. Las aserciones rechazadas nunca son evidencia y la proximidad en el tiempo
  no interviene.
- **Precisión:** se demuestran el solapamiento de los challenges (de su emisión a su verificación) y
  el orden de los contadores firmados; no el instante físico de la interacción con el
  autenticador.
- **Procedencia:** cada verificación deja una fila en `webauthn_assertion` (challenge, emisión,
  contador al emitir, verificación, recibido, antes y después, resultado), que sobrevive al borrado
  del challenge y de la passkey. Es de solo inserción, también para el propietario (la purga por
  retención llega en la V9).
- **Garantías en la base de datos:** el contador no baja, una suspensión no se retira y las
  anomalías no se borran (triggers). Una credencial suspendida no se ofrece ni sirve; `me` lo
  indica. Salidas: otra passkey, un código de recuperación o la recuperación de emergencia (6.2.6).

### Códigos de recuperación

- **Generación** (`POST /api/auth/recovery-codes`, F con verificación reciente): 10 códigos de
  80 bits en base32 de Crockford (`XXXX-XXXX-XXXX-XXXX`), mostrados una vez con `no-store`. El
  lote nuevo invalida el anterior.
- **Almacenamiento:** solo su HMAC-SHA-256 con una clave de la API (`cms_api_hmac_key`, fuera de la
  base de datos). La búsqueda por HMAC no filtra nada útil por tiempos: sin la clave, el HMAC no
  revela el código.
- **Canje** (`POST /api/auth/recovery`, solo P2): `UPDATE … WHERE used_at IS NULL … RETURNING`
  en su propia transacción; de canjes simultáneos, solo uno lo consigue. La entrada se normaliza
  (mayúsculas, sin guiones ni espacios, `O→0`, `I/L→1`).
- **Estado R:** solo registrar una passkey. Al canjear, se cierran las demás sesiones del usuario;
  al registrar la passkey nueva termina la recuperación (los códigos restantes se invalidan) y la
  sesión pasa a P2: hay que autenticarse con ella para llegar a F. Un código nunca da
  `FACTOR_WEBAUTHN`.

### Transiciones

| Desde          | Evento                                  | Hacia                                     | Además                                                  |
| -------------- | --------------------------------------- | ----------------------------------------- | ------------------------------------------------------- |
| A o cualquiera | Contraseña correcta                     | P1 (registro pendiente y 0 passkeys) o P2 | `last_login_at`                                         |
| P1             | Registro correcto                       | P2                                        | No da F                                                 |
| P2             | Passkey del mismo usuario               | F                                         | Verificación reciente; `complete_passkey_enrollment`    |
| P2             | Código de recuperación                  | R                                         | Cierra las demás sesiones                               |
| R              | Registro correcto                       | P2                                        | Invalida los códigos restantes                          |
| F              | Step-up                                 | F                                         | Renueva la verificación reciente; no cambia autoridades |
| P2, R, F       | 5 fallos en la sesión                   | A                                         | D7                                                      |
| Cualquiera     | Recuperación de emergencia (CLI, 6.2.6) | A (todas las sesiones)                    | Sello nuevo; el siguiente acceso es P1                  |

**Cada transición crea una sesión nueva** (otra fila, otro `PRIMARY_ID`), copia los atributos y
borra la anterior; también el inicio de sesión. La caducidad absoluta sigue contando desde la
contraseña. Verificación reciente: `cms.security.webauthn.step-up-window` (5 min), con el reloj de
la API. La exigen generar códigos, registrar otra passkey desde F y borrar una; no la exigen cerrar
todas las sesiones ni listar las passkeys.

### Peticiones simultáneas de una misma sesión (diagnóstico de la 6.2.3)

Con 20 peticiones simultáneas de la misma sesión y el mismo challenge (6 repeticiones):

- El challenge se consumió **una sola vez** en todas: el `DELETE … RETURNING` es atómico.
- **14–18 respuestas 500:** el contador de fallos en un atributo de sesión. Cada petición perdedora
  lo había cargado sin él y Spring Session JDBC lo insertaba al confirmar la respuesta
  (`SessionRepositoryFilter.commitSession → JdbcIndexedSessionRepository.save →
insertSessionAttributes`): `DuplicateKeyException` en `spring_session_attributes_pk`.
- **Resurrección del identificador anterior (1 de 6):** Spring Session JDBC guarda la fila con
  `UPDATE … SET SESSION_ID = <el que cargó la petición> … WHERE PRIMARY_ID = ?` siempre que cambia
  el último acceso (en cada petición). `changeSessionId()` conserva el `PRIMARY_ID`, así que una
  perdedora que guardó después de la rotación devolvió la fila al identificador P2, ya con el
  contexto F de la ganadora: la cookie anterior quedó en F y la nueva, inválida.
- **D7 no se cumplía:** de unos 19 fallos simultáneos se guardaba 1 (lectura-modificación-escritura).

**Corrección** (sin cambiar las garantías aprobadas):

1. **Rotación por migración** (como `migrateSession` de Spring Security): sesión nueva y borrado de
   la anterior. Una petición tardía actualiza una fila que ya no existe.
2. **Fallos por sesión en `auth_session_failure`** (V8), con un incremento atómico
   (`INSERT … ON CONFLICT DO UPDATE … RETURNING`). Las peticiones que fallan no escriben en la sesión.
3. **El id del challenge ya no se guarda en la sesión:** se consume por `(session_hash, ceremonia)`,
   que es único.

`ChallengeConsumptionTests` lo comprueba repetido: exactamente una autenticación, ninguna 500, el
identificador antiguo inválido y el nuevo en F, los fallos contados y dos challenges distintos a la
vez desde la misma sesión con un guardado tardío tras la rotación.

### Fin del registro (D5)

La API no tiene `UPDATE` sobre `enroll_required`: al completar F llama a
`complete_passkey_enrollment(usuario)`, `SECURITY DEFINER` cuyo dueño es `cms_enrollment_writer`
(sin login, con `SELECT (id, enroll_required)` y `UPDATE (enroll_required, updated_at)` sobre
`app_user`). Solo pone `false` si hay una passkey utilizable y solo desde una sesión de la API
(`pg_has_role(session_user, 'cms_app', 'USAGE')`, como V6). Volver a `true` es de la recuperación
de emergencia (6.2.6). El rol lo crea `infra/local/postgres-init/sql/cms-function-owners.psql`
(idempotente); en un volumen ya inicializado hay que ejecutarlo a mano antes de migrar
(`infra/local/README.md`). V8 comprueba el rol al empezar y, si falta, se detiene sin cambiar nada.

## Limitación de intentos, auditoría y dispositivos (6.2.4)

### Limitación de intentos

| Ámbito                | Sin `__Host-DEVICE` válido                                       | Con `__Host-DEVICE` válido para la cuenta                                                       |
| --------------------- | ---------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Cuenta + dispositivo  | —                                                                | 10 fallos / 15 min → bloqueo de 15 min que crece hasta 1 h; 20 en 24 h → se revoca su confianza |
| Cuenta + IP           | 5 fallos / 15 min → bloqueo de 15 min que crece hasta 1 h        | 10 fallos / 15 min                                                                              |
| IP (cualquier cuenta) | 20 fallos / 15 min → 429 durante 15 min                          | Igual                                                                                           |
| Modo de ataque        | Más de 50 fallos / hora en la cuenta → 1 intento / 15 min por IP | Sin endurecimiento                                                                              |

- **La cuenta nunca se bloquea globalmente.** El modo de ataque se desactiva tras 1 h por debajo
  del umbral. Un atacante solo bloquea su IP y sus parejas.
- **Qué cuenta como fallo (E1):** contraseña, passkey (P2 y step-up) y código de recuperación.
  Emitir opciones WebAuthn cuenta solo en el ámbito de la IP.
- **Claves:** HMAC de la IP, del correo **tecleado** (normalizado) y de sus combinaciones: un correo
  inexistente se comporta exactamente igual que uno existente (mensaje, tiempo y contadores).
- **Orden:** IP → pareja o dispositivo → modo de ataque, antes de Argon2id. Bloqueado: **429 con
  `Retry-After`** y un mensaje genérico, sin calcular el hash.
- **Contadores atómicos:** un único `INSERT … ON CONFLICT DO UPDATE … RETURNING` (la lección de la
  6.2.3), en su propia transacción. 50 fallos simultáneos se cuentan exactos.
- **Reinicio (E3):** la pareja y el dispositivo solo vuelven a cero al completar F; una contraseña
  correcta sola no los reinicia.
- **Reloj (E4):** el de la API (inyectable), como parámetro de las consultas. Los challenges siguen
  con el de PostgreSQL.
- **IP del cliente:** la del socket (`server.forward-headers-strategy: none`). `X-Forwarded-For`
  solo cuenta detrás de un proxy de confianza configurado (6.9); un test lo comprueba.

### Auditoría persistente

`audit_event` (instante, evento, resultado, usuario, huella HMAC de la sesión, agente de usuario
truncado a 200, `details` sin secretos) y `audit_event_ip` (la IP completa, aparte). Nunca se
registran, ni en la base de datos ni en los registros, contraseñas, hashes, identificadores de
sesión, tokens, challenges, códigos, material de credenciales ni el correo tecleado de un usuario
inexistente (solo su HMAC). El identificador lo genera la API (UUID): solo inserta, sin `RETURNING`.

**Transacciones y orden (E2):**

| Operación                                  | Orden                                                                                     | Si falla la auditoría                                                 |
| ------------------------------------------ | ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Fallo de contraseña o del segundo factor   | Contadores (y D7), reglas del dispositivo, después la auditoría                           | 500; el contador ya está confirmado                                   |
| Contraseña correcta                        | Auditoría, después la sesión                                                              | 500 y ninguna sesión                                                  |
| P2 → F, step-up, canje de código, registro | Pasos de la ceremonia (ya confirmados), auditoría, después la transición y el dispositivo | 500 y el estado no cambia (un código canjeado queda usado: lo seguro) |
| Cerrar sesión, cerrar todas, revocar       | La acción protectora (confirmada), después la auditoría (mejor esfuerzo)                  | Se completa (204); el fallo se registra sin datos y no deshace nada   |

Cada evento se escribe en su propia transacción (`REQUIRES_NEW`); ninguno comparte transacción con
la operación que audita. `AuditFailureTests` lo comprueba con fallos reales (el propietario retira
el `INSERT` a `cms_app`).

**Inmutabilidad:** triggers contra `UPDATE`, `DELETE` y `TRUNCATE`, también para el propietario.
Solo borra `purge_audit` (`SECURITY DEFINER`, dueño `cms_audit_retention`, ejecutable solo por
`cms_maintenance`): mínimos fijos de 365 días para eventos y aserciones WebAuthn y 90 para IP,
rechaza cortes más recientes y deja `audit.retention_purge` en la misma transacción. La tarea
programada es de la 6.9.

| Protegido frente a                                                              | No protegido frente a                                                    |
| ------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `cms_api`, `cms_tool`, `cms_security_tool` y `cms_maintenance` (salvo la purga) | `cms_owner` (vía `cms_migrator`): puede desactivar o borrar los triggers |
| Una inyección SQL o un fallo de la API o de las herramientas                    | El superusuario y quien tenga acceso al servidor, a Docker o al volumen  |

**Rechazos CSRF (E5):** agregados por IP y minuto en memoria de cada instancia, como mucho 1000 IP
por minuto. Al llenarse, las IP nuevas van a un único contador de desbordamiento: el mapa no crece.
Registrar es O(1), sin bloqueos ni E/S, y la respuesta 403 no depende del mapa. Cada minuto se
sustituye por uno vacío y se escribe un evento por IP más, si lo hubo, uno de desbordamiento.
**Pérdidas aceptadas:** hasta un minuto si la instancia se detiene sin vaciar; el detalle por IP por
encima de la capacidad (queda el total); y el minuto entero si su escritura falla (se registra sin
datos).

### Dispositivos de confianza

`__Host-DEVICE` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, sin `Domain`): token aleatorio
guardado como HMAC, ligado al usuario y a una familia, caducidad absoluta de 90 días. **Es una señal
de confianza del navegador, nunca un factor de autenticación:** solo atenúa los límites de su
cuenta; no concede ninguna autoridad ni interviene en la autorización. Revocarlo quita el alivio,
nunca el acceso (contraseña y passkey siempre).

| Estado       | Significado                                                      |
| ------------ | ---------------------------------------------------------------- |
| `ACTIVE`     | Vigente y confirmado                                             |
| `PENDING`    | Sucesor emitido, sin presentar (como mucho uno por familia)      |
| `RETIRED`    | Predecesor de un sucesor ya confirmado                           |
| `SUPERSEDED` | Sucesor pendiente sustituido antes de confirmarse                |
| `REVOKED`    | Revocado (con evidencia, a mano o por la herramienta); no vuelve |

- **Rotación:** al completar F, como mucho una vez por inicio de sesión (el step-up no rota). El
  anterior solo se retira cuando el navegador presenta el nuevo. Cada operación bloquea la familia:
  10 accesos simultáneos con el mismo token emiten un solo sucesor y una sola `Set-Cookie`.
- **Concurrencia (dos hallazgos de las pruebas):** (1) actualizar las presentaciones de la
  familia antes de bloquearla provocaba un interbloqueo entre accesos completos simultáneos; ahora
  todo se hace tras el bloqueo. (2) Tras esperar un `SELECT … FOR UPDATE`, PostgreSQL devuelve la
  última versión de las filas ya vistas, pero no el sucesor que la transacción ganadora acaba de
  insertar; la familia se relee tras el bloqueo. El índice único (un `PENDING` por familia)
  impedía el duplicado, pero con un 500.
- **Tolerancia** (`cms.security.device.grace`, 2 min, no es una garantía absoluta sobre la entrega
  de respuestas HTTP): un `RETIRED` o `SUPERSEDED` conserva la confianza durante ese tiempo. Fuera
  de él es **caducado**: sin confianza y con los límites normales, **sin revocar por una sola
  presentación**; si ese navegador completa F, recibe un sucesor del `ACTIVE`.
- **Respuestas desordenadas o perdidas:** un `SUPERSEDED` cuyo sustituto sigue pendiente se
  acepta y se confirma; un sucesor pendiente de más de 2 min se sustituye por otro.
- **Revocación proporcionada**, solo con evidencia: el token caducado y el `ACTIVE` alternados desde
  orígenes distintos (IP o agente) en 15 min; 3 presentaciones caducadas en 24 h sin acceso completo
  después desde ese origen; o alcanzar el límite de fallos con un token caducado. La evidencia está
  en `trusted_device_presentation` (huellas HMAC del origen, 24 h).
- **Límite técnico:** no es posible distinguir con certeza una cookie antigua legítima de una copia
  robada. Un cambio legítimo de IP (móvil) puede revocar; solo cuesta el alivio de los límites.
- **Gestión:** `GET /api/auth/devices` y `DELETE /api/auth/devices/{id}` (F, sin verificación
  reciente: es protectora). `sessions/revoke-all` revoca también los dispositivos salvo
  `{"devices": false}`.

## Autorización, cabeceras y errores (6.2.5)

### Autorización por roles: cerrado por defecto (G3)

- **Contenido del CMS** bajo `/api/cms/**` (6.3 en adelante): a nivel de URL exige F con rol
  (`ROLE_ADMIN` o `ROLE_EDITOR`), y cada método debe declarar además su política:

  | Anotación        | Exige                                                                    |
  | ---------------- | ------------------------------------------------------------------------ |
  | `@CmsEditor`     | F (contraseña y passkey del mismo usuario) con rol ADMIN o EDITOR        |
  | `@CmsAdmin`      | F con rol ADMIN                                                          |
  | `@RecentPasskey` | Además, verificación con passkey reciente (step-up), para lo destructivo |

- **Obligatorio:** `EndpointPolicyArchitectureTests` falla si un endpoint de producción no está en la
  matriz de autenticación ni lleva `@CmsEditor` o `@CmsAdmin` (se demuestra con un controlador de
  pruebas sin anotación). Fuera de `/api/auth/**` y `/api/cms/**`, todo sigue en `denyAll`.
- **Añadir un endpoint de contenido:** bajo `/api/cms/`, con `@CmsEditor` o `@CmsAdmin` (y
  `@RecentPasskey` si destruye o publica algo, según se decida en su fase). Qué puede EDITOR
  frente a ADMIN se decide en cada endpoint (6.3).

### Cabeceras: API frente al CMS

| Cabecera                       | API (`/api/**`, Spring, 6.2.5)                                                                                                      | Documento HTML del CMS (Caddy, 6.6 y 6.9)                                                                 |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `Content-Security-Policy`      | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`                                                   | Borrador abajo                                                                                            |
| `X-Frame-Options`              | `DENY`                                                                                                                              | `DENY`                                                                                                    |
| `X-Content-Type-Options`       | `nosniff`                                                                                                                           | `nosniff`                                                                                                 |
| `Cache-Control`                | `no-store`                                                                                                                          | Según el recurso                                                                                          |
| `Referrer-Policy`              | `no-referrer`                                                                                                                       | `no-referrer` o `same-origin` (6.6)                                                                       |
| `Permissions-Policy`           | Todo denegado: `accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()` (G5) | La del CMS, que **debe permitir WebAuthn** (`publickey-credentials-create` y `-get` en `self`), en la 6.6 |
| `Cross-Origin-Resource-Policy` | `same-origin` (G6)                                                                                                                  | `same-origin`                                                                                             |
| `Strict-Transport-Security`    | `max-age=31536000`, **solo con una petición HTTPS real** (G4)                                                                       | Caddy (6.9)                                                                                               |
| `X-Correlation-Id`             | Identificador de la petición (G2)                                                                                                   | —                                                                                                         |

- **Todos los caminos:** las pone `ApiResponseFilter`, el primer filtro (antes que Spring
  Security) y en todos los despachos: también los rechazos del cortafuegos, los 401/403 de Spring
  Security, CSRF, la limitación de intentos y el despacho de errores del contenedor (comprobado en
  `SecurityHeadersTests`). Las cabeceras propias de Spring Security están desactivadas. **Límite:**
  las respuestas que Tomcat genera antes de llegar a cualquier filtro (una línea de petición mal
  formada) no las llevan; en producción las cubrirá Caddy, delante.
- **HSTS (G4):** un año, **sin `includeSubDomains`** (hasta comprobar que todos los subdominios
  están preparados para HTTPS) y sin `preload`. Solo si `request.isSecure()`:
  `X-Forwarded-Proto` no se acepta (`forward-headers-strategy: none`) hasta tener el proxy de
  confianza de la 6.9 (`HstsHttpsTests` lo comprueba con TLS real; un `X-Forwarded-Proto` del
  cliente no activa nada).
- **Las cabeceras de las respuestas JSON no protegen la aplicación Angular:** la CSP que cuenta es la
  del documento HTML del CMS. Borrador de esa CSP (se prueba en la 6.6 con el bundle real; el
  tratamiento de los estilos de Angular, con nonce o hashes, se decide entonces):
  `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https://media.<dominio>;
connect-src 'self'; font-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none';
form-action 'self'`.

### Errores: ProblemDetail (RFC 9457, G1)

`application/problem+json` con `title` (el del estado), `status`, `detail` genérico, `code` estable
para el CMS y `correlationId`; `type` se omite (equivale a `about:blank`). **Nunca** trazas, clases,
mensajes de excepción ni datos de la petición: lo inesperado es un 500 `internal_error`, y su traza
solo va al registro del servidor, con el identificador de correlación.

| `code`                   | Estado | Cuándo                                                      |
| ------------------------ | ------ | ----------------------------------------------------------- |
| `invalid_credentials`    | 401    | Contraseña incorrecta, usuario inexistente o desactivado    |
| `unauthenticated`        | 401    | Sin sesión, o passkey o código no válidos                   |
| `forbidden`              | 403    | El estado o el rol no lo permiten                           |
| `csrf`                   | 403    | Falta el token CSRF o no es válido                          |
| `rate_limited`           | 429    | Limitación de intentos (con `Retry-After`)                  |
| `saturated`              | 429    | Semáforo de Argon2id lleno (con `Retry-After: 1`)           |
| `bad_request`            | 400    | Entrada no válida, ruta anómala (cortafuegos)               |
| `not_found`              | 404    | Recurso inexistente                                         |
| `method_not_allowed`     | 405    | Método no admitido                                          |
| `conflict`               | 409    | Conflicto de estado (p. ej., la última passkey)             |
| `unsupported_media_type` | 415    | Se esperaba JSON                                            |
| `unavailable`            | 503    | Base de datos no disponible (con `Retry-After: 5`, 6.2.7.2) |
| `internal_error`         | 5xx    | Cualquier error inesperado                                  |

### Correlación (G2)

La API genera un UUID por petición (no se confía en ninguno recibido hasta el proxy de la 6.9) y lo
devuelve en `X-Correlation-Id`, en el `correlationId` de los errores, en cada línea del registro
(`logging.pattern.correlation`, MDC) y en `details.correlationId` de la auditoría. Con hilos
virtuales, el MDC se fija y se limpia en el mismo filtro: 100 peticiones simultáneas tienen cada una
el suyo, sin mezclarse.

### CSRF en todos los métodos que cambian estado

`CsrfCoverageTests` recorre todas las rutas que cambian estado (13 en la 6.2; hoy 38, con las 2 de la
publicación de la 6.3 y las 23 editoriales de la 6.4): sin token, 403 `csrf` en A y en F, y
el estado de negocio y de seguridad (usuarios, passkeys, códigos, dispositivos, contadores,
auditoría, challenges y contenido) no cambia. Los GET no cambian nada de ese estado en ningún
estado de sesión; los únicos efectos internos legítimos, que no se comparan, son el último acceso
de Spring Session y la emisión de la cookie CSRF.

## Herramienta de seguridad (6.2.6)

Solo línea de comandos (sin servidor web), conectada como `cms_security_tool` (comprueba que es
miembro de `cms_security_admin` y que no es superusuario; si no, sale con 1 sin tocar nada) y
**sin la clave HMAC de la API**. Ningún endpoint HTTP llega a esta lógica (la matriz de endpoints
no cambia). En local, el servicio `security-tool` de Compose
([procedimiento](../../infra/local/README.md#herramienta-de-seguridad-fase-626)).

| Orden                                | Argumentos                                                        | Hace                                                                                        |
| ------------------------------------ | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `--cms.tool=crear-admin`             | `--cms.usuario=<correo>` `--cms.contrasena-desde=<archivo>`       | Alta de un ADMIN (H5) activo y con registro pendiente (P1); rechaza un correo que ya existe |
| `--cms.tool=recuperacion-emergencia` | `--cms.usuario=<correo>` `--cms.nueva-contrasena-desde=<archivo>` | Contraseña nueva y revocación completa, en una transacción (abajo)                          |

- **Código de salida:** 0 correcto; 2 argumentos o contraseña no válidos; 3 usuario ya existente
  (alta) o inexistente (recuperación); 1 error inesperado (la transacción se deshace).
- **Salida (H6):** el id del usuario, el resultado y los recuentos. Nunca la contraseña, el
  contenido del archivo, tokens ni el correo; un error inesperado muestra solo la clase y el
  SQLSTATE (el mensaje podría llevar valores de la fila).
- **La contraseña llega siempre de un archivo**, nunca de un argumento: si aparece
  `--cms.contrasena=…` (o `--cms.nueva-contrasena`, `--cms.password`), se rechaza (2) y se avisa de
  que ya ha quedado expuesta. El archivo se lee con un límite de tamaño, en UTF-8 estricto, y se
  ignora un único salto de línea final; no se admiten otros saltos ni caracteres de control.
- **Política:** de 16 caracteres (puntos de código) a 1024 (unidades UTF-16, el mismo límite con
  el que la API acepta el inicio de sesión); que no sea un único carácter repetido; que no contenga
  la parte local del correo (si tiene 3 caracteres o más); y que no figure en la lista de
  contraseñas comunes, sin distinguir mayúsculas. No se normaliza: la API la compara tal cual.
- **Lista de contraseñas comunes (H1):** `src/main/resources/security/common-passwords-16.txt`,
  las 233 entradas de 16 caracteres o más de `100k-most-used-passwords-NCSC.txt` (SecLists, commit
  `1a7bb912…`, licencia MIT de la recopilación; la lista original es del NCSC del Reino Unido, con
  su propia licencia). Origen, tamaño, hashes y filtro, en su cabecera. Sin dependencias nuevas.
- **Auditoría (H3)** en `audit_event`, dentro de la misma transacción (si no se puede escribir, no
  se hace nada): `user.created` y `user.emergency_recovery`, con la fecha, el nombre de la máquina
  (el del contenedor) y `declaredOperator`, el valor de `CMS_OPERATOR` que pasa el procedimiento
  (`$(whoami)@$(hostname)`). **Es información declarada, no una identidad verificada**; si falta,
  «no indicado». Sin `session_hash`, IP ni agente de usuario.

### Recuperación de emergencia

Para cuando se han perdido la passkey y todos los códigos. En **una única transacción**:

1. bloquea la fila del usuario (`SELECT … FOR UPDATE`, ver H7 abajo);
2. pone la contraseña nueva (Argon2id, calculado antes de bloquear), `enroll_required = true`
   (el siguiente acceso es P1) y un `password_changed_at` nuevo, siempre distinto del anterior: el
   **sello de seguridad** que invalida todas las sesiones anteriores;
3. borra las passkeys y el identificador WebAuthn;
4. invalida los códigos de recuperación vigentes;
5. revoca los dispositivos de confianza (`revoked_reason = 'emergency_recovery'`);
6. borra las sesiones del usuario;
7. lo audita (`user.emergency_recovery`, con los recuentos).

Si cualquier paso falla, incluida la auditoría, no cambia nada (comprobado con un fallo simulado
de la auditoría). **No se hace** (decisiones aprobadas):

- **Challenges pendientes:** `cms_security_tool` no tiene permiso sobre ellos (V8). Están ligados
  a la huella de sesiones que se cierran en el paso 6 y caducan en 5 minutos como mucho.
- **Contadores de intentos (H4):** no se reinician (están indexados con HMAC de la clave de la API,
  que la herramienta no tiene, y no forman parte del diseño aprobado). **Consecuencia:** si la
  pareja cuenta e IP, o la IP, estaban bloqueadas por fallos anteriores, el acceso tras la
  recuperación puede tener que esperar a que caduque el bloqueo (15 min a 1 h, ver
  [Limitación de intentos](#limitación-de-intentos)). La cuenta nunca se bloquea globalmente.
- **Fallos por sesión (D7):** sus filas son de sesiones ya borradas y caducan solas.

### Sincronización con la API (H7)

**Problema (diagnóstico de la 6.2.6).** El paso a F recorre varias transacciones independientes
(contador, auditoría, dispositivo), y Spring Session inserta la fila de la sesión nueva al confirmar
la respuesta, fuera de todas ellas. Sin sincronización, una recuperación confirmada entre medias no
ve esa sesión (aún no existe) y el usuario quedaría autenticado después de la recuperación.

**Bloqueo por usuario durante toda la operación protegida.** Las operaciones que conceden un
estado o crean material de autenticación se ejecutan **enteras** con la fila del usuario
bloqueada `FOR KEY SHARE` (`AccountLock`): el bloqueo se toma antes de empezar y se suelta
después de completarlas, no solo durante la lectura del sello.

| Operación                        | Qué protege el bloqueo                                                                     |
| -------------------------------- | ------------------------------------------------------------------------------------------ |
| Inicio de sesión (tras Argon2id) | Auditoría, `last_login_at`, P1 o P2 y la sesión nueva con el sello leído junto con el hash |
| Opciones de registro             | El identificador WebAuthn que se crea si no existe                                         |
| Registro de passkey              | Consumo del challenge, passkey, invalidación de códigos (R) y paso a P2                    |
| Paso a F                         | Challenge, verificación, contador, auditoría, dispositivo, fin del registro y sesión nueva |
| Step-up                          | Challenge, verificación, contador, auditoría y sesión nueva                                |
| Canje de un código (R)           | Código usado, auditoría, sesión nueva y cierre de las demás                                |
| Generación de códigos            | El lote nuevo y su auditoría                                                               |

No lo toman (no conceden nada ni crean material): CSRF, `/me`, cerrar sesión, cerrar todas,
listar y borrar passkeys o dispositivos, y las opciones de autenticación y step-up (solo crean un
challenge de la sesión, que muere con ella). El cuerpo de la petición se lee **antes** de bloquear:
un cliente lento no retiene el bloqueo.

**Con el bloqueo tomado** se compara el sello de la sesión (atributo `cms.auth.securityStamp`,
el `password_changed_at` leído al iniciar sesión) con el actual. Si no coincide, la operación no
se ejecuta: la sesión se cierra, se audita `session.stale_stamp` y se responde 401
`unauthenticated` (en el inicio de sesión, 401 `invalid_credentials` y `login.stale_account`).

**Por qué no se intercalan.** La recuperación toma `FOR UPDATE` sobre la misma fila, que choca
con `FOR KEY SHARE`:

- **Recuperación primero:** las operaciones que lleguen esperan a que termine, ven el sello nuevo
  y se rechazan sin ejecutar nada.
- **API primero:** la recuperación espera a que terminen las operaciones en curso; cuando entra,
  todo lo que crearon (passkey, dispositivo, códigos, sesiones ya guardadas) está confirmado y lo
  revoca.

Hace falta `FOR UPDATE` explícito: el UPDATE de columnas que no son clave solo toma
`FOR NO KEY UPDATE`, compatible con `FOR KEY SHARE` (comprobado por mutación).

**Con las transacciones independientes.** El bloqueo vive en una transacción propia (T), en su
propia conexión, que no escribe nada. Lo demás sigue exactamente como en la 6.2.2–6.2.5:

| Transacción                                | Conexión   | Con el bloqueo tomado                                                                                                   |
| ------------------------------------------ | ---------- | ----------------------------------------------------------------------------------------------------------------------- |
| Consumo del challenge                      | Propia     | Se confirma antes de verificar, también si después falla (sin cambios)                                                  |
| Contador y procedencia                     | Propia     | Se confirma aunque la operación se rechace después (sin cambios)                                                        |
| Auditoría (`record`, `recordBestEffort`)   | Propia     | Se confirma antes de conceder (E2, sin cambios)                                                                         |
| Dispositivo de confianza                   | Propia     | Se confirma antes de conceder F (sin cambios)                                                                           |
| Fallos por sesión (D7) y `last_login_at`   | Autocommit | Sin cambios                                                                                                             |
| `complete_passkey_enrollment`              | Autocommit | Su UPDATE toma `FOR NO KEY UPDATE`, compatible con `FOR KEY SHARE`: no se espera a sí misma                             |
| Spring Session (borrar la sesión anterior) | Propia     | Se confirma con el bloqueo tomado                                                                                       |
| Spring Session (insertar la sesión nueva)  | Propia     | **Fuera del bloqueo**: al confirmar la respuesta. Si llega después de una recuperación, lleva el sello anterior (abajo) |

Las claves foráneas de esas transacciones (passkey, dispositivo, challenge) toman `FOR KEY SHARE`
sobre el usuario, compatible con T. Durante una recuperación esperan a que termine (no hay ciclo:
la recuperación no las espera a ellas). Si alguna vez se formara un ciclo con otra transacción,
PostgreSQL lo detecta y aborta una: la herramienta informa (salida 1) y se puede repetir sin
riesgo, porque es atómica.

**Filtro del sello en cada petición** (`SecurityStampFilter`, después de la caducidad absoluta):
compara el sello de la sesión con el actual (una lectura por clave primaria) y, si no coincide,
cierra la sesión (con su fila) y la petición sigue como anónima. Cubre lo único que queda fuera del
bloqueo: la sesión que Spring Session inserta después de una recuperación se cierra en su primera
petición. Una sesión sin sello (anterior a la 6.2.6) también se cierra. Su coste se mide en la
6.2.7.

**Conexiones.** Cada operación bloqueada usa la conexión de T más, como mucho, otra a la vez. Un
semáforo (`cms.security.account-lock.max-concurrent`, 2) limita las simultáneas para que no puedan
agotar el pool (5): la API no arranca si `2 × max-concurrent ≥ maximum-pool-size`. Si no hay hueco
en `max-wait` (5 s), o el bloqueo no llega en `lock-timeout` (10 s; solo durante una recuperación
larga), 429 `saturated`. La recuperación espera el bloqueo como mucho 30 s (después sale con 1 sin
cambiar nada).

**Límite:** una petición que ya ha pasado el filtro y no toma el bloqueo (p. ej., listar passkeys o
borrar un dispositivo) puede terminar mientras se ejecuta la recuperación; ninguna de esas concede
acceso ni crea material, y la siguiente petición de esa sesión ya se rechaza.

**Pruebas deterministas** (`EmergencyConcurrencyTests`, con puntos de observación del bloqueo, sin
depender de tiempos):

| Orden                | Operación        | Qué se comprueba                                                                                                                                                |
| -------------------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Recuperación primero | Paso a F         | Detenido antes de bloquear; tras la recuperación, 401 y `session.stale_stamp`; nunca `login.passkey_succeeded`                                                  |
| Recuperación primero | Registro (P1)    | 401 y ninguna passkey (sin la comprobación con el bloqueo, quedaría una)                                                                                        |
| Recuperación primero | Inicio de sesión | Contraseña anterior ya validada: 401 `invalid_credentials` y `login.stale_account`                                                                              |
| API primero          | Paso a F         | La recuperación espera el bloqueo (pg_locks); revoca el dispositivo y la passkey; la sesión que Spring Session inserta después se cierra en su primera petición |
| API primero          | Registro (P1)    | La recuperación espera y borra la passkey registrada                                                                                                            |
| Simultáneo (×3)      | 10 pasos a F     | Sin 500; ninguna de las 10 sesiones (ni sus cookies nuevas) vale después                                                                                        |

En todos se comprueba el estado final: P1 con la contraseña nueva (la anterior, 401), ninguna
sesión del usuario, sin passkeys ni identificador WebAuthn, sin códigos vigentes ni dispositivos
vigentes y `enroll_required = true`.

## Disponibilidad de la base de datos (6.2.7.2)

Corrige la incidencia 1 de la 6.1.4, reproducida en la 6.2.7.1: con PostgreSQL detenido,
`/actuator/health` tardaba **32 s** en dar DOWN y las peticiones esperaban **hasta 60 s**.

**Tiempos máximos** (valores aprobados tras las mediciones de la 6.2.7.1):

| Ajuste                                | Valor | Qué acota                                                                                       |
| ------------------------------------- | ----- | ----------------------------------------------------------------------------------------------- |
| Hikari `connection-timeout`           | 5 s   | La espera de una conexión del pool (antes, 30 s). La espera legítima más larga medida: 340 ms   |
| Hikari `validation-timeout`           | 2 s   | La validación de una conexión inactiva antes de entregarla                                      |
| Driver `connectTimeout`               | 5 s   | El establecimiento de la conexión TCP                                                           |
| Driver `socketTimeout`                | 30 s  | Una respuesta de PostgreSQL; por encima del `lock_timeout` de 10 s del bloqueo por usuario (H7) |
| Pool (`maximum-pool-size`)            | 5     | Sin cambios                                                                                     |
| Semáforo del bloqueo por usuario (H7) | 2     | Sin cambios                                                                                     |

**Respuestas: 503 `unavailable` con `Retry-After: 5`** cuando la causa es la base de datos (pool
agotado, conexión rota o caída, clase SQLSTATE 08 o 57P0x, o un commit o rollback que falla porque
la conexión se perdió, aunque no traiga SQLSTATE: 6.2.7.4), en ProblemDetail, con las cabeceras y
el identificador de correlación, y sin detalles internos. En los tres caminos:

- **En los controladores:** `ApiErrors` (también si la auditoría o una transacción lo envuelven).
- **En la cadena de filtros** (Spring Session al cargar o guardar la sesión, el filtro del sello):
  `ApiResponseFilter`, el primero, responde directamente. **Corrige un error anterior:** el
  despacho de errores volvía a cargar la sesión, volvía a fallar y Tomcat respondía con su página
  HTML 500, sin ProblemDetail ni cabeceras (lo encontró `DatabaseOutageTests`).
- **En el despacho de errores:** `ApiErrorController`, para lo que llegue por `sendError`.

**Sondas** (`management.endpoint.health.probes`):

| Sonda                        | Incluye                     | Con PostgreSQL detenido | Uso                                                     |
| ---------------------------- | --------------------------- | ----------------------- | ------------------------------------------------------- |
| `/actuator/health/liveness`  | Estado de la JVM            | UP, al momento          | `HEALTHCHECK` de Docker; `livenessProbe` en Kubernetes  |
| `/actuator/health/readiness` | Disponibilidad y PostgreSQL | DOWN (503) en ≤ 7 s     | Quien reparte el tráfico: Caddy (6.9), `readinessProbe` |
| `/actuator/health`           | Todo (como hasta ahora)     | DOWN (503) en ≤ 7 s     | Comprobación manual                                     |

- **Liveness nunca depende de PostgreSQL:** una caída de la base de datos no hace que Docker o
  Kubernetes den la JVM por muerta ni la reinicien. **Por eso el `HEALTHCHECK` de Compose usa
  liveness y no readiness** (precisión de la 6.2.7.2 sobre D1: Docker solo tiene un estado de salud,
  y con readiness la caída de PostgreSQL marcaría el contenedor como `unhealthy`).
- **El componente `db` es propio** (`DatabaseHealthIndicator`, sustituye al de Spring Boot con el
  mismo nombre): el de Spring valida con `isValid(0)`, sin límite, y con PostgreSQL detenido
  esperaba los 30 s del `socketTimeout` (medido). El propio valida con 2 s y expulsa del pool la
  conexión que no responde.
- **Recuperación automática:** al volver PostgreSQL, el pool crea conexiones nuevas, readiness
  vuelve a UP y las sesiones siguen valiendo (están en PostgreSQL), sin reiniciar la API.

**Límite conocido (medido):** las peticiones que ya estaban ejecutando una consulta cuando
PostgreSQL deja de responder esperan el `socketTimeout` (30 s) y, después, 5 s más por cada acceso
posterior a la base de datos en la misma petición (Spring Session guarda la sesión al escribir la
respuesta y al terminar): **35–45 s**, siempre con su 503 `unavailable`. Como cada una ocupa una
conexión, **como mucho son tantas como el pool (5)**; todas las demás reciben el 503 en unos 5 s.
Bajarlo exigiría un `socketTimeout` menor (siempre por encima del `lock_timeout` de 10 s del
bloqueo por usuario).

**Corrección de la escritura directa:** en esas peticiones, Spring MVC ya había abierto el flujo
binario de la respuesta al fallar el guardado de la sesión, y el 503 directo fallaba con
`IllegalStateException` (Tomcat respondía con su propia página). `ApiProblems.write` escribe por el
canal que ya esté abierto (`ApiProblemsTests`).

**Pruebas:** `DatabaseOutageTests` (un PostgreSQL propio de la clase, pausado con la API en marcha:
liveness, readiness, 503 con sesión y en el inicio de sesión, CSRF sin base de datos y recuperación
con la misma sesión), `HealthEndpointTests` (sondas públicas y sin detalles) y `ApiProblemsTests`
(qué es «no disponible» y qué sigue siendo 500).

## Contraseñas: Argon2id

- **Parámetros:** `m = 19 MiB` (19 456 KiB), `t = 2`, `p = 1`, sal de 16 bytes y hash de 32 (recomendación de OWASP). Formato PHC: `$argon2id$v=19$m=19456,t=2,p=1$…`.
- **Implementación:** `Argon2PasswordEncoder` de Spring Security 7.1.2 sobre BouncyCastle 1.86, que reserva la memoria en el **heap** de Java.
- **Semáforo:** como mucho `cms.security.argon2.max-concurrent` verificaciones a la vez (por defecto, una por CPU). Si no hay hueco en `cms.security.argon2.max-wait` (1 s), la respuesta es **429 con `Retry-After: 1`**: comportamiento esperado ante saturación, no un fallo.
- **Aceptación en contenedor (6.2.7):** sin OOM y con el pico por debajo del 80 % de 768 MiB; un
  hash en 50–500 ms con 2 CPU (p50 ≈ 92 ms). La p95 de 20 inicios de sesión simultáneos (< 1,5 s)
  no se puede validar en el portátil por su variabilidad: cumple con el equipo rápido y no con el
  lento, con cualquier recolector. Recolector (SerialGC, candidato preferido), `MaxMetaspaceSize` y
  `max-concurrent=4`, pendientes de la validación en el VPS (6.9), sin aplicar
  ([informe de la 6.2.7](../../infra/bench/informe-6.2.7.md)).

## Sesión

| Aspecto            | Valor                                                                                   |
| ------------------ | --------------------------------------------------------------------------------------- |
| Almacenamiento     | PostgreSQL (Spring Session JDBC 4.1.1, tablas `spring_session*` de V7)                  |
| Cookie             | `__Host-SESSION`: `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, sin `Domain`       |
| Inactividad        | 30 min (`spring.session.timeout`)                                                       |
| Caducidad absoluta | 12 h desde el inicio de sesión (`cms.security.session.absolute-timeout`, filtro propio) |
| Fijación de sesión | Identificador nuevo en cada inicio de sesión                                            |
| Cierre             | Borra la fila de la sesión y caduca la cookie (`Max-Age=0`)                             |
| Cerrar todas       | Borra todas las sesiones del usuario (índice de Spring Session por principal: su id)    |
| Contenido          | Mínimo: id y rol del usuario, factores y el instante de autenticación                   |

Con `SameSite=Strict`, al llegar a `admin.` desde un enlace externo la primera petición no lleva
la cookie: el CMS verá la sesión como cerrada hasta la siguiente navegación interna. Es aceptable
para un panel de administración.

### Serialización de la sesión: Java por defecto

Comprobado en los jars: Spring Session JDBC 4.1.1 guarda los atributos con la **serialización
Java** (`GenericConversionService` con `SerializingConverter` y `DeserializingConverter`). Existe
una alternativa JSON (los módulos Jackson 3 de Spring Security 7.1.2,
`org.springframework.security.jackson.SecurityJacksonModules`), pero exige un servicio de
conversión propio, una lista de tipos permitidos y soporte para nuestro principal y nuestras
autoridades. Desde la 6.2.3 la sesión sigue sin guardar objetos WebAuthn: los challenges y los
fallos están en tablas propias.

**Decisión: la serialización por defecto**, sin código propio. Es una decisión con riesgo
aceptado, no un riesgo inexistente.

#### Riesgos si se manipulan los datos

La API deserializa los bytes de `spring_session_attributes` (`ObjectInputStream`) al cargar la
sesión de una petición que presenta su cookie. Esos bytes vienen de PostgreSQL. Si alguien los
manipula:

1. **Suplantación sin ningún fallo de Java.** Basta con escribir un `SecurityContext` válido,
   serializado con clases legítimas, con `FACTOR_PASSWORD`, `FACTOR_WEBAUTHN` y rol `ADMIN`, en una
   sesión cuyo identificador conozca, o crear una sesión nueva con un identificador elegido. La API
   la aceptará como una sesión F: **sin contraseña y sin passkey**. Una lista de clases permitidas
   no lo evita, porque las clases son las esperadas.
2. **Ejecución de código en la API.** La deserialización Java puede instanciar cualquier clase
   `Serializable` del classpath. Con una cadena de _gadgets_ adecuada, eso es ejecución de código
   en el proceso de la API, con sus credenciales y su acceso de red.
3. **Denegación de servicio.** Objetos muy anidados o colecciones enormes pueden agotar CPU o
   memoria al deserializar.
4. **Lectura.** `SESSION_ID` (tabla `spring_session`) equivale a la cookie de sesión: quien lo lea
   puede usar esa sesión mientras siga viva. Esto no depende del serializador.

#### Límite de confianza

**Las tablas `spring_session*` están dentro del límite de confianza de la API**: la API confía en
su contenido igual que en su propia memoria. Por tanto, **poder escribir en ellas equivale a
controlar la API** (suplantar a cualquier usuario y, potencialmente, ejecutar código en ella), y
poder leerlas equivale a poder usar las sesiones vivas.

| Quién                                                    | Sobre `spring_session*`                  | Consecuencia si se compromete                                       |
| -------------------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------- |
| `cms_api` (la propia API)                                | Lectura y escritura                      | Sesión F falsificada: equivale a la API                             |
| `cms_migrator` (`SET ROLE cms_owner`, dueño de la tabla) | Todo                                     | Igual                                                               |
| Superusuario de PostgreSQL                               | Todo                                     | Igual                                                               |
| Acceso al volumen de datos o a una copia restaurada      | Todo                                     | Igual                                                               |
| `cms_security_tool`                                      | Solo `principal_name` y borrado de filas | Cerrar sesiones (de cualquier usuario); no puede leerlas ni usarlas |
| `cms_tool`, `cms_maintenance`                            | Ninguno                                  | —                                                                   |

Esto significa, en particular, que **la contraseña de `cms_api` es tan sensible como la cuenta de
administrador del CMS**: quien la tenga puede fabricar una sesión F aunque no pueda modificar
`app_user`. Fuera del límite (lo que esta decisión **no** protege): cualquier atacante que obtenga
una de las capacidades de escritura de la tabla anterior.

#### Mitigaciones aplicadas (6.2.2)

- **Permisos mínimos**, comprobados con logins reales (`SecurityTablePrivilegesTests`): solo
  `cms_api` lee y escribe; `cms_security_tool` solo ve `principal_name` y borra filas (ver
  [Cierre de sesiones por la herramienta de seguridad](#cierre-de-sesiones-por-la-herramienta-de-seguridad));
  `cms_tool` y `cms_maintenance`, nada.
- **Base de datos no expuesta:** en local escucha solo en `127.0.0.1`; en producción quedará en la
  red interna de Docker, sin puerto publicado (6.9).
- **Credenciales separadas** en secretos de archivo, nunca en argumentos ni en registros.
- **Contenido mínimo y sin datos del usuario:** id y rol, autoridades y dos `Instant` (inicio de sesión y verificación reciente). Sin caché de
  peticiones ni objetos construidos con entradas del cliente: un cliente legítimo no puede hacer
  que la API serialice un objeto elegido por él.
- **Identificadores de sesión aleatorios** (UUID de Spring Session), cookie `HttpOnly` y caducidad
  corta (30 min de inactividad, 12 h absolutas): acota la ventana de una sesión robada.
- **Cierre de sesiones:** `revoke-all` (F) y el borrado por la herramienta de seguridad (6.2.6).

#### Mitigaciones posibles (no aplicadas; requieren aprobación)

| Medida                                                   | Qué evita                                   | Qué no evita                                 |
| -------------------------------------------------------- | ------------------------------------------- | -------------------------------------------- |
| Filtro `ObjectInputFilter` (clases, profundidad, tamaño) | _Gadgets_ y bombas de deserialización       | La sesión F falsificada (punto 1)            |
| Serializador JSON (`SecurityJacksonModules`)             | _Gadgets_ de la serialización Java          | La sesión F falsificada                      |
| Firma HMAC de los atributos, con clave fuera de la BD    | Falsificación por quien solo tiene la BD    | A quien tenga también los secretos de la API |
| Borrar `spring_session*` al restaurar una copia          | Confiar en sesiones de una copia manipulada | —                                            |

El filtro es candidato natural del endurecimiento de la 6.2.7, y el borrado al restaurar, de los
procedimientos de copia de la 6.2.8 o la 6.9. Ninguna se aplica sin aprobación.

#### Otras implicaciones

- **Actualizaciones:** tras una actualización incompatible de Spring Security, las sesiones
  guardadas pueden no leerse y habrá que volver a iniciar sesión. Con un solo administrador es
  aceptable.

## Matriz de endpoints (6.2.3)

| Endpoint                                                                                                                                                                                                                                                                          | A   | P1  | P2  | R   | F                                                                                        |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --- | --- | --- | --- | ---------------------------------------------------------------------------------------- |
| `GET /api/auth/csrf`, `POST /api/auth/login`                                                                                                                                                                                                                                      | ✓   | ✓   | ✓   | ✓   | ✓                                                                                        |
| `GET /api/auth/me`, `POST /api/auth/logout`                                                                                                                                                                                                                                       | 401 | ✓   | ✓   | ✓   | ✓                                                                                        |
| `POST /api/auth/passkeys/registration/options` y `…/registration`                                                                                                                                                                                                                 | 401 | ✓   | 403 | ✓   | ✓ con verificación reciente                                                              |
| `POST /api/auth/passkeys/authentication/options` y `…/authentication`                                                                                                                                                                                                             | 401 | 403 | ✓   | 403 | 403                                                                                      |
| `POST /api/auth/recovery`                                                                                                                                                                                                                                                         | 401 | 403 | ✓   | 403 | 403                                                                                      |
| `POST /api/auth/step-up/options` y `…/step-up`                                                                                                                                                                                                                                    | 401 | 403 | 403 | 403 | ✓                                                                                        |
| `GET /api/auth/passkeys`                                                                                                                                                                                                                                                          | 401 | 403 | 403 | 403 | ✓                                                                                        |
| `DELETE /api/auth/passkeys/{id}`                                                                                                                                                                                                                                                  | 401 | 403 | 403 | 403 | ✓ con verificación reciente                                                              |
| `POST /api/auth/recovery-codes`                                                                                                                                                                                                                                                   | 401 | 403 | 403 | 403 | ✓ con verificación reciente                                                              |
| `POST /api/auth/sessions/revoke-all`                                                                                                                                                                                                                                              | 401 | 403 | 403 | 403 | ✓                                                                                        |
| `GET /api/auth/devices`, `DELETE /api/auth/devices/{id}`                                                                                                                                                                                                                          | 401 | 403 | 403 | 403 | ✓                                                                                        |
| Lectura editorial (6.4.2): `GET /api/cms/articulos`, `…/{id}`, `…/{id}/revisiones`, `…/revisiones/{numero}` y los catálogos (`categorias`, `etiquetas`, `autores`, `lugares`, `itinerarios`, `proyectos`, `imagenes`, `galerias`)                                                 | 401 | 403 | 403 | 403 | ✓ (ADMIN y EDITOR, sin verificación reciente)                                            |
| Escrituras editoriales (6.4.3), con CSRF: `POST /api/cms/articulos`, `POST /api/cms/articulos/validacion`, `PUT /api/cms/articulos/{id}/metadatos`, `POST /api/cms/articulos/{id}/revisiones`                                                                                     | 401 | 403 | 403 | 403 | ✓ (ADMIN y EDITOR, sin verificación reciente; los metadatos de un publicado, solo ADMIN) |
| Restaurar una revisión (6.4.4), con CSRF: `POST /api/cms/articulos/{id}/revisiones/{numero}/restaurar`                                                                                                                                                                            | 401 | 403 | 403 | 403 | ✓ (ADMIN y EDITOR, sin verificación reciente)                                            |
| Publicación de artículos y orden (6.4.5), con CSRF: `POST /api/cms/articulos/{id}/publicacion`, `POST /api/cms/articulos/{id}/despublicacion`, `PUT /api/cms/secciones/{id}/orden`                                                                                                | 401 | 403 | 403 | 403 | ✓ solo ADMIN (`@CmsAdmin`), sin verificación reciente; EDITOR, 403                       |
| Etiquetas (6.4.6), con CSRF: `POST /api/cms/etiquetas`, `PUT /api/cms/etiquetas/{id}` (ADMIN y EDITOR); `DELETE /api/cms/etiquetas/{id}` (solo ADMIN)                                                                                                                             | 401 | 403 | 403 | 403 | ✓ sin verificación reciente; borrar siendo EDITOR, 403                                   |
| Autores, lugares, itinerarios y proyectos (6.4.7): `GET /api/cms/{colección}/{id}` (`autores`, `lugares`, `itinerarios`, `proyectos`) y, con CSRF, `POST /api/cms/{colección}`, `PUT /api/cms/{colección}/{id}` (ADMIN y EDITOR); `DELETE /api/cms/{colección}/{id}` (solo ADMIN) | 401 | 403 | 403 | 403 | ✓ sin verificación reciente; borrar siendo EDITOR, 403                                   |

Públicos además, sin sesión y solo con `GET`: `/actuator/health` y sus sondas
`/actuator/health/liveness` y `/actuator/health/readiness` (6.2.7.2); el resto del actuador, 401.

`EndpointProtectionTests` la recorre entera con estados reales (también F de EDITOR y F sin
verificación reciente), y falla si aparece un endpoint sin clasificar. `me` devuelve el estado y,
en P2 y F, cuántas passkeys hay utilizables y suspendidas.

## CSRF

- `csrf.spa()` de Spring Security 7.1.2: el token va en la cookie `__Host-XSRF-TOKEN` (`Secure`,
  `SameSite=Strict`, `Path=/`, sin `Domain`, **legible** por el CMS) y vuelve en la cabecera
  `X-XSRF-TOKEN`. En la 6.6, Angular deberá usar `withXsrfConfiguration` con ese nombre de cookie.
- Todo `POST`, `PUT` y `DELETE` exige el token, también el inicio de sesión (contra el CSRF de inicio de sesión).
- Token nuevo tras iniciar sesión, cerrarla y cerrar todas.

## Usuarios (V7)

`app_user`: id, correo (único sin distinguir mayúsculas), hash Argon2id (`CHECK`), rol (`ADMIN` o
`EDITOR`), `enabled`, `enroll_required`, fechas. Sin registro público: los crea la herramienta de
seguridad (6.2.6).

| Tabla                       | `cms_api` (vía `cms_app`)              | `cms_security_tool` (vía `cms_security_admin`) | Resto |
| --------------------------- | -------------------------------------- | ---------------------------------------------- | ----- |
| `app_user`                  | SELECT; UPDATE solo de `last_login_at` | SELECT, INSERT, UPDATE                         | —     |
| `spring_session`            | SELECT, INSERT, UPDATE, DELETE         | SELECT solo de `principal_name`; DELETE        | —     |
| `spring_session_attributes` | SELECT, INSERT, UPDATE, DELETE         | —                                              | —     |

**V8** (comprobado con conexiones reales en `WebAuthnPrivilegesTests`, privilegios efectivos e
indirectos):

| Tabla u objeto                | `cms_api`                                                                                                                          | `cms_security_tool`                                                        |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `webauthn_user_handle`        | SELECT, INSERT                                                                                                                     | SELECT (`user_id`), DELETE                                                 |
| `passkey_credential`          | SELECT, INSERT, DELETE; UPDATE de `sign_count`, `backup_state`, `last_used_at`, `anomaly_count`, `last_anomaly_at`, `suspended_at` | SELECT (`user_id`), DELETE                                                 |
| `webauthn_challenge`          | SELECT, INSERT, DELETE                                                                                                             | —                                                                          |
| `webauthn_assertion`          | SELECT, INSERT (solo inserción; SELECT para clasificar, D4)                                                                        | —                                                                          |
| `recovery_code`               | SELECT, INSERT; UPDATE de `used_at` e `invalidated_at`                                                                             | SELECT (`user_id`, `used_at`, `invalidated_at`), UPDATE (`invalidated_at`) |
| `auth_session_failure`        | SELECT, INSERT, DELETE; UPDATE de `failures`                                                                                       | —                                                                          |
| `complete_passkey_enrollment` | EXECUTE                                                                                                                            | —                                                                          |

`cms_tool`, `cms_maintenance` y `cms_migrator` (sin `SET ROLE`) no tienen nada.

**V9** (comprobado en `AuditImmutabilityTests`):

| Tabla                           | `cms_api`                                                           | `cms_security_tool`                                                           | `cms_maintenance`                              |
| ------------------------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ---------------------------------------------- |
| `audit_event`, `audit_event_ip` | INSERT                                                              | INSERT, SELECT                                                                | EXECUTE de `purge_audit` (y USAGE del esquema) |
| `login_attempt`                 | SELECT, INSERT, DELETE; UPDATE de contadores y bloqueos             | SELECT (`key_type`, `key_hash`), DELETE                                       | —                                              |
| `trusted_device`                | SELECT, INSERT; UPDATE de estado, fechas y enlaces (nunca el token) | SELECT (`user_id`, `state`), UPDATE (`state`, `revoked_at`, `revoked_reason`) | —                                              |
| `trusted_device_presentation`   | SELECT, INSERT, DELETE; UPDATE de `followed_by_full`                | —                                                                             | —                                              |

`cms_audit_retention` (dueño de `purge_audit`) tiene lo justo para borrar y dejar su evento.

**EXECUTE de PUBLIC en las funciones nuevas (corrección de V6 en V8).** V6 quiso evitarlo con
`ALTER DEFAULT PRIVILEGES … IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`, pero en
PostgreSQL los privilegios por defecto por esquema solo se suman a los globales: no quitan el
EXECUTE de PUBLIC. Las tres funciones de trigger de V8 nacieron con él (detectado por
`SessionUserAuthorizationTests`; el test de 6.2.1 sobre funciones futuras no lo veía porque no
trataba un ACL nulo como público, y se ha corregido). V8 lo revoca en ellas y fija el privilegio
por defecto global (sin `IN SCHEMA`) de `cms_owner` y de los dueños de funciones. La herramienta
de seguridad no lee claves públicas, HMAC, aserciones ni challenges: solo lo necesario para revocar
por usuario en la recuperación de emergencia (6.2.6).

- **`last_login_at`:** la API lo registra (permiso de columna) al iniciar sesión con contraseña
  correcta y usuario activo, con el mismo instante que inicia la caducidad absoluta. Un intento
  fallido no lo cambia.
- **`enroll_required`:** la API no puede modificarlo, ni sola ni junto con `last_login_at`. Su cambio
  (completar el registro de la passkey, entrar en recuperación) será una operación de seguridad
  controlada, definida en la 6.2.3.
- **`updated_at`:** tampoco. Es la fecha del último cambio de la cuenta (herramienta de seguridad);
  un inicio de sesión no es un cambio de la cuenta.

### Cierre de sesiones por la herramienta de seguridad

La herramienta de seguridad (6.2.6) solo necesita cerrar las sesiones de un usuario, no verlas:

```sql
DELETE FROM spring_session WHERE principal_name = ?   -- principal_name = id del usuario
```

- **Permiso:** `SELECT (principal_name)` y `DELETE` sobre `spring_session`; nada sobre
  `spring_session_attributes`. Los atributos se borran por la clave foránea `ON DELETE CASCADE`,
  que PostgreSQL ejecuta con los permisos del dueño de la tabla.
- **Puede:** contar las sesiones de un usuario y borrarlas (todas las suyas, o todas).
- **No puede:** leer `SESSION_ID` (equivale a la cookie), `PRIMARY_ID`, los tiempos ni los
  atributos serializados; filtrar o usar `RETURNING` por esas columnas; modificar o crear
  sesiones; ni ver sus estadísticas (`pg_stats` solo muestra columnas con permiso de lectura).
- **Comprobado con conexiones reales** (`SecurityTablePrivilegesTests` y
  `SessionRevocationByToolTests`), con privilegios efectivos (`has_column_privilege`, que incluye
  lo heredado y PUBLIC) y vías indirectas: solo hereda de `cms_security_admin`, no puede cambiar a
  otro rol, no puede leer ninguna otra relación de las sesiones (vistas incluidas) y no puede
  ejecutar ninguna función `SECURITY DEFINER`. Las cookies de las sesiones borradas dejan de valer
  en la API; las de otros usuarios siguen válidas.
- **Riesgo residual:** puede cerrar sesiones ajenas, que es justo su función (recuperación de
  emergencia): una denegación de servicio sobre las sesiones, no un acceso a ellas.

## Publicación (6.3.4)

Diseño completo en [`publicacion.md`](publicacion.md). Lo relevante para la seguridad:

- **Endpoints:** `POST /api/cms/publicaciones` y `POST /api/cms/publicaciones/vN/activar` exigen `@CmsAdmin`, `@RecentPasskey` y CSRF; `GET /api/cms/publicaciones`, `@CmsAdmin`. La versión de la ruta solo admite `vN`. Las respuestas nunca llevan contenido.
- **Permisos:** la API sigue siendo `cms_app`; V10 le da solo lo necesario para P1–P4, la purga y la reconciliación (sin `DELETE`, sin cambiar la identidad de una fila). Ningún rol ni secreto nuevo.
- **Identidad en la publicación (R1′, confirmada al cerrar la 6.3.4):**
  - **Por HTTP:** el usuario de la sesión autenticada en el servidor (ADMIN con contraseña, passkey y verificación reciente), en `requested_by` y en el evento. Nunca un dato que envíe el cliente.
  - **Herramientas de publicación:** la identidad autenticada y verificable es el login de PostgreSQL de la sesión (`session_user`, autenticado por PostgreSQL con su contraseña): es el rol de la API, no una persona, y así se registra (`requested_by` vacío, `via = herramienta`). El nombre de la máquina se añade solo como contexto: lo fija el entorno de ejecución, no se autentica y no se usa como identidad. No hay ningún parámetro ni variable de operador (ni `CMS_OPERATOR`): nada permite inscribir una identidad libre.
- **Deuda independiente de la 6.2.6 (sin cambios en la 6.3):** la herramienta de seguridad (`SecurityTool`, `ToolAudit`) sigue registrando el operador declarado en `CMS_OPERATOR` como «declaredOperator», información no verificada. No afecta a la publicación: `PublicationTool` y `PublicationAudit` no usan `ToolAudit` ni leen `CMS_OPERATOR`. Queda para revisar en una fase posterior.
- **Auditoría:** `content.*` en la misma transacción que el cambio de estado (repetir una reconciliación no duplica eventos).
- **Puerto interno de la web:** solo en la red interna, sin secretos; el orden entre publicadores lo garantiza la época (fencing), también ante peticiones antiguas que lleguen tarde.

## API editorial (6.4)

Contrato completo en [`cms-api.md`](cms-api.md). Lo relevante para la seguridad:

- **Autorización:** las políticas de la 6.2, sin cambios. Leer y editar contenido: ADMIN o EDITOR
  (`@CmsEditor`). Lo que cambia lo público o borra: solo ADMIN (`@CmsAdmin`), es decir, marcar y
  desmarcar artículos, reordenar una sección y borrar etiquetas y registros del catálogo; y, en el
  servicio, los metadatos de un artículo publicado (403). La passkey reciente sigue solo en la
  publicación de la web (D3).
- **CSRF:** las 23 rutas editoriales que cambian estado (`POST`, `PUT` y `DELETE`) lo exigen; sin
  token, 403 `csrf` y nada cambia (`CsrfCoverageTests`).
- **Privilegios:** ninguno nuevo para `cms_app` salvo `EXECUTE` sobre `publish_article` y
  `unpublish_article` (V11). Son `SECURITY DEFINER` con dueño `cms_article_publisher` (sin login,
  creado en `cms-function-owners.psql`), que solo puede leer las columnas que necesita y actualizar
  `published_revision_id`, `published_at`, `editorial_order` y `lock_version` de `article`.
  `ArticlePublishingSchemaTests` comprueba que ni la API ni ese dueño (con `SET ROLE`) pueden mover el
  puntero publicado por otra vía.
- **Revisiones inmutables:** las protege la base de datos (V3–V6); la API solo crea revisiones
  nuevas y restaurar es crear una nueva.
- **Entrada estricta:** JSON de como mucho 1 MiB, sin claves duplicadas ni desconocidas, con los
  números exactos. El HTML del texto enriquecido se **rechaza**, no se sanea (D5, `RichTextPolicy`).
- **Auditoría:** cada escritura registra su evento `content.*` en la misma transacción, con el
  usuario de la sesión e ids o números, nunca contenido. Un rechazo no registra nada.
- **Errores:** ProblemDetail sin datos internos, con los códigos de `cms-api.md`.

## Pruebas en local

- Los tests automáticos comprueban las cabeceras `Set-Cookie` exactas (con un cliente que no
  descarta las cookies `Secure` por HTTP).
- En un navegador, Chromium trata `http://localhost` como contexto seguro: acepta las cookies
  `Secure` y `__Host-`. La comprobación manual en Chromium (registrar una passkey, iniciar sesión,
  usar un código) queda para la 6.6, con la pantalla de acceso del CMS.
- Las ceremonias se prueban con un autenticador de pruebas propio (`TestAuthenticator`: clave EC
  P-256 y control total de origen, RP ID, flags, contador, tipo y firma), contra la API real.
- HSTS solo se envía en respuestas HTTPS (6.2.5): en local, por HTTP, no.
