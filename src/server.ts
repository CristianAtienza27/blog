import { AngularNodeAppEngine, createNodeRequestHandler, isMainModule } from '@angular/ssr/node';
import express from 'express';
import { join } from 'node:path';
import {
  handleRender,
  prepareContent,
  registerContentRoutes,
} from './app/content/content-mode.server';

const browserDistFolder = join(import.meta.dirname, '../browser');

const app = express();
const angularApp = new AngularNodeAppEngine();

/**
 * Example Express Rest API endpoints can be defined here.
 * Uncomment and define endpoints as necessary.
 *
 * Example:
 * ```ts
 * app.get('/api/{*splat}', (req, res) => {
 *   // Handle API request
 * });
 * ```
 */

/**
 * Contenido de las instantáneas (/content/) y salud, solo en el build de instantáneas (fases 6.3.2
 * y 6.3.3). En el build normal no registra nada.
 */
registerContentRoutes(app);

/**
 * Serve static files from /browser
 */
app.use(
  express.static(browserDistFolder, {
    maxAge: '1y',
    index: false,
    redirect: false,
  }),
);

/**
 * Handle all other requests by rendering the Angular application.
 * (Build de instantáneas: versión fijada por petición, caché de HTML y 503 sin versión activa.)
 */
app.use((req, res, next) => {
  handleRender(req, res, next, (context) => angularApp.handle(req, context));
});

/**
 * Start the server if this module is the main entry point, or it is ran via PM2.
 * The server listens on the port defined by the `PORT` environment variable, or defaults to 4000.
 */
if (isMainModule(import.meta.url) || process.env['pm_id']) {
  const port = process.env['PORT'] || 4000;
  // En el build de instantáneas, lee activa.json y abre el puerto interno antes de escuchar.
  prepareContent().then(
    () =>
      app.listen(port, (error) => {
        if (error) {
          throw error;
        }

        console.log(`Node Express server listening on http://localhost:${port}`);
      }),
    (error: unknown) => {
      console.error(error instanceof Error ? error.message : error);
      process.exit(1);
    },
  );
}

/**
 * Request handler used by the Angular CLI (for dev-server and during build) or Firebase Cloud Functions.
 */
export const reqHandler = createNodeRequestHandler(app);
