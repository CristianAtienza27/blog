/**
 * Muestreador de recursos (fase 6.1.4). Se ejecuta en un contenedor con /sys/fs/cgroup montado
 * en /cg (solo lectura) y lee directamente los archivos del cgroup v2 de cada contenedor medido:
 * no usa `docker exec` ni `docker stats` (que añadirían carga y resolución de ~2 s).
 *
 *   TARGETS="postgres=<id>,api=<id>"  INTERVAL_MS=500  OUT=/out/muestras
 *
 * Escribe OUT.csv (una fila por contenedor y muestra) y, al recibir SIGTERM, OUT.json con el
 * resumen: memoria actual (máx., media, p95), anónima y caché, pico del kernel (memory.peak),
 * CPU (media, p95, máx., en % de una CPU), procesos y eventos OOM.
 */
import fs from 'node:fs';

const targets = (process.env.TARGETS ?? '')
  .split(',')
  .filter(Boolean)
  .map((pair) => {
    const [name, id] = pair.split('=');
    return { name, dir: `/cg/docker/${id}` };
  });
const interval = Number(process.env.INTERVAL_MS ?? 500);
const out = process.env.OUT ?? '/out/muestras';

const read = (file) => {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
};
const num = (file) => {
  const text = read(file);
  return text === null || text.trim() === 'max' ? null : Number(text.trim());
};
const keyed = (file) =>
  Object.fromEntries(
    (read(file) ?? '')
      .trim()
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const [k, v] = line.split(/\s+/);
        // /proc/meminfo escribe «MemAvailable:»; memory.stat y cpu.stat, sin dos puntos.
        return [k.replace(/:$/, ''), Number(v)];
      }),
  );
const meminfo = () => keyed('/proc/meminfo');

const csv = fs.createWriteStream(`${out}.csv`);
csv.write(
  't_ms,servicio,mem_actual,anon,file,kernel,shmem,peak,cpu_pct,pids,oom_kill,vm_disponible_kb\n',
);

const series = Object.fromEntries(
  targets.map((t) => [t.name, { mem: [], anon: [], file: [], cpu: [], pids: [] }]),
);
const last = {};
const first = {};
const start = Date.now();
let vmMinAvailable = Infinity;

function sample() {
  const now = Date.now();
  const vm = meminfo();
  vmMinAvailable = Math.min(vmMinAvailable, vm.MemAvailable ?? Infinity);
  for (const { name, dir } of targets) {
    const current = num(`${dir}/memory.current`);
    if (current === null) continue; // el contenedor se ha detenido o reiniciado
    const stat = keyed(`${dir}/memory.stat`);
    const cpu = keyed(`${dir}/cpu.stat`).usage_usec;
    const events = keyed(`${dir}/memory.events`);
    const peak = num(`${dir}/memory.peak`);
    const pids = num(`${dir}/pids.current`);
    first[name] ??= { events };
    let cpuPct = '';
    if (last[name] && cpu >= last[name].cpu) {
      cpuPct = ((cpu - last[name].cpu) / ((now - last[name].t) * 1000)) * 100;
      series[name].cpu.push(cpuPct);
    }
    last[name] = { t: now, cpu, peak, events, limit: num(`${dir}/memory.max`) };
    const s = series[name];
    s.mem.push(current);
    s.anon.push(stat.anon);
    s.file.push(stat.file);
    s.pids.push(pids);
    csv.write(
      [
        now - start,
        name,
        current,
        stat.anon,
        stat.file,
        stat.kernel,
        stat.shmem,
        peak,
        cpuPct === '' ? '' : cpuPct.toFixed(1),
        pids,
        events.oom_kill ?? 0,
        vm.MemAvailable,
      ].join(',') + '\n',
    );
  }
}

const pct = (values, p) => {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
};
const avg = (values) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : null);
const mib = (bytes) =>
  bytes === null || bytes === undefined ? null : Math.round((bytes / 1048576) * 10) / 10;

function summary() {
  const result = {
    segundos: Math.round((Date.now() - start) / 100) / 10,
    intervaloMs: interval,
    vmDisponibleMinimoMiB: Number.isFinite(vmMinAvailable)
      ? Math.round(vmMinAvailable / 1024)
      : null,
    servicios: {},
  };
  for (const { name } of targets) {
    const s = series[name];
    const l = last[name];
    result.servicios[name] = {
      muestras: s.mem.length,
      limiteMiB: mib(l?.limit),
      memoriaMiB: {
        max: mib(Math.max(...s.mem)),
        media: mib(avg(s.mem)),
        p95: mib(pct(s.mem, 95)),
      },
      anonimaMiB: { max: mib(Math.max(...s.anon)), media: mib(avg(s.anon)) },
      cacheMiB: { max: mib(Math.max(...s.file)) },
      picoKernelMiB: mib(l?.peak),
      cpuPct: {
        media: s.cpu.length ? Math.round(avg(s.cpu) * 10) / 10 : null,
        p95: s.cpu.length ? Math.round(pct(s.cpu, 95) * 10) / 10 : null,
        max: s.cpu.length ? Math.round(Math.max(...s.cpu) * 10) / 10 : null,
      },
      procesosMax: s.pids.length ? Math.max(...s.pids) : null,
      oomKills: (l?.events.oom_kill ?? 0) - (first[name]?.events.oom_kill ?? 0),
      eventosMax: (l?.events.max ?? 0) - (first[name]?.events.max ?? 0),
    };
  }
  return result;
}

sample();
const timer = setInterval(sample, interval);
process.on('SIGTERM', () => {
  clearInterval(timer);
  sample();
  csv.end(() => {
    fs.writeFileSync(`${out}.json`, `${JSON.stringify(summary(), null, 2)}\n`);
    process.exit(0);
  });
});
