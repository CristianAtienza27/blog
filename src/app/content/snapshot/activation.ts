import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import { SECTIONS } from '../../layout/navigation';
import { validateContent } from '../records/integrity';
import { COLLECTION_NAMES, type ContentCollections } from '../records/records';
import {
  SnapshotIntegrityError,
  VERSION,
  loadSnapshot,
  peekCurrentSnapshot,
  peekPreviousSnapshot,
  setServedSnapshots,
  type LoadedSnapshot,
} from './snapshot-store';

/**
 * Puerta y activación de versiones en la web (fase 6.3.3). Solo para Node.
 *
 * `estado/activa.json` es la referencia de la versión que sirve la web:
 *
 *   { "schemaVersion": 1, "version": "v12", "contentSha256": "…",
 *     "activatedAt": "2026-11-20T10:00:00.000Z", "previous": "v11" }
 *
 * Activar una versión: (1) cargarla con la comprobación completa del almacén; (2) la puerta,
 * validateContent() con el contrato completo y las mismas opciones que el test del contenido del
 * build; (3) escribir activa.json de forma atómica (temporal, fsync, renombrado, fsync de la
 * carpeta); (4) solo entonces cambiar la versión en memoria. Si (1) o (2) fallan, nada cambia y
 * se devuelven las incidencias. Si (3) falla, nada cambia. Si el proceso cae entre (3) y (4), al
 * arrancar se lee activa.json: manda el puntero.
 *
 * Las activaciones se hacen de una en una. Activar la versión que ya está activa no cambia nada
 * (ni siquiera reescribe el puntero). Nunca se vuelve atrás de forma automática (N3).
 *
 * Época (fencing, fase 6.3.4). La API (PostgreSQL) emite épocas monotónicas; el publicador con el
 * control RESERVA su época y solo puede activar con exactamente la época reservada. La máxima se
 * guarda en estado/control.json. Si control.json está dañado (o falta habiendo activa.json), la
 * web queda BLOQUEADA: rechaza reservas y activaciones hasta una recuperación en dos pasos con un
 * nonce de un solo uso que genera la propia web (iniciar → completar). Todas estas operaciones
 * pasan por la misma cola que las activaciones: cada una (validar, consumir el nonce, escribir
 * control.json) es una sección crítica que termina antes de que empiece la siguiente.
 */

export const POINTER_FILE = 'activa.json';
export const CONTROL_FILE = 'control.json';
const POINTER_SCHEMA = 1;
const CONTROL_SCHEMA = 1;
const NONCE = /^[0-9a-f]{32}$/;
const SHA256 = /^[0-9a-f]{64}$/;

export interface ActivePointer {
  schemaVersion: 1;
  version: string;
  contentSha256: string;
  activatedAt: string;
  previous: string | null;
  /** Época con la que se activó (6.3.4): mínimo para recuperar control.json. */
  epoch?: number;
}

/** estado/control.json: la época máxima reservada. */
interface ControlFile {
  schemaVersion: 1;
  epoch: number;
  updatedAt: string;
}

/** Rechazos por la época: obsoleta, sin reservar o web bloqueada. */
export type EpochRefusal =
  | { outcome: 'stale'; epoch: number }
  | { outcome: 'unreserved'; epoch: number }
  | { outcome: 'blocked'; reason: string };

export type ActivationResult =
  | { outcome: 'activated' | 'unchanged'; pointer: ActivePointer }
  | { outcome: 'rejected'; issues: string[] }
  | EpochRefusal;

export type ReservationResult = { outcome: 'reserved'; epoch: number } | EpochRefusal;

export type RecoveryResult =
  | { outcome: 'recovered'; epoch: number }
  | { outcome: 'not-blocked' }
  | { outcome: 'invalid-nonce' }
  | { outcome: 'below-floor'; floor: number };

type ControlState = { epoch: number; blocked: null } | { epoch: null; blocked: string };

export interface ContentStatus {
  ready: boolean;
  active: string | null;
  previous: string | null;
  contentSha256: string | null;
  activatedAt: string | null;
  issues: readonly string[];
  epoch: number | null;
  blocked: string | null;
}

