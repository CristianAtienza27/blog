import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import {
  CONTROL_FILE,
  ContentActivator,
  POINTER_FILE,
  gate,
  type ActivePointer,
} from './activation';
import {
  loadSnapshot,
  peekCurrentSnapshot,
  peekPreviousSnapshot,
  servedSnapshot,
  setServedSnapshots,
} from './snapshot-store';
import { tempRoot, withPrivateOriginals, writeSnapshot } from './snapshot-testing';

function validCollections(title?: string) {
  const data = withPrivateOriginals(fixtureCollections());
  if (title) {
    data.articles = data.articles.map((a, i) => (i === 0 ? { ...a, title } : a));
  }
  return data;
}

/** Una versión que pasa la comprobación de integridad pero no la puerta (un tag inexistente). */
function invalidCollections() {
  const data = validCollections();
  data.articles = data.articles.map((a, i) => (i === 0 ? { ...a, tags: ['no-existe'] } : a));
  return data;
}

/** Reserva la época 1 (idempotente) y activa con ella, como hace la API con su época. */
async function activateWithEpoch(activator: ContentActivator, version: string) {
  await activator.reserve(1);
  return activator.activate(version, 1);
}

describe('Activación de versiones (puerta y activa.json)', () => {
  let root: string;
  let snapshots: string;
  let state: string;

  beforeEach(() => {
    root = tempRoot();
    snapshots = join(root, 'instantaneas');
    state = join(root, 'estado');
    mkdirSync(snapshots);
    setServedSnapshots(undefined);
  });
  afterEach(() => {
    setServedSnapshots(undefined);
    rmSync(root, { recursive: true, force: true });
  });

  const pointer = (): ActivePointer => JSON.parse(readFileSync(join(state, POINTER_FILE), 'utf8'));

  it('los datos de prueba pasan la puerta (control)', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    expect(await gate(await loadSnapshot(snapshots, 'v1'))).toEqual([]);
    writeSnapshot(snapshots, 'v2', invalidCollections(), FIXTURE_BODIES);
    expect((await gate(await loadSnapshot(snapshots, 'v2'))).length).toBeGreaterThan(0);
  });

  it('activa una versión: puntero atómico y después memoria; la anterior se sigue sirviendo', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    writeSnapshot(snapshots, 'v2', validCollections('Segunda'), FIXTURE_BODIES);
    const activator = new ContentActivator(
      snapshots,
      state,
      {},
      () => new Date('2026-11-20T10:00:00Z'),
    );

    const first = await activateWithEpoch(activator, 'v1');
    expect(first.outcome).toBe('activated');
    expect(pointer()).toEqual({
      schemaVersion: 1,
      version: 'v1',
      contentSha256: peekCurrentSnapshot()?.contentSha256,
      activatedAt: '2026-11-20T10:00:00.000Z',
      previous: null,
      epoch: 1,
    });

    const second = await activateWithEpoch(activator, 'v2');
    expect(second.outcome).toBe('activated');
    expect(pointer()).toMatchObject({ version: 'v2', previous: 'v1' });
    expect(peekCurrentSnapshot()?.version).toBe('v2');
    expect(peekPreviousSnapshot()?.version).toBe('v1');
    expect(servedSnapshot('v1')).toBeDefined();
    // Sin temporales en estado/.
    expect(readdirSync(state).sort()).toEqual([CONTROL_FILE, POINTER_FILE].sort());
    expect(activator.status()).toMatchObject({ ready: true, active: 'v2', previous: 'v1' });
  });

  it('una versión que no pasa la puerta, o que no existe o está alterada, no cambia nada', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    writeSnapshot(snapshots, 'v2', invalidCollections(), FIXTURE_BODIES);
    const altered = writeSnapshot(snapshots, 'v3', validCollections(), FIXTURE_BODIES);
    writeFileSync(join(altered, 'tags.json'), '[]');
    const activator = new ContentActivator(snapshots, state);
    await activateWithEpoch(activator, 'v1');
    const before = readFileSync(join(state, POINTER_FILE), 'utf8');

    for (const version of ['v2', 'v3', 'v9', '../v1', 'v0']) {
      const result = await activateWithEpoch(activator, version);
      expect(result.outcome).toBe('rejected');
      expect(peekCurrentSnapshot()?.version).toBe('v1');
      expect(readFileSync(join(state, POINTER_FILE), 'utf8')).toBe(before);
    }
    const rejected = await activateWithEpoch(activator, 'v2');
    expect(rejected).toMatchObject({ outcome: 'rejected' });
    expect(rejected.outcome === 'rejected' && rejected.issues.join()).toContain('no-existe');
  });

  it('activar la versión activa no cambia nada, ni reescribe el puntero', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    const activator = new ContentActivator(snapshots, state);
    await activateWithEpoch(activator, 'v1');
    const before = statSync(join(state, POINTER_FILE)).mtimeMs;
    const text = readFileSync(join(state, POINTER_FILE), 'utf8');
    await new Promise((r) => setTimeout(r, 20));
    const again = await activateWithEpoch(activator, 'v1');
    expect(again.outcome).toBe('unchanged');
    expect(statSync(join(state, POINTER_FILE)).mtimeMs).toBe(before);
    expect(readFileSync(join(state, POINTER_FILE), 'utf8')).toBe(text);
  });

  it('si falla la escritura del puntero, nada cambia (ni puntero ni memoria ni temporales)', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    writeSnapshot(snapshots, 'v2', validCollections('Segunda'), FIXTURE_BODIES);
    let fail = false;
    const activator = new ContentActivator(snapshots, state, {
      beforePointerRename: () => {
        if (fail) throw new Error('disco lleno (simulado)');
      },
    });
    await activateWithEpoch(activator, 'v1');
    const before = readFileSync(join(state, POINTER_FILE), 'utf8');
    fail = true;
    await expect(activateWithEpoch(activator, 'v2')).rejects.toThrow('disco lleno');
    expect(readFileSync(join(state, POINTER_FILE), 'utf8')).toBe(before);
    expect(peekCurrentSnapshot()?.version).toBe('v1');
    expect(readdirSync(state).sort()).toEqual([CONTROL_FILE, POINTER_FILE].sort());
    // Y la siguiente activación funciona.
    fail = false;
    expect((await activateWithEpoch(activator, 'v2')).outcome).toBe('activated');
  });

  it('una caída entre escribir el puntero y cambiar la memoria: al arrancar manda el puntero', async () => {
    writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
    writeSnapshot(snapshots, 'v2', validCollections('Segunda'), FIXTURE_BODIES);
    const crashing = new ContentActivator(snapshots, state, {
      afterPointerWritten: () => {
        throw new Error('caída simulada');
      },
    });
    await activateWithEpoch(new ContentActivator(snapshots, state), 'v1');
    await expect(activateWithEpoch(crashing, 'v2')).rejects.toThrow('caída simulada');
    expect(peekCurrentSnapshot()?.version).toBe('v1'); // la memoria no llegó a cambiar
    expect(pointer().version).toBe('v2');

    // Un proceso nuevo: lee activa.json y sirve v2 (con v1 como anterior).
    setServedSnapshots(undefined);
    const restarted = await new ContentActivator(snapshots, state).start();
    expect(restarted).toMatchObject({ ready: true, active: 'v2', previous: 'v1' });
  });

  it('las activaciones simultáneas se hacen de una en una', async () => {
    for (const v of ['v1', 'v2', 'v3'])
      writeSnapshot(snapshots, v, validCollections(v), FIXTURE_BODIES);
    const activator = new ContentActivator(snapshots, state);
    const results = await Promise.all(
      ['v1', 'v2', 'v3', 'v2'].map((v) => activateWithEpoch(activator, v)),
    );
    expect(results.map((r) => r.outcome)).toEqual([
      'activated',
      'activated',
      'activated',
      'activated',
    ]);
    // En orden de llegada: la última fue v2, con v3 como anterior.
    expect(pointer()).toMatchObject({ version: 'v2', previous: 'v3' });
    expect(peekCurrentSnapshot()?.version).toBe('v2');
    expect(peekPreviousSnapshot()?.version).toBe('v3');
  });

  describe('arranque', () => {
    it('sin activa.json: sin versión (readiness DOWN), y se recupera activando', async () => {
      writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
      const activator = new ContentActivator(snapshots, state);
      expect(await activator.start()).toMatchObject({
        ready: false,
        active: null,
        issues: [`no hay ${POINTER_FILE}`],
      });
      expect(peekCurrentSnapshot()).toBeUndefined();
      expect((await activateWithEpoch(activator, 'v1')).outcome).toBe('activated');
      expect(activator.status().ready).toBe(true);
    });

    it.each([
      ['un puntero que no es JSON', () => 'no es json', `${POINTER_FILE}: JSON no válido`],
      [
        'un puntero con otro formato',
        () => JSON.stringify({ version: '../v1' }),
        `${POINTER_FILE}: formato no válido`,
      ],
    ])('%s: sin versión, sin volver atrás', async (_label, text, issue) => {
      writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
      mkdirSync(state);
      writeFileSync(join(state, POINTER_FILE), text());
      expect(await new ContentActivator(snapshots, state).start()).toMatchObject({
        ready: false,
        issues: [issue],
      });
    });

    it('una versión que ya no pasa la puerta, o cuya huella no es la del puntero: sin versión (N3)', async () => {
      writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
      writeSnapshot(snapshots, 'v2', validCollections('Segunda'), FIXTURE_BODIES);
      const activator = new ContentActivator(snapshots, state);
      await activateWithEpoch(activator, 'v1');
      await activateWithEpoch(activator, 'v2');

      // La huella del puntero no coincide (la carpeta se sustituyó por otra versión correcta).
      const p = pointer();
      writeFileSync(
        join(state, POINTER_FILE),
        JSON.stringify({ ...p, contentSha256: '0'.repeat(64) }),
      );
      setServedSnapshots(undefined);
      const mismatch = await new ContentActivator(snapshots, state).start();
      expect(mismatch.ready).toBe(false);
      expect(mismatch.issues.join()).toContain('no coincide');

      // La versión activa alterada en el disco: no se vuelve a v1 automáticamente.
      writeFileSync(join(state, POINTER_FILE), JSON.stringify(p));
      writeFileSync(join(snapshots, 'v2', 'tags.json'), '[]');
      const altered = await new ContentActivator(snapshots, state).start();
      expect(altered).toMatchObject({ ready: false, active: null });
      expect(peekCurrentSnapshot()).toBeUndefined();
    });

    it('la anterior alterada: se sirve la activa y la anterior no', async () => {
      writeSnapshot(snapshots, 'v1', validCollections(), FIXTURE_BODIES);
      writeSnapshot(snapshots, 'v2', validCollections('Segunda'), FIXTURE_BODIES);
      const activator = new ContentActivator(snapshots, state);
      await activateWithEpoch(activator, 'v1');
      await activateWithEpoch(activator, 'v2');
      writeFileSync(join(snapshots, 'v1', 'tags.json'), '[]');
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      setServedSnapshots(undefined);
      expect(await new ContentActivator(snapshots, state).start()).toMatchObject({
        ready: true,
        active: 'v2',
        previous: null,
      });
      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });

    it('un volumen vacío y sin estado: sin versión y sin crear nada', async () => {
      const activator = new ContentActivator(snapshots, state);
      expect((await activator.start()).ready).toBe(false);
      expect(existsSync(state)).toBe(false);
      expect((await activateWithEpoch(activator, 'v1')).outcome).toBe('rejected');
      // Copiar una versión al volumen y activarla: recuperación.
      const source = tempRoot();
      writeSnapshot(source, 'v1', validCollections(), FIXTURE_BODIES);
      cpSync(join(source, 'v1'), join(snapshots, 'v1'), { recursive: true });
      rmSync(source, { recursive: true, force: true });
      expect((await activateWithEpoch(activator, 'v1')).outcome).toBe('activated');
    });
  });
});
