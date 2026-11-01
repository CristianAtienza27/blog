# Cristian Atienza

Revista digital personal: viajes, fotografía, motor, experiencias y tecnología.

Angular 22 · TypeScript 6 · SCSS · SSR híbrido (prerender + servidor).

## Requisitos

- Node.js 26.13 LTS o superior (ver `.node-version`)
- npm 11

## Scripts

| Comando               | Qué hace                                                                     |
| --------------------- | ---------------------------------------------------------------------------- |
| `npm start`           | Servidor de desarrollo en http://localhost:4200 (con SSR)                    |
| `npm run build`       | Build de producción: prerenderiza páginas estáticas y genera el servidor SSR |
| `npm run serve:ssr`   | Arranca el servidor SSR de producción en http://localhost:4000               |
| `npm test`            | Tests unitarios (Vitest)                                                     |
| `npm run lint`        | ESLint (TypeScript y plantillas)                                             |
| `npm run lint:styles` | Stylelint (SCSS)                                                             |
| `npm run format`      | Formatea el código con Prettier                                              |

## Renderizado

Definido en `src/app/app.routes.server.ts`:

- **Prerender**: páginas estáticas, generadas como HTML en el build.
- **Server**: contenido dinámico (futuro backend Spring Boot), renderizado en cada petición.
