# Secretos del entorno de mediciones

Mismos archivos y requisitos que [`infra/local/secrets/`](../../local/secrets/README.md), pero
**independientes**: este entorno (`cms-bench`) tiene su propia base de datos de pruebas y sus
propias contraseñas. Carpeta ignorada por Git salvo este README.

Para generarlas (desde la raíz del proyecto):

```bash
node -e 'const fs=require("fs"),c=require("crypto");for(const f of ["postgres_superuser_password","cms_migrator_password","cms_api_password","cms_tool_password","cms_security_tool_password","cms_maintenance_password"]){const p="infra/bench/secrets/"+f;if(!fs.existsSync(p))fs.writeFileSync(p,c.randomBytes(32).toString("base64url"),{mode:0o600})}'
```

Desde la 6.2.3, también la clave HMAC de la API (`cms_api_hmac_key`, ver el README de
`infra/local/secrets/`):

```bash
node -e 'const fs=require("fs"),c=require("crypto"),p="infra/bench/secrets/cms_api_hmac_key";if(!fs.existsSync(p))fs.writeFileSync(p,c.randomBytes(48).toString("base64"),{mode:0o600})'
```
