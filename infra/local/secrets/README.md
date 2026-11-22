# Secretos locales de la base de datos

Un archivo por contraseña, sin salto de línea final. Esta carpeta está ignorada por Git salvo
este README. Compose los monta como secretos de Docker (`/run/secrets/…`).

| Archivo                       | Login de PostgreSQL | Uso                                     |
| ----------------------------- | ------------------- | --------------------------------------- |
| `postgres_superuser_password` | superusuario        | Solo para inicializar el volumen        |
| `cms_migrator_password`       | `cms_migrator`      | Contenedor de migraciones               |
| `cms_api_password`            | `cms_api`           | API                                     |
| `cms_tool_password`           | `cms_tool`          | Importación y exportación del contenido |
| `cms_security_tool_password`  | `cms_security_tool` | Herramienta de seguridad (fase 6.2)     |
| `cms_maintenance_password`    | `cms_maintenance`   | Retención de la auditoría (fase 6.2)    |
| `cms_api_hmac_key`            | —                   | Clave HMAC de la API (fase 6.2.3)       |
| `cms_admin_password`          | —                   | De un solo uso (6.2.6): ver abajo       |

Cada contraseña debe tener al menos 24 caracteres y ser distinta de las demás: el script de
inicio (`../postgres-init`) se detiene si no es así. Para generarlas (desde la raíz del proyecto):

```bash
node -e 'const fs=require("fs"),c=require("crypto");for(const f of ["postgres_superuser_password","cms_migrator_password","cms_api_password","cms_tool_password","cms_security_tool_password","cms_maintenance_password"]){const p="infra/local/secrets/"+f;if(!fs.existsSync(p))fs.writeFileSync(p,c.randomBytes(32).toString("base64url"),{mode:0o600})}'
```

`cms_api_hmac_key` no es una contraseña de PostgreSQL: es la clave de la API para los HMAC de los
códigos de recuperación y de la huella de la sesión (al menos 32 bytes; la API no arranca sin
ella). Se monta como `/run/secrets/cms.security.hmac-key`. Para generarla:

```bash
node -e 'const fs=require("fs"),c=require("crypto"),p="infra/local/secrets/cms_api_hmac_key";if(!fs.existsSync(p))fs.writeFileSync(p,c.randomBytes(48).toString("base64"),{mode:0o600})'
```

Cambiarla invalida los códigos de recuperación vigentes (hay que generar otros desde el CMS).

`cms_admin_password` **no es un secreto permanente**: es la contraseña del administrador que lee la
herramienta de seguridad (`crear-admin` o `recuperacion-emergencia`). Se crea justo antes de
ejecutarla y se borra justo después
([procedimiento](../README.md#herramienta-de-seguridad-fase-626)); normalmente no existe.

Las contraseñas solo se aplican al inicializar un volumen vacío. Cambiarlas después exige
`ALTER ROLE … PASSWORD` en la base de datos.