/** Puntos de prueba: se llaman en cada paso y pueden fallar (solo los tests los usan). */
export interface ActivationHooks {
  afterPointerWritten?: () => void | Promise<void>;
  beforePointerRename?: () => void | Promise<void>;
  /** Antes de renombrar control.json (para simular un fallo de escritura). */
  beforeControlRename?: () => void | Promise<void>;
  /** Dentro de la sección crítica de completar, tras validar el nonce y antes de escribir. */
  duringRecovery?: () => void | Promise<void>;
}

const isEpoch = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

/** La puerta: validateContent() con el contrato interno completo de la versión. */
export async function gate(snapshot: LoadedSnapshot): Promise<string[]> {
  const raw: Partial<Record<keyof ContentCollections, unknown>> = {};
  for (const name of COLLECTION_NAMES) raw[name] = await snapshot.source.collection(name);
  const bodies = new Map<string, unknown>();
  for (const slug of snapshot.slugs) bodies.set(slug, await snapshot.source.body(slug));
  return validateContent(raw as Record<keyof ContentCollections, unknown>, bodies, {
    externalMedia: SECTIONS.map((section) => section.preview),
    retiredMedia: RETIRED_MEDIA,
  });
}

/** Carga y pasa la puerta. Lanza SnapshotIntegrityError con las incidencias. */
async function loadAndGate(root: string, version: string): Promise<LoadedSnapshot> {
  const snapshot = await loadSnapshot(root, version);
  const issues = await gate(snapshot);
  if (issues.length) throw new SnapshotIntegrityError(issues);
  return snapshot;
}

function parsePointer(text: string): ActivePointer {
  let value: Partial<ActivePointer>;
  try {
    value = JSON.parse(text) as Partial<ActivePointer>;
  } catch {
    throw new SnapshotIntegrityError([`${POINTER_FILE}: JSON no válido`]);
  }
  const valid =
    value &&
    typeof value === 'object' &&
    value.schemaVersion === POINTER_SCHEMA &&
    typeof value.version === 'string' &&
    VERSION.test(value.version) &&
    typeof value.contentSha256 === 'string' &&
    SHA256.test(value.contentSha256) &&
    typeof value.activatedAt === 'string' &&
    (value.previous === null ||
      (typeof value.previous === 'string' && VERSION.test(value.previous))) &&
    (value.epoch === undefined || isEpoch(value.epoch));
  if (!valid) throw new SnapshotIntegrityError([`${POINTER_FILE}: formato no válido`]);
  return value as ActivePointer;
}

/** control.json: la época, o el motivo por el que no se puede confiar en ella. */
function parseControl(text: string): number {
  let value: Partial<ControlFile>;
  try {
    value = JSON.parse(text) as Partial<ControlFile>;
  } catch {
    throw new Error(`${CONTROL_FILE}: JSON no válido`);
  }
  if (
    !value ||
    typeof value !== 'object' ||
    value.schemaVersion !== CONTROL_SCHEMA ||
    !isEpoch(value.epoch)
  ) {
    throw new Error(`${CONTROL_FILE}: formato no válido`);
  }
  return value.epoch;
}

export class ContentActivator {
  private queue: Promise<unknown> = Promise.resolve();
  private pointer: ActivePointer | null = null;
  private issues: string[] = [];
  /** Época reservada, o el motivo del bloqueo (sin época de confianza). */
  private control: ControlState = { epoch: 0, blocked: null };
  /** Nonce de la recuperación en curso (solo en memoria: un reinicio la anula). */
  private nonce: string | null = null;

  constructor(
    private readonly snapshotsDir: string,
    private readonly stateDir: string,
    private readonly hooks: ActivationHooks = {},
    private readonly now: () => Date = () => new Date(),
  ) {}

