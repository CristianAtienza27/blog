import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import type { Server } from 'node:http';
import { connect, type AddressInfo } from 'node:net';
import { join } from 'node:path';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import { ContentActivator } from './activation';
import { createInternalApp, listenInternal, parseBind } from './internal-server';
import { peekCurrentSnapshot, setServedSnapshots } from './snapshot-store';
import { tempRoot, writeSnapshot } from './snapshot-testing';

function validCollections() {
  return fixtureCollections();
}

interface RawResponse {
  status: number;
  body: string;
}

/** Petición cruda (ruta y cuerpo tal cual). */
function raw(
  port: number,
  method: string,
  path: string,
  body = '',
  headers: Record<string, string> = {},
): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    let data = '';
    socket.setEncoding('utf8');
    socket.on('data', (chunk) => (data += chunk));
    socket.on('error', reject);
    socket.on('end', () => {
      const [head, ...rest] = data.split('\r\n\r\n');
      resolve({ status: Number(head.split(' ')[1]), body: rest.join('\r\n\r\n') });
    });
    const lines = [`${method} ${path} HTTP/1.1`, 'Host: localhost', 'Connection: close'];
    for (const [name, value] of Object.entries(headers)) lines.push(`${name}: ${value}`);
    if (body || method === 'POST') lines.push(`Content-Length: ${Buffer.byteLength(body)}`);
    socket.write(`${lines.join('\r\n')}\r\n\r\n${body}`);
  });
}

const JSON_TYPE = { 'Content-Type': 'application/json' };

