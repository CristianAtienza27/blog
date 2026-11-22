import express from 'express';
import { rmSync } from 'node:fs';
import type { Server } from 'node:http';
import { connect, type AddressInfo } from 'node:net';
import type { MediaRecord } from '../records/records';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import { privateValues } from './public-projection';
import { snapshotHandler } from './snapshot-http';
import { loadSnapshot, type LoadedSnapshot } from './snapshot-store';
import { tempRoot, withPrivateOriginals, writeSnapshot } from './snapshot-testing';

interface RawResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

/**
 * Petición HTTP cruda por un socket: la ruta llega tal cual, sin la normalización que harían
 * fetch o un navegador («..», «\», codificaciones). Así se prueba lo que puede enviar un atacante.
 */
function raw(port: number, path: string, method = 'GET'): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    let data = '';
    socket.setEncoding('latin1');
    socket.on('data', (chunk) => (data += chunk));
    socket.on('error', reject);
    socket.on('end', () => {
      const [head, ...rest] = data.split('\r\n\r\n');
      const [statusLine, ...lines] = head.split('\r\n');
      const headers: Record<string, string> = {};
      for (const line of lines) {
        const i = line.indexOf(':');
        headers[line.slice(0, i).toLowerCase()] = line.slice(i + 1).trim();
      }
      resolve({ status: Number(statusLine.split(' ')[1]), headers, body: rest.join('\r\n\r\n') });
    });
    socket.write(
      `${method} ${path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n`,
      'latin1',
    );
  });
}