  /**
   * Arranque: lee activa.json, carga la versión, la pasa por la puerta y comprueba que su huella
   * es la del puntero. Nunca lanza: sin versión válida, la web queda sin versión (readiness DOWN)
   * y se recupera activando una por el puerto interno.
   */
  async start(): Promise<ContentStatus> {
    return this.serialize(async () => {
      setServedSnapshots(undefined);
      this.pointer = null;
      this.issues = [];
      this.nonce = null;
      this.control = await this.readControl();
      let text: string;
      try {
        text = await readFile(join(this.stateDir, POINTER_FILE), 'utf8');
      } catch (error) {
        const code = (error as NodeJS.ErrnoException).code;
        this.issues = [
          code === 'ENOENT' ? `no hay ${POINTER_FILE}` : `no se puede leer ${POINTER_FILE}`,
        ];
        return this.status();
      }
      try {
        const pointer = parsePointer(text);
        const active = await loadAndGate(this.snapshotsDir, pointer.version);
        if (active.contentSha256 !== pointer.contentSha256) {
          throw new SnapshotIntegrityError([
            `la huella de ${pointer.version} no coincide con la de ${POINTER_FILE}`,
          ]);
        }
        // La anterior se sirve si sigue siendo correcta; si no, solo se avisa.
        let previous: LoadedSnapshot | undefined;
        if (pointer.previous) {
          previous = await loadAndGate(this.snapshotsDir, pointer.previous).catch(
            (error: unknown) => {
              console.warn(
                `Contenido: la versión anterior ${pointer.previous} no se sirve (${describe(error)})`,
              );
              return undefined;
            },
          );
        }
        setServedSnapshots(active, previous);
        this.pointer = pointer;
      } catch (error) {
        this.issues = issuesOf(error);
      }
      return this.status();
    });
  }

  /**
   * Activa una versión existente con la época reservada. Las activaciones se ejecutan de una en
   * una (y en la misma cola que las reservas y la recuperación).
   */
  activate(version: string, epoch: number): Promise<ActivationResult> {
    return this.serialize(async () => {
      const refusal = this.checkEpoch(epoch);
      if (refusal) return refusal;
      if (!VERSION.test(version)) return { outcome: 'rejected', issues: ['versión no válida'] };
      const active = peekCurrentSnapshot();
      if (active?.version === version && this.pointer) {
        return { outcome: 'unchanged', pointer: this.pointer };
      }
      let snapshot: LoadedSnapshot;
      try {
        snapshot = await loadAndGate(this.snapshotsDir, version);
      } catch (error) {
        if (error instanceof SnapshotIntegrityError)
          return { outcome: 'rejected', issues: [...error.issues] };
        throw error;
      }
      const pointer: ActivePointer = {
        schemaVersion: 1,
        version,
        contentSha256: snapshot.contentSha256,
        activatedAt: this.now().toISOString(),
        previous: active?.version ?? null,
        epoch,
      };
      await this.writePointer(pointer); // si falla, lanza y nada ha cambiado
      await this.hooks.afterPointerWritten?.();
      // Un solo cambio en memoria: la activa pasa a ser la anterior.
      setServedSnapshots(snapshot, active);
      this.pointer = pointer;
      this.issues = [];
      return { outcome: 'activated', pointer };
    });
  }

  status(): ContentStatus {
    const active = peekCurrentSnapshot();
    return {
      ready: Boolean(active),
      active: active?.version ?? null,
      previous: peekPreviousSnapshot()?.version ?? null,
      contentSha256: active?.contentSha256 ?? null,
      activatedAt: this.pointer?.activatedAt ?? null,
      issues: active ? [] : this.issues,
      epoch: this.control.epoch,
      blocked: this.control.blocked,
    };
  }

  /**
   * Reserva una época: solo si es mayor o igual que la guardada (igual: idempotente, sin escribir).
   * Con la web bloqueada, nada.
   */
  reserve(epoch: number): Promise<ReservationResult> {
    return this.serialize(async () => {
      if (this.control.blocked !== null) {
        return { outcome: 'blocked', reason: this.control.blocked };
      }
      if (epoch < this.control.epoch) return { outcome: 'stale', epoch: this.control.epoch };
      if (epoch > this.control.epoch) {
        await this.writeControl(epoch); // si falla, lanza y la época no cambia
        this.control = { epoch, blocked: null };
      }
      return { outcome: 'reserved', epoch };
    });
  }

  /** Recuperación, paso 1: solo bloqueada. Un nonce nuevo anula cualquier anterior. */
  beginRecovery(): Promise<{ outcome: 'started'; nonce: string } | { outcome: 'not-blocked' }> {
    return this.serialize(async () => {
      if (this.control.blocked === null) return { outcome: 'not-blocked' };
      this.nonce = randomBytes(16).toString('hex');
      return { outcome: 'started', nonce: this.nonce };
    });
  }

