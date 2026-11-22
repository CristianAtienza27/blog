-- V7: usuarios y sesiones (fase 6.2.2). Se ejecuta como cms_owner (cms_migrator + SET ROLE).

-- --- Usuarios ------------------------------------------------------------------------------------
-- Sin registro público: los crea la herramienta de seguridad (cms_security_tool, fase 6.2.6).
-- El correo es único sin distinguir mayúsculas (índice sobre lower(email), sin extensiones).
CREATE TABLE app_user (
  id                  uuid        PRIMARY KEY,
  email               text        NOT NULL
                                  CHECK (length(email) BETWEEN 3 AND 254 AND email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
  -- Solo hashes Argon2id (formato PHC de Spring Security).
  password_hash       text        NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
  role                text        NOT NULL CHECK (role IN ('ADMIN', 'EDITOR')),
  enabled             boolean     NOT NULL DEFAULT true,
  -- Sin passkey registrada: el primer acceso (o una recuperación) solo permite registrarla.
  -- Lo cambian operaciones de seguridad controladas (6.2.3), nunca la API directamente.
  enroll_required     boolean     NOT NULL DEFAULT true,
  last_login_at       timestamptz,
  password_changed_at timestamptz NOT NULL DEFAULT now(),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email));

-- --- Sesiones (Spring Session JDBC 4.1.1, esquema oficial para PostgreSQL) -------------------------
-- Copiado de org/springframework/session/jdbc/schema-postgresql.sql (spring-session-jdbc 4.1.1).
-- Los atributos se guardan con la serialización Java por defecto de Spring Session (ver
-- api/docs/seguridad.md): solo la API puede escribirlos.
CREATE TABLE SPRING_SESSION (
  PRIMARY_ID CHAR(36) NOT NULL,
  SESSION_ID CHAR(36) NOT NULL,
  CREATION_TIME BIGINT NOT NULL,
  LAST_ACCESS_TIME BIGINT NOT NULL,
  MAX_INACTIVE_INTERVAL INT NOT NULL,
  EXPIRY_TIME BIGINT NOT NULL,
  PRINCIPAL_NAME VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES BYTEA NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- --- Permisos ----------------------------------------------------------------------------------------
-- API (cms_app): lee los usuarios y solo registra el último acceso (permiso por columna). No crea
-- usuarios ni cambia correos, contraseñas, roles, enabled, enroll_required ni updated_at (fecha del
-- último cambio de la cuenta, que hace la herramienta de seguridad).
GRANT SELECT ON app_user TO cms_app;
GRANT UPDATE (last_login_at) ON app_user TO cms_app;
-- Spring Session necesita las cuatro operaciones en sus tablas (incluida la limpieza de caducadas).
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO cms_app;

-- Herramienta de seguridad (cms_security_admin): altas y cambios controlados de usuarios, y
-- cierre de sesiones (recuperación de emergencia, 6.2.6). Sin borrar usuarios.
GRANT SELECT, INSERT, UPDATE ON app_user TO cms_security_admin;
-- Para cerrar las sesiones de un usuario le basta localizarlas por principal_name (el id del
-- usuario) y borrarlas: DELETE ... WHERE principal_name = ?. No puede leer SESSION_ID (equivale a
-- la cookie), los atributos serializados ni el resto de columnas, ni usar RETURNING sobre ellas.
-- Los atributos se borran por la clave foránea ON DELETE CASCADE, que PostgreSQL ejecuta con los
-- permisos del dueño de la tabla: no necesita ningún permiso sobre spring_session_attributes.
GRANT SELECT (principal_name), DELETE ON spring_session TO cms_security_admin;
