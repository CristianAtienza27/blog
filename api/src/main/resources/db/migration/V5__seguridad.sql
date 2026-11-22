-- V5: seguridad (fase 6.2).
--
-- Se ejecuta como cms_owner (el contenedor de migraciones se conecta como cms_migrator y hace
-- SET ROLE cms_owner). Los roles y la propiedad de la base de datos los crea el script de
-- inicio (infra/local/postgres-init), nunca las migraciones.

-- --- 6.2.1 · Roles y credenciales --------------------------------------------------------------

-- Para que V3 y V4 pudieran transferir sus funciones SECURITY DEFINER a cms_revision_writer y
-- cms_import_writer, esos roles necesitaban CREATE en el esquema (requisito de ALTER ... OWNER
-- TO sin superusuario). Ya no lo necesitan.
REVOKE CREATE ON SCHEMA public FROM cms_revision_writer, cms_import_writer;

-- La API y las herramientas comprueban al arrancar que el esquema está en la versión que esperan
-- (no ejecutan Flyway): solo lectura del historial de migraciones.
GRANT USAGE ON SCHEMA public TO cms_security_admin;
GRANT SELECT ON flyway_schema_history TO cms_app, cms_importer, cms_security_admin;