  /**
   * Recuperación, paso 2. SECCIÓN CRÍTICA (una sola tarea de la cola): validar el nonce,
   * CONSUMIRLO, comprobar el mínimo de activa.json y escribir control.json. Dos completar con el
   * mismo nonce se ejecutan uno detrás de otro: el segundo ya no encuentra el nonce.
   */
  completeRecovery(epoch: number, nonce: string): Promise<RecoveryResult> {
    return this.serialize(async () => {
      if (this.control.blocked === null) return { outcome: 'not-blocked' };
      if (!this.nonceMatches(nonce)) return { outcome: 'invalid-nonce' };
      this.nonce = null; // consumido: pase lo que pase, no vale para otro intento
      await this.hooks.duringRecovery?.();
      const floor = await this.pointerEpoch();
      if (epoch <= floor) return { outcome: 'below-floor', floor };
      await this.writeControl(epoch); // si falla, lanza y sigue bloqueada
      this.control = { epoch, blocked: null };
      return { outcome: 'recovered', epoch };
    });
  }

  private checkEpoch(epoch: number): EpochRefusal | null {
    if (this.control.blocked !== null) {
      return { outcome: 'blocked', reason: this.control.blocked };
    }
    if (epoch < this.control.epoch) return { outcome: 'stale', epoch: this.control.epoch };
    if (epoch > this.control.epoch) return { outcome: 'unreserved', epoch: this.control.epoch };
    return null;
  }

  private nonceMatches(candidate: string): boolean {
    if (this.nonce === null || !NONCE.test(candidate)) return false;
    return timingSafeEqual(Buffer.from(candidate), Buffer.from(this.nonce));
  }

  /** La época de activa.json (mínimo para recuperar), o 0 si no hay o no se puede leer. */
  private async pointerEpoch(): Promise<number> {
    try {
      return parsePointer(await readFile(join(this.stateDir, POINTER_FILE), 'utf8')).epoch ?? 0;
    } catch {
      return 0;
    }
  }

  /** Lee control.json al arrancar. Dañado, o ausente habiendo activa.json: bloqueada. */
  private async readControl(): Promise<ControlState> {
    let text: string;
    try {
      text = await readFile(join(this.stateDir, CONTROL_FILE), 'utf8');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
        return { epoch: null, blocked: `no se puede leer ${CONTROL_FILE}` };
      }
      const hasPointer = await readFile(join(this.stateDir, POINTER_FILE)).then(
        () => true,
        () => false,
      );
      return hasPointer
        ? { epoch: null, blocked: `falta ${CONTROL_FILE} y hay ${POINTER_FILE}` }
        : { epoch: 0, blocked: null };
    }
    try {
      return { epoch: parseControl(text), blocked: null };
    } catch (error) {
      return { epoch: null, blocked: describe(error) };
    }
  }

  private writeControl(epoch: number): Promise<void> {
    const control: ControlFile = { schemaVersion: 1, epoch, updatedAt: this.now().toISOString() };
    return this.writeAtomically(CONTROL_FILE, control, this.hooks.beforeControlRename);
  }

  private writePointer(pointer: ActivePointer): Promise<void> {
    return this.writeAtomically(POINTER_FILE, pointer, this.hooks.beforePointerRename);
  }

  /** Temporal, fsync, renombrado atómico y fsync de la carpeta. Si falla, el archivo no cambia. */
  private async writeAtomically(
    file: string,
    value: unknown,
    beforeRename?: () => void | Promise<void>,
  ): Promise<void> {
    await mkdir(this.stateDir, { recursive: true });
    const target = join(this.stateDir, file);
    const temp = join(this.stateDir, `.${file}.tmp-${randomBytes(6).toString('hex')}`);
    try {
      const handle = await open(temp, 'wx', 0o644);
      try {
        await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, 'utf8');
        await handle.sync();
      } finally {
        await handle.close();
      }
      await beforeRename?.();
      await rename(temp, target);
    } catch (error) {
      await rm(temp, { force: true });
      throw error;
    }
    await syncDirectory(this.stateDir);
  }

  private serialize<T>(task: () => Promise<T>): Promise<T> {
    const run = this.queue.then(task, task);
    this.queue = run.catch(() => undefined);
    return run;
  }
}

/** fsync de la carpeta para que el renombrado sobreviva a un corte (Linux; en Windows se omite). */
async function syncDirectory(dir: string): Promise<void> {
  try {
    const handle = await open(dir, 'r');
    try {
      await handle.sync();
    } finally {
      await handle.close();
    }
  } catch {
    // Windows no permite abrir una carpeta así; el renombrado sigue siendo atómico.
  }
}

function issuesOf(error: unknown): string[] {
  return error instanceof SnapshotIntegrityError ? [...error.issues] : [describe(error)];
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