describe('Puerto interno de activación', () => {
  let root: string;
  let server: Server;
  let port: number;

  beforeEach(async () => {
    root = tempRoot();
    mkdirSync(join(root, 'instantaneas'));
    writeSnapshot(join(root, 'instantaneas'), 'v1', validCollections(), FIXTURE_BODIES);
    setServedSnapshots(undefined);
    const activator = new ContentActivator(join(root, 'instantaneas'), join(root, 'estado'));
    await activator.start();
    await new Promise<void>((resolve) => {
      server = createInternalApp(activator).listen(0, '127.0.0.1', () => resolve());
    });
    port = (server.address() as AddressInfo).port;
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    setServedSnapshots(undefined);
    rmSync(root, { recursive: true, force: true });
  });

  it('sin versión activa: el estado responde y activar recupera la web', async () => {
    const before = JSON.parse((await raw(port, 'GET', '/interno/estado')).body);
    expect(before).toMatchObject({ listo: false, activa: null });

    expect((await raw(port, 'POST', '/interno/reservar', '{"epoch":1}', JSON_TYPE)).status).toBe(
      200,
    );
    const activated = await raw(
      port,
      'POST',
      '/interno/activar',
      '{"version":"v1","epoch":1}',
      JSON_TYPE,
    );
    expect(activated.status).toBe(200);
    expect(JSON.parse(activated.body)).toMatchObject({
      resultado: 'activada',
      version: 'v1',
      previous: null,
    });
    expect(peekCurrentSnapshot()?.version).toBe('v1');

    const again = await raw(
      port,
      'POST',
      '/interno/activar',
      '{"version":"v1","epoch":1}',
      JSON_TYPE,
    );
    expect(JSON.parse(again.body)).toMatchObject({ resultado: 'sin cambios', version: 'v1' });
    expect(JSON.parse((await raw(port, 'GET', '/interno/estado')).body)).toMatchObject({
      listo: true,
      activa: 'v1',
    });
  });

  it('una versión inexistente o que no pasa la puerta: 422 con las incidencias', async () => {
    await raw(port, 'POST', '/interno/reservar', '{"epoch":1}', JSON_TYPE);
    const response = await raw(
      port,
      'POST',
      '/interno/activar',
      '{"version":"v7","epoch":1}',
      JSON_TYPE,
    );
    expect(response.status).toBe(422);
    expect(JSON.parse(response.body)).toMatchObject({
      resultado: 'rechazada',
      incidencias: ['la versión v7 no existe'],
    });
  });

  it.each([
    ['otra ruta', 'GET', '/interno', '', {}],
    ['otra ruta', 'POST', '/interno/activar/', '{"version":"v1"}', JSON_TYPE],
    ['parámetros de consulta', 'POST', '/interno/activar?x=1', '{"version":"v1"}', JSON_TYPE],
    ['mayúsculas', 'POST', '/INTERNO/activar', '{"version":"v1"}', JSON_TYPE],
    ['codificada', 'POST', '/interno/%61ctivar', '{"version":"v1"}', JSON_TYPE],
    ['recorrido', 'POST', '/interno/../interno/activar', '{"version":"v1"}', JSON_TYPE],
    ['barra invertida', 'POST', '/interno\\activar', '{"version":"v1"}', JSON_TYPE],
    ['otro método', 'GET', '/interno/activar', '', {}],
    ['otro método', 'PUT', '/interno/activar', '{"version":"v1"}', JSON_TYPE],
    ['otro método', 'POST', '/interno/estado', '', JSON_TYPE],
    ['contenido', 'GET', '/content/v1/media.json', '', {}],
    ['salud', 'GET', '/_salud/listo', '', {}],
  ])('%s → 404 y nada cambia: %s %s', async (_label, method, path, body, headers) => {
    const response = await raw(port, method, path, body, headers);
    expect(response.status).toBe(404);
    expect(peekCurrentSnapshot()).toBeUndefined();
  });

  it.each([
    ['sin tipo JSON', '{"version":"v1"}', { 'Content-Type': 'text/plain' }, 415],
    ['JSON no válido', '{version:v1}', JSON_TYPE, 400],
    ['otra forma', '["v1"]', JSON_TYPE, 400],
    ['campos de más', '{"version":"v1","ruta":"/etc"}', JSON_TYPE, 400],
    ['contenido en vez de versión', '{"contenido":{"title":"x"}}', JSON_TYPE, 400],
    ['versión con recorrido', '{"version":"../v1"}', JSON_TYPE, 400],
    ['versión codificada', '{"version":"v%31"}', JSON_TYPE, 400],
    ['versión con barra', '{"version":"v1/../v2"}', JSON_TYPE, 400],
    ['versión con nulo', '{"version":"v1\\u0000"}', JSON_TYPE, 400],
    ['versión numérica', '{"version":1}', JSON_TYPE, 400],
    ['cuerpo vacío', '', JSON_TYPE, 400],
    [
      'cuerpo de más de 1 KB',
      JSON.stringify({ version: 'v1', relleno: 'x'.repeat(2000) }),
      JSON_TYPE,
      413,
    ],
  ])('%s → %s y nada cambia', async (_label, body, headers, status) => {
    const response = await raw(port, 'POST', '/interno/activar', body, headers);
    expect(response.status).toBe(status);
    expect(peekCurrentSnapshot()).toBeUndefined();
  });

  it('solo escucha en la dirección indicada, nunca en 0.0.0.0', async () => {
    expect(() => parseBind('0.0.0.0:4100')).toThrow('sin 0.0.0.0');
    for (const bad of [
      '4100',
      'localhost:4100',
      '127.0.0.1',
      '127.0.0.1:0',
      '127.0.0.1:70000',
      '[::]:4100',
    ]) {
      expect(() => parseBind(bad)).toThrow('WEB_INTERNAL_BIND');
    }
    const activator = new ContentActivator(join(root, 'instantaneas'), join(root, 'estado'));
    const bound = await listenInternal(
      activator,
      '127.0.0.1:0'.replace(':0', `:${port + 1}`),
    ).catch(() => null);
    if (bound) {
      expect((bound.address() as AddressInfo).address).toBe('127.0.0.1');
      await new Promise((resolve) => bound.close(resolve));
    }
  });

  describe('época y recuperación por HTTP', () => {
    /** Reinicia el puerto interno sobre un estado con control.json dañado: web bloqueada. */
    async function restartBlocked() {
      await raw(port, 'POST', '/interno/reservar', '{"epoch":5}', JSON_TYPE);
      await raw(port, 'POST', '/interno/activar', '{"version":"v1","epoch":5}', JSON_TYPE);
      await new Promise((resolve) => server.close(resolve));
      writeFileSync(join(root, 'estado', 'control.json'), '{roto');
      setServedSnapshots(undefined);
      const activator = new ContentActivator(join(root, 'instantaneas'), join(root, 'estado'));
      await activator.start();
      await new Promise<void>((resolve) => {
        server = createInternalApp(activator).listen(0, '127.0.0.1', () => resolve());
      });
      port = (server.address() as AddressInfo).port;
    }

    it('reservar y activar: época obsoleta o sin reservar → 409', async () => {
      expect((await raw(port, 'POST', '/interno/reservar', '{"epoch":3}', JSON_TYPE)).status).toBe(
        200,
      );
      const stale = await raw(port, 'POST', '/interno/reservar', '{"epoch":2}', JSON_TYPE);
      expect(stale.status).toBe(409);
      expect(JSON.parse(stale.body)).toMatchObject({ resultado: 'época obsoleta', epoca: 3 });
      const unreserved = await raw(
        port,
        'POST',
        '/interno/activar',
        '{"version":"v1","epoch":4}',
        JSON_TYPE,
      );
      expect(JSON.parse(unreserved.body)).toMatchObject({ resultado: 'época no reservada' });
      const old = await raw(
        port,
        'POST',
        '/interno/activar',
        '{"version":"v1","epoch":2}',
        JSON_TYPE,
      );
      expect(old.status).toBe(409);
      expect(peekCurrentSnapshot()).toBeUndefined();
    });

    it.each([
      ['/interno/reservar', '{"epoch":0}'],
      ['/interno/reservar', '{"epoch":-1}'],
      ['/interno/reservar', '{"epoch":1.5}'],
      ['/interno/reservar', '{"epoch":"1"}'],
      ['/interno/reservar', '{"epoch":9007199254740993}'],
      ['/interno/reservar', '{"epoch":1,"extra":1}'],
      ['/interno/activar', '{"version":"v1"}'],
      ['/interno/recuperacion/iniciar', '{"x":1}'],
      ['/interno/recuperacion/completar', '{"epoch":1}'],
      ['/interno/recuperacion/completar', '{"epoch":1,"nonce":1}'],
    ])('%s %s → 400', async (path, body) => {
      expect((await raw(port, 'POST', path, body, JSON_TYPE)).status).toBe(400);
    });

    it('bloqueada: 423 a reservas y activaciones; recuperación con el nonce', async () => {
      await restartBlocked();
      const estado = JSON.parse((await raw(port, 'GET', '/interno/estado')).body);
      expect(estado).toMatchObject({ bloqueada: true, epoca: null, activa: 'v1' });
      for (const [path, body] of [
        ['/interno/reservar', '{"epoch":99}'],
        ['/interno/activar', '{"version":"v1","epoch":99}'],
      ]) {
        const response = await raw(port, 'POST', path, body, JSON_TYPE);
        expect(response.status).toBe(423);
        expect(JSON.parse(response.body).resultado).toBe('recuperación necesaria');
      }
      const begun = JSON.parse(
        (await raw(port, 'POST', '/interno/recuperacion/iniciar', '{}', JSON_TYPE)).body,
      );
      expect(begun.nonce).toMatch(/^[0-9a-f]{32}$/);
      const done = await raw(
        port,
        'POST',
        '/interno/recuperacion/completar',
        JSON.stringify({ epoch: 9, nonce: begun.nonce }),
        JSON_TYPE,
      );
      expect(done.status).toBe(200);
      expect(JSON.parse(done.body)).toEqual({ resultado: 'recuperada', epoca: 9 });
      // Lo antiguo ya no entra.
      expect((await raw(port, 'POST', '/interno/reservar', '{"epoch":5}', JSON_TYPE)).status).toBe(
        409,
      );
      expect(
        (await raw(port, 'POST', '/interno/recuperacion/iniciar', '{}', JSON_TYPE)).status,
      ).toBe(409);
    });

    it('dos completar simultáneos por HTTP con el mismo nonce: exactamente uno 200', async () => {
      await restartBlocked();
      const begun = JSON.parse(
        (await raw(port, 'POST', '/interno/recuperacion/iniciar', '{}', JSON_TYPE)).body,
      );
      const responses = await Promise.all(
        [21, 22].map((epoch) =>
          raw(
            port,
            'POST',
            '/interno/recuperacion/completar',
            JSON.stringify({ epoch, nonce: begun.nonce }),
            JSON_TYPE,
          ),
        ),
      );
      expect(responses.map((r) => r.status).sort()).toEqual([200, 409]);
      const winner = JSON.parse(
        (responses.find((r) => r.status === 200) as RawResponse).body,
      ).epoca;
      const estado = JSON.parse((await raw(port, 'GET', '/interno/estado')).body);
      expect(estado).toMatchObject({ bloqueada: false, epoca: winner });
    });
  });
});
