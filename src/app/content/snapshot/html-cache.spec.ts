import express from 'express';
import { rmSync } from 'node:fs';
import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import {
  contentHtmlCache,
  handleRender,
  registerContentRoutes,
} from './content-mode.server.snapshot';
import { HtmlCache } from './html-cache';
import {
  loadSnapshot,
  setCurrentSnapshot,
  setServedSnapshots,
  type LoadedSnapshot,
} from './snapshot-store';
import { tempRoot, writeSnapshot } from './snapshot-testing';

const page = (body: string) => ({
  status: 200,
  headers: [] as [string, string][],
  body: Buffer.from(body),
});

describe('Caché de HTML', () => {
  it('LRU acotada en entradas y en bytes', () => {
    const cache = new HtmlCache(3, 10);
    cache.set('a', page('1234'));
    cache.set('b', page('1234'));
    expect(cache.bytes).toBe(8);
    cache.get('a'); // a pasa a ser la más reciente
    cache.set('c', page('1234')); // 12 bytes > 10: sale b (la menos reciente)
    expect([...cache.entries()].map(([k]) => k)).toEqual(['a', 'c']);
    expect(cache.bytes).toBe(8);
    cache.set('d', page('1'));
    cache.set('e', page('1'));
    expect(cache.size).toBeLessThanOrEqual(3);
    cache.set('enorme', page('x'.repeat(11))); // nunca cabe: no se guarda
    expect(cache.get('enorme')).toBeUndefined();
    expect(HtmlCache.key('v2', '/motor/x')).toBe('v2 /motor/x');
  });
});

describe('Renderizado del build de instantáneas', () => {
  let root: string;
  let server: Server;
  let port: number;
  let v1: LoadedSnapshot;
  let v2: LoadedSnapshot;
  let renders: { url: string; version: string | undefined }[];
  let renderStatus: number;

  beforeAll(async () => {
    root = tempRoot();
    writeSnapshot(root, 'v1', fixtureCollections(), FIXTURE_BODIES);
    writeSnapshot(root, 'v2', fixtureCollections(), FIXTURE_BODIES);
    v1 = await loadSnapshot(root, 'v1');
    v2 = await loadSnapshot(root, 'v2');
    const app = express();
    registerContentRoutes(app);
    app.use('/favicon.ico', (_req, res) => res.status(200).end('icono'));
    app.use((req, res, next) =>
      handleRender(req, res, next, async (context) => {
        const version = (context as { contentSnapshot?: LoadedSnapshot }).contentSnapshot?.version;
        renders.push({ url: req.originalUrl, version });
        return new Response(`<html>${version} ${req.originalUrl}</html>`, {
          status: renderStatus,
          headers: { 'Content-Type': 'text/html; charset=utf-8' },
        });
      }),
    );
    await new Promise<void>((resolve) => {
      server = app.listen(0, '127.0.0.1', () => resolve());
    });
    port = (server.address() as AddressInfo).port;
  });

  afterAll(async () => {
    await new Promise((resolve) => server.close(resolve));
    setServedSnapshots(undefined);
    rmSync(root, { recursive: true, force: true });
  });

  beforeEach(() => {
    renders = [];
    renderStatus = 200;
  });

  const get = async (path: string) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`);
    return { status: response.status, headers: response.headers, text: await response.text() };
  };

  it('sin versión activa: 503 en las páginas y /content/, y la salud responde', async () => {
    setServedSnapshots(undefined);
    const home = await get('/');
    expect(home.status).toBe(503);
    expect(home.headers.get('retry-after')).toBe('5');
    expect((await get('/content/version.json')).status).toBe(503);
    expect(await get('/_salud/vida')).toMatchObject({ status: 200, text: '{"status":"UP"}' });
    expect(await get('/_salud/listo')).toMatchObject({ status: 503, text: '{"status":"DOWN"}' });
    expect((await get('/favicon.ico')).status).toBe(200); // los estáticos se siguen sirviendo
    expect(renders).toEqual([]);
  });

  it('la portada y los artículos: MISS y luego HIT, con la clave de la versión fijada', async () => {
    setServedSnapshots(v1);
    expect(await get('/_salud/listo')).toMatchObject({ status: 200, text: '{"status":"UP"}' });
    const first = await get('/viajes/calas-de-mallorca');
    expect(first.headers.get('x-cache')).toBe('MISS');
    expect(first.headers.get('cache-control')).toBe('no-cache');
    const second = await get('/viajes/calas-de-mallorca');
    expect(second.headers.get('x-cache')).toBe('HIT');
    expect(second.text).toBe(first.text);
    expect(renders).toHaveLength(1);

    // Otra versión activa: otra clave, se renderiza de nuevo con la nueva.
    setCurrentSnapshot(v2);
    const third = await get('/viajes/calas-de-mallorca');
    expect(third.headers.get('x-cache')).toBe('MISS');
    expect(third.text).toContain('v2 ');
    expect(contentHtmlCache().get('v1 /viajes/calas-de-mallorca')).toBeDefined();
    expect(contentHtmlCache().get('v2 /viajes/calas-de-mallorca')).toBeDefined();
  });

  it('no guarda lo que no es igual para todos: parámetros, otras rutas, otros estados', async () => {
    setServedSnapshots(v1);
    for (const path of [
      '/?utm=x',
      '/viajes/calas?x=1',
      '/sobre-mi',
      '/viajes',
      '/no/existe/ruta',
      '/Viajes/calas',
    ]) {
      const response = await get(path);
      expect(response.headers.get('x-cache')).toBeNull();
    }
    renderStatus = 404;
    const missing = await get('/motor/no-existe');
    expect(missing.status).toBe(404);
    expect(missing.headers.get('x-cache')).toBeNull();
    expect(contentHtmlCache().get('v1 /motor/no-existe')).toBeUndefined();
  });
});
