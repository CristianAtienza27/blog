import { TransferState } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { createReloadOnce, snapshotBrowserSource } from './content-source.snapshot';
import { SNAPSHOT_VERSION_KEY } from './snapshot-version';

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('Fuente de instantáneas en el navegador', () => {
  let state: TransferState;
  beforeEach(() => {
    state = TestBed.inject(TransferState);
  });

  it('pide la versión que fijó el servidor (TransferState), sin consultar cuál se sirve', async () => {
    state.set(SNAPSHOT_VERSION_KEY, 'v7');
    const fetcher = vi.fn(async (url: string) =>
      json(url.endsWith('tags.json') ? [{ id: 'a' }] : {}),
    );
    const source = snapshotBrowserSource(state, fetcher);

    expect(await source.collection('tags')).toEqual([{ id: 'a' }]);
    await source.body('paso-stelvio');
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      '/content/v7/tags.json',
      '/content/v7/articles/paso-stelvio.json',
    ]);
  });

  it('en una página prerenderizada, pregunta una vez la versión servida', async () => {
    const fetcher = vi.fn(async (url: string) =>
      url === '/content/version.json' ? json({ version: 'v3' }) : json([]),
    );
    const source = snapshotBrowserSource(state, fetcher);
    await Promise.all([source.collection('media'), source.collection('tags')]);
    expect(fetcher.mock.calls.filter(([url]) => url === '/content/version.json')).toHaveLength(1);
    expect(fetcher).toHaveBeenCalledWith('/content/version.json', { cache: 'no-store' });
    expect(fetcher).toHaveBeenCalledWith('/content/v3/media.json');
  });

  it('rechaza una versión con un formato no válido y permite reintentar', async () => {
    const responses = [json({ version: '../v1' }), json({ version: 'v2' }), json([])];
    const fetcher = vi.fn(async () => responses.shift() as Response);
    const source = snapshotBrowserSource(state, fetcher);
    await expect(source.collection('tags')).rejects.toThrow('versión del contenido');
    expect(await source.collection('tags')).toEqual([]);
    expect(fetcher).toHaveBeenLastCalledWith('/content/v2/tags.json');

    // Una versión transferida con mal formato se ignora.
    state.set(SNAPSHOT_VERSION_KEY, 'v1/../x');
    const other = vi.fn(async (url: string) =>
      url.endsWith('version.json') ? json({ version: 'v4' }) : json([]),
    );
    await snapshotBrowserSource(state, other).collection('tags');
    expect(other).toHaveBeenLastCalledWith('/content/v4/tags.json');
  });

  it('nunca construye una ruta con un slug o una colección fuera del contrato', async () => {
    state.set(SNAPSHOT_VERSION_KEY, 'v1');
    const fetcher = vi.fn(async () => json({}));
    const source = snapshotBrowserSource(state, fetcher);
    for (const slug of ['../media', 'a/b', 'a\\b', '%2e%2e', 'Mayus', '', 'a\0b']) {
      expect(await source.body(slug)).toBeNull();
    }
    await expect(source.collection('manifest' as never)).rejects.toThrow('Colección desconocida');
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('un artículo que no existe es null; cualquier otro error se propaga', async () => {
    state.set(SNAPSHOT_VERSION_KEY, 'v1');
    const source = snapshotBrowserSource(state, async (url) =>
      url.includes('no-existe') ? json({}, 404) : json({}, 500),
    );
    expect(await source.body('no-existe')).toBeNull();
    await expect(source.body('otro')).rejects.toThrow('(500)');
    await expect(source.collection('tags')).rejects.toThrow('(500)');
  });

  it('descarga cada cuerpo una sola vez, y reintenta si falla', async () => {
    state.set(SNAPSHOT_VERSION_KEY, 'v1');
    let fail = true;
    const fetcher = vi.fn(async () => {
      if (fail) {
        fail = false;
        return json({}, 503);
      }
      return json({ slug: 'a', blocks: [] });
    });
    const source = snapshotBrowserSource(state, fetcher);
    await expect(source.body('a')).rejects.toThrow('(503)');
    const [first, second] = await Promise.all([source.body('a'), source.body('a')]);
    expect(first).toEqual({ slug: 'a', blocks: [] });
    expect(second).toBe(first);
    expect(await source.body('a')).toBe(first);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('una versión retirada (410): avisa para recargar y no da contenido', async () => {
    state.set(SNAPSHOT_VERSION_KEY, 'v1');
    const retired = vi.fn();
    const source = snapshotBrowserSource(state, async () => json({}, 410), retired);
    await expect(source.collection('tags')).rejects.toThrow('se ha retirado');
    await expect(source.body('a')).rejects.toThrow('se ha retirado');
    expect(retired).toHaveBeenCalledWith('v1');
  });

  it('recarga una sola vez por versión (sin bucles) y nunca sin sessionStorage', () => {
    const marks = new Map<string, string>();
    const storage = {
      getItem: (k: string) => marks.get(k) ?? null,
      setItem: (k: string, v: string) => void marks.set(k, v),
    };
    const reload = vi.fn();
    const once = createReloadOnce(() => storage, reload);
    once('v1');
    once('v1');
    once('v1');
    expect(reload).toHaveBeenCalledTimes(1);
    once('v2');
    expect(reload).toHaveBeenCalledTimes(2);

    const withoutStorage = createReloadOnce(() => {
      throw new Error('sin almacenamiento');
    }, reload);
    withoutStorage('v3');
    expect(reload).toHaveBeenCalledTimes(2);
  });
});