describe('/content/ en el build de instantáneas', () => {
  let root: string;
  let server: Server;
  let port: number;
  let snapshot: LoadedSnapshot;
  const data = withPrivateOriginals(fixtureCollections());
  const slug = FIXTURE_BODIES[0].slug;

  beforeAll(async () => {
    root = tempRoot();
    writeSnapshot(root, 'v1', data, FIXTURE_BODIES);
    snapshot = await loadSnapshot(root, 'v1');
    const app = express();
    app.use(
      '/content',
      snapshotHandler({
        active: () => snapshot,
        find: (version) => (version === snapshot.version ? snapshot : undefined),
      }),
    );
    // Si algo de /content/ se escapara del manejador, llegaría aquí.
    app.use((_req, res) => res.status(299).send('escapado'));
    await new Promise<void>((resolve) => {
      server = app.listen(0, '127.0.0.1', () => resolve());
    });
    port = (server.address() as AddressInfo).port;
  });

  afterAll(async () => {
    await new Promise((resolve) => server.close(resolve));
    rmSync(root, { recursive: true, force: true });
  });

  it('sirve las colecciones y los cuerpos de la versión cargada, inmutables', async () => {
    const media = await raw(port, '/content/v1/media.json');
    expect(media.status).toBe(200);
    expect(media.headers['content-type']).toBe('application/json; charset=utf-8');
    expect(media.headers['cache-control']).toBe('public, max-age=31536000, immutable');
    expect(media.headers['x-content-type-options']).toBe('nosniff');
    expect(JSON.parse(media.body)).toHaveLength(data.media.length);

    const body = await raw(port, `/content/v1/articles/${slug}.json`);
    expect(body.status).toBe(200);
    expect(JSON.parse(body.body)).toEqual(FIXTURE_BODIES[0]);

    const head = await raw(port, '/content/v1/tags.json', 'HEAD');
    expect(head.status).toBe(200);
    expect(head.body).toBe('');
    expect((await raw(port, '/content/v1/tags.json?cualquier=cosa')).status).toBe(200);
  });

  it('la versión servida, sin caché', async () => {
    const version = await raw(port, '/content/version.json');
    expect(version.status).toBe(200);
    expect(version.headers['cache-control']).toBe('no-cache');
    expect(JSON.parse(version.body)).toEqual({ version: 'v1' });
  });

  it('ninguna respuesta lleva original, hashes ni valores privados', async () => {
    const names = [
      'categories',
      'tags',
      'authors',
      'media',
      'galleries',
      'destinations',
      'trips',
      'projects',
      'articles',
    ];
    const bodies = await Promise.all([
      ...names.map((name) => raw(port, `/content/v1/${name}.json`)),
      ...FIXTURE_BODIES.map((b) => raw(port, `/content/v1/articles/${b.slug}.json`)),
      raw(port, '/content/version.json'),
    ]);
    const all = bodies.map((r) => r.body).join('\n');
    expect(bodies.every((r) => r.status === 200)).toBe(true);
    expect(all).not.toContain('"original"');
    expect(all).not.toMatch(/[0-9a-f]{64}/);
    for (const value of privateValues(data.media as MediaRecord[]))
      expect(all).not.toContain(value);
  });

  it.each([
    // El manifiesto, otras versiones y nombres fuera del contrato.
    '/content/v1/manifest.json',
    '/content/v0/media.json',
    '/content/v01/media.json',
    '/content/V1/media.json',
    '/content/v1/MEDIA.json',
    '/CONTENT/v1/media.json',
    '/content/v1/secreto.json',
    '/content/v1/media',
    '/content/v1/media.json/',
    '/content/v1/media.json.bak',
    '/content/v1/articles/no-existe.json',
    '/content/v1/articles/Mayusculas.json',
    '/content/v1/articles/',
    '/content/v1/',
    '/content/',
    '/content',
    '/content/version.json/x',
    // Recorrido de carpetas, literal y codificado.
    '/content/v1/../v1/media.json',
    '/content/v1/articles/../media.json',
    '/content/../server/server.mjs',
    '/content/v1/%2e%2e/media.json',
    '/content/v1/articles/%2e%2e%2fmedia.json',
    '/content/%2e%2e/%2e%2e/package.json',
    '/content/v1/articles/..%2f..%2fmanifest.json',
    // Doble codificación.
    '/content/v1/%252e%252e/media.json',
    '/content/v1/articles/%252e%252e%252fmedia.json',
    // Barras invertidas, literales y codificadas.
    '/content/v1\\media.json',
    '/content/v1/articles\\..\\media.json',
    '/content/v1/%5cmedia.json',
    '/content/v1/articles/%5c..%5cmedia.json',
    // Caracteres nulos codificados y otros codificados.
    '/content/v1/media.json%00',
    '/content/v1/media%00.json',
    '/content/v1/articles/%00.json',
    '/content/v1/med%69a.json',
    '/content/v1/%6dedia.json',
    // Barras dobles y rutas absolutas.
    '/content//v1/media.json',
    '/content/v1//media.json',
    '/content/v1/articles//media.json',
  ])('404 sin escaparse: %s', async (path) => {
    const response = await raw(port, path);
    expect(response.status).toBe(404);
    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.body).toBe('No encontrado');
  });

  it('un carácter nulo literal o un espacio en la ruta no llegan a servir nada', async () => {
    for (const path of ['/content/v1/media.json\0', '/content/v1/med\0ia.json']) {
      const response = await raw(port, path);
      // Node rechaza la petición (400) antes del manejador, o el manejador da 404.
      expect([400, 404]).toContain(response.status);
      expect(response.body).not.toContain('"id"');
    }
  });

  it('una versión bien formada que ya no se sirve: 410, para que el navegador recargue', async () => {
    for (const path of [
      '/content/v2/media.json',
      '/content/v9/articles/x.json',
      '/content/v999999999/tags.json',
    ]) {
      const response = await raw(port, path);
      expect(response.status).toBe(410);
      expect(response.headers['cache-control']).toBe('no-store');
      expect(Buffer.from(response.body, 'latin1').toString('utf8')).toBe('Versión retirada');
    }
  });

  it('solo GET y HEAD', async () => {
    for (const method of ['POST', 'PUT', 'DELETE', 'PATCH']) {
      const response = await raw(port, '/content/v1/media.json', method);
      expect(response.status).toBe(405);
      expect(response.headers['allow']).toBe('GET, HEAD');
    }
  });
});
