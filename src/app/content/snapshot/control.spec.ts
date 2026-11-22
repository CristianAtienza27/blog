import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import { CONTROL_FILE, ContentActivator, POINTER_FILE } from './activation';
import { peekCurrentSnapshot, setServedSnapshots } from './snapshot-store';
import { tempRoot, writeSnapshot } from './snapshot-testing';

/**
 * Época y recuperación de control.json en la web (fase 6.3.4): reservas, activación con la época
 * reservada, estado bloqueado y recuperación en dos pasos con un nonce de un solo uso.
 */
describe('Época de activación y recuperación de control.json', () => {
  let root: string;
  let snapshots: string;
  let state: string;

  beforeEach(() => {
    root = tempRoot();
    snapshots = join(root, 'instantaneas');
    state = join(root, 'estado');
    mkdirSync(snapshots);
    writeSnapshot(snapshots, 'v1', fixtureCollections(), FIXTURE_BODIES);
    writeSnapshot(snapshots, 'v2', fixtureCollections(), FIXTURE_BODIES);
    setServedSnapshots(undefined);
  });
  afterEach(() => {
    setServedSnapshots(undefined);
    rmSync(root, { recursive: true, force: true });
  });

  const control = () => JSON.parse(readFileSync(join(state, CONTROL_FILE), 'utf8'));
  const pointer = () => JSON.parse(readFileSync(join(state, POINTER_FILE), 'utf8'));

  /** Un activador arrancado sobre el estado actual (como tras un reinicio). */
  async function started(hooks = {}) {
    setServedSnapshots(undefined);
    const activator = new ContentActivator(snapshots, state, hooks);
    await activator.start();
    return activator;
  }

  /** Activa v1 con la época 5 y deja el estado listo para dañar control.json. */
  async function withActivation() {
    const activator = await started();
    expect((await activator.reserve(5)).outcome).toBe('reserved');
    expect((await activator.activate('v1', 5)).outcome).toBe('activated');
    expect(pointer().epoch).toBe(5);
    return activator;
  }

  describe('reservas y activación con la época', () => {
    it('una instalación nueva empieza en la época 0; reservar escribe control.json', async () => {
      const activator = await started();
      expect(activator.status()).toMatchObject({ epoch: 0, blocked: null });
      expect(await activator.reserve(3)).toEqual({ outcome: 'reserved', epoch: 3 });
      expect(control()).toMatchObject({ schemaVersion: 1, epoch: 3 });
      // La misma época otra vez: idempotente; una menor: obsoleta.
      expect(await activator.reserve(3)).toEqual({ outcome: 'reserved', epoch: 3 });
      expect(await activator.reserve(2)).toEqual({ outcome: 'stale', epoch: 3 });
      expect(control().epoch).toBe(3);
    });

    it('solo se activa con exactamente la época reservada', async () => {
      const activator = await started();
      await activator.reserve(4);
      expect(await activator.activate('v1', 3)).toEqual({ outcome: 'stale', epoch: 4 });
      expect(await activator.activate('v1', 5)).toEqual({ outcome: 'unreserved', epoch: 4 });
      expect(peekCurrentSnapshot()).toBeUndefined();
      expect((await activator.activate('v1', 4)).outcome).toBe('activated');
      expect(pointer()).toMatchObject({ version: 'v1', epoch: 4 });
    });

    it('la época persiste al reiniciar', async () => {
      await withActivation();
      const restarted = await started();
      expect(restarted.status()).toMatchObject({ epoch: 5, blocked: null, active: 'v1' });
      expect((await restarted.reserve(4)).outcome).toBe('stale');
    });
  });

  describe('control.json dañado: estado bloqueado', () => {
    it.each([
      ['truncado', '{"schemaVersion":1,"ep'],
      ['JSON no válido', 'no es json'],
      ['otro formato', '{"version":5}'],
      ['otra versión del esquema', '{"schemaVersion":2,"epoch":5,"updatedAt":"x"}'],
      ['época negativa', '{"schemaVersion":1,"epoch":-1,"updatedAt":"x"}'],
      ['época no entera', '{"schemaVersion":1,"epoch":5.5,"updatedAt":"x"}'],
      [
        'época mayor que un entero seguro',
        '{"schemaVersion":1,"epoch":9007199254740993,"updatedAt":"x"}',
      ],
      ['época como texto', '{"schemaVersion":1,"epoch":"5","updatedAt":"x"}'],
    ])('%s: bloqueada; rechaza reservas y activaciones; sigue sirviendo', async (_label, text) => {
      await withActivation();
      writeFileSync(join(state, CONTROL_FILE), text);
      const activator = await started();
      expect(activator.status()).toMatchObject({ epoch: null, active: 'v1', ready: true });
      expect(activator.status().blocked).toContain(CONTROL_FILE);
      for (const epoch of [4, 5, 6, 1_000_000_000]) {
        expect((await activator.reserve(epoch)).outcome).toBe('blocked');
        expect((await activator.activate('v2', epoch)).outcome).toBe('blocked');
      }
      expect(peekCurrentSnapshot()?.version).toBe('v1'); // el contenido se sigue sirviendo
      expect(readFileSync(join(state, CONTROL_FILE), 'utf8')).toBe(text); // nada se ha tocado
    });

    it('control.json ilegible (una carpeta en su lugar): bloqueada', async () => {
      await withActivation();
      rmSync(join(state, CONTROL_FILE));
      mkdirSync(join(state, CONTROL_FILE));
      expect((await started()).status().blocked).toBe(`no se puede leer ${CONTROL_FILE}`);
    });

    it('falta control.json pero hay activa.json: bloqueada; faltan los dos: instalación nueva', async () => {
      await withActivation();
      rmSync(join(state, CONTROL_FILE));
      expect((await started()).status().blocked).toBe(
        `falta ${CONTROL_FILE} y hay ${POINTER_FILE}`,
      );
      rmSync(join(state, POINTER_FILE));
      expect((await started()).status()).toMatchObject({ epoch: 0, blocked: null });
    });
  });

  describe('recuperación en dos pasos', () => {
    async function blocked(hooks = {}) {
      await withActivation();
      writeFileSync(join(state, CONTROL_FILE), 'dañado');
      return started(hooks);
    }

    it('iniciar y completar: vuelve a la normalidad con la época nueva; lo antiguo ya no entra', async () => {
      const activator = await blocked();
      // Peticiones antiguas durante el bloqueo: rechazadas.
      expect((await activator.reserve(5)).outcome).toBe('blocked');
      expect((await activator.activate('v2', 5)).outcome).toBe('blocked');

      const begun = await activator.beginRecovery();
      expect(begun.outcome).toBe('started');
      const nonce = begun.outcome === 'started' ? begun.nonce : '';
      expect(nonce).toMatch(/^[0-9a-f]{32}$/);
      expect(await activator.completeRecovery(7, nonce)).toEqual({
        outcome: 'recovered',
        epoch: 7,
      });
      expect(control().epoch).toBe(7);
      expect(activator.status()).toMatchObject({ epoch: 7, blocked: null });

      // Peticiones antiguas en tránsito que llegan DESPUÉS de la recuperación.
      expect(await activator.reserve(6)).toEqual({ outcome: 'stale', epoch: 7 });
      expect(await activator.activate('v2', 6)).toEqual({ outcome: 'stale', epoch: 7 });
      expect(await activator.completeRecovery(8, nonce)).toEqual({ outcome: 'not-blocked' });
      expect(await activator.beginRecovery()).toEqual({ outcome: 'not-blocked' });
      expect(control().epoch).toBe(7);
      expect(peekCurrentSnapshot()?.version).toBe('v1');
      // Y el controlador legítimo sigue: reserva su época y activa.
      expect((await activator.activate('v2', 7)).outcome).toBe('activated');
    });

    it('fuera del estado bloqueado no hay recuperación', async () => {
      const activator = await started();
      expect(await activator.beginRecovery()).toEqual({ outcome: 'not-blocked' });
      expect(await activator.completeRecovery(9, 'a'.repeat(32))).toEqual({
        outcome: 'not-blocked',
      });
    });

    it('un iniciar nuevo anula el nonce anterior (dos recuperaciones cruzadas)', async () => {
      const activator = await blocked();
      const a = await activator.beginRecovery();
      const b = await activator.beginRecovery();
      const nonceA = a.outcome === 'started' ? a.nonce : '';
      const nonceB = b.outcome === 'started' ? b.nonce : '';
      expect(await activator.completeRecovery(8, nonceA)).toEqual({ outcome: 'invalid-nonce' });
      expect(activator.status().blocked).not.toBeNull();
      expect(await activator.completeRecovery(9, nonceB)).toEqual({
        outcome: 'recovered',
        epoch: 9,
      });
    });

    it('un nonce inventado, mal formado o ya usado no vale', async () => {
      const activator = await blocked();
      await activator.beginRecovery();
      for (const nonce of ['0'.repeat(32), 'NO-HEX', '', 'a'.repeat(31), 'a'.repeat(64)]) {
        expect(await activator.completeRecovery(9, nonce)).toEqual({ outcome: 'invalid-nonce' });
      }
    });

    it('la época debe superar la de activa.json; el intento consume el nonce', async () => {
      const activator = await blocked(); // activa.json tiene la época 5
      const begun = await activator.beginRecovery();
      const nonce = begun.outcome === 'started' ? begun.nonce : '';
      expect(await activator.completeRecovery(5, nonce)).toEqual({
        outcome: 'below-floor',
        floor: 5,
      });
      expect(activator.status().blocked).not.toBeNull();
      expect(await activator.completeRecovery(6, nonce)).toEqual({ outcome: 'invalid-nonce' });
    });

    it('un reinicio entre iniciar y completar anula el nonce; una recuperación nueva funciona', async () => {
      const activator = await blocked();
      const begun = await activator.beginRecovery();
      const nonce = begun.outcome === 'started' ? begun.nonce : '';
      void activator;
      const restarted = await started();
      expect(restarted.status().blocked).not.toBeNull();
      expect(await restarted.completeRecovery(8, nonce)).toEqual({ outcome: 'invalid-nonce' });
      const again = await restarted.beginRecovery();
      expect(
        await restarted.completeRecovery(8, again.outcome === 'started' ? again.nonce : ''),
      ).toEqual({ outcome: 'recovered', epoch: 8 });
    });

    it('si falla la escritura de control.json, sigue bloqueada y sin temporales', async () => {
      const activator = await blocked({
        beforeControlRename: () => {
          throw new Error('disco lleno (simulado)');
        },
      });
      const begun = await activator.beginRecovery();
      await expect(
        activator.completeRecovery(8, begun.outcome === 'started' ? begun.nonce : ''),
      ).rejects.toThrow('disco lleno');
      expect(activator.status().blocked).not.toBeNull();
      expect(readFileSync(join(state, CONTROL_FILE), 'utf8')).toBe('dañado');
      expect(readdirSync(state).filter((f) => f.includes('.tmp-'))).toEqual([]);
    });

    it('SECCIÓN CRÍTICA: dos completar simultáneos con el mismo nonce, exactamente uno tiene éxito', async () => {
      // El primero se detiene DENTRO de la sección crítica (nonce validado y consumido, antes de
      // escribir) mientras llega el segundo: si no estuvieran serializados, ambos validarían.
      let release: () => void = () => undefined;
      const pause = new Promise<void>((resolve) => (release = resolve));
      let entered = 0;
      const activator = await blocked({
        duringRecovery: async () => {
          entered++;
          await pause;
        },
      });
      const begun = await activator.beginRecovery();
      const nonce = begun.outcome === 'started' ? begun.nonce : '';

      const first = activator.completeRecovery(11, nonce);
      const second = activator.completeRecovery(12, nonce); // otra época, el mismo nonce
      await new Promise((r) => setTimeout(r, 50));
      expect(entered).toBe(1); // el segundo espera fuera de la sección crítica
      release();
      const results = await Promise.all([first, second]);

      expect(results.filter((r) => r.outcome === 'recovered')).toHaveLength(1);
      expect(results[0]).toEqual({ outcome: 'recovered', epoch: 11 });
      // El segundo no puede validar el nonce: ya está consumido y la web ya no está bloqueada.
      expect(['invalid-nonce', 'not-blocked']).toContain(results[1].outcome);
      expect(entered).toBe(1);
      expect(control().epoch).toBe(11); // una sola época escrita
    });

    it('muchos completar simultáneos con el mismo nonce: exactamente uno', async () => {
      const activator = await blocked();
      const begun = await activator.beginRecovery();
      const nonce = begun.outcome === 'started' ? begun.nonce : '';
      const results = await Promise.all(
        Array.from({ length: 20 }, (_, i) => activator.completeRecovery(100 + i, nonce)),
      );
      const winners = results.filter((r) => r.outcome === 'recovered');
      expect(winners).toHaveLength(1);
      expect(control().epoch).toBe((winners[0] as { epoch: number }).epoch);
    });
  });
});
