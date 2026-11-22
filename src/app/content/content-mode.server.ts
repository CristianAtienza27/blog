import type { EnvironmentProviders, Provider } from '@angular/core';
import { writeResponseToNodeResponse } from '@angular/ssr/node';
import type { Express, NextFunction, Request, Response } from 'express';

/**
 * Modo de contenido del servidor: JSON de src/content incluidos en el build (el de siempre).
 * El build de instantáneas (`--configuration production,snapshot`, fase 6.3.2) sustituye este
 * archivo por snapshot/content-mode.server.snapshot.ts. Solo lo importa código del servidor.
 */

/** La portada y los artículos se prerenderizan con el contenido del build. */
export const SNAPSHOT_MODE = false;

/** Sin proveedores propios: CONTENT_SOURCE es el de los JSON del build. */
export function contentServerProviders(): (Provider | EnvironmentProviders)[] {
  return [];
}

/** Sin rutas propias. */
export function registerContentRoutes(app: Express): void {
  void app;
}

/** Renderiza como siempre: sin contexto, sin caché propia. */
export function handleRender(
  req: Request,
  res: Response,
  next: NextFunction,
  render: (context: unknown) => Promise<globalThis.Response | null>,
): void {
  void req;
  render(undefined)
    .then((response) => (response ? writeResponseToNodeResponse(response, res) : next()))
    .catch(next);
}

/** Nada que preparar antes de escuchar. */
export async function prepareContent(): Promise<void> {
  // Sin instantáneas.
}
