// Utilidades comunes de los escenarios k6 (fase 6.1.4).
//
// Variables de entorno:
//   VUS + DURATION   modelo cerrado (usuarios simultáneos): mide la capacidad
//   RATE + DURATION  modelo abierto (peticiones por segundo fijas): mide una carga objetivo
//   NAME             nombre del resultado (/out/<NAME>.json)
//
// Corte automático: si fallan más del 5 % de las peticiones o el p95 supera 3 s, la prueba se
// detiene (protege el equipo y evita medir un sistema ya saturado).

export const TIMEOUT = '10s';

/** Umbral neutro por tipo: k6 solo resume las submétricas etiquetadas que tienen un umbral. */
export function byType(...tipos) {
  return Object.fromEntries(tipos.map((t) => [`http_req_duration{tipo:${t}}`, ['max>=0']]));
}

export function options(extra = {}) {
  const duration = __ENV.DURATION || '60s';
  const scenario = __ENV.RATE
    ? {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.RATE),
        timeUnit: '1s',
        duration,
        preAllocatedVUs: Number(__ENV.MAX_VUS || 50),
        maxVUs: Number(__ENV.MAX_VUS || 50),
      }
    : { executor: 'constant-vus', vus: Number(__ENV.VUS || 1), duration };
  return {
    scenarios: { carga: scenario },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: {
      ...(extra.porTipo ? byType(...extra.porTipo) : {}),
      http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '10s' }],
      http_req_duration: [{ threshold: 'p(95)<3000', abortOnFail: true, delayAbortEval: '10s' }],
    },
    discardResponseBodies: true,
    noConnectionReuse: false,
    ...Object.fromEntries(Object.entries(extra).filter(([k]) => k !== 'porTipo')),
  };
}

const ms = (value) => (value === undefined ? null : Math.round(value * 10) / 10);

function trend(metric) {
  if (!metric) return null;
  const v = metric.values;
  return {
    media: ms(v.avg),
    p50: ms(v.med),
    p90: ms(v['p(90)']),
    p95: ms(v['p(95)']),
    p99: ms(v['p(99)']),
    max: ms(v.max),
  };
}

/** Resumen compacto: latencias, peticiones por segundo, errores y tiempos agotados. */
export function summarize(data) {
  const m = data.metrics;
  const out = {
    nombre: __ENV.NAME || 'k6',
    modelo: __ENV.RATE ? `${__ENV.RATE} pet/s` : `${__ENV.VUS || 1} usuarios`,
    duracionPedida: __ENV.DURATION || '60s',
    peticiones: m.http_reqs ? m.http_reqs.values.count : 0,
    porSegundo: m.http_reqs ? ms(m.http_reqs.values.rate) : 0,
    errores: m.http_req_failed ? m.http_req_failed.values.passes : 0,
    tasaError: m.http_req_failed ? ms(m.http_req_failed.values.rate * 100) : 0,
    timeouts: m.timeouts ? m.timeouts.values.count : 0,
    iteracionesDescartadas: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
    latenciaMs: trend(m.http_req_duration),
    abortado: Object.values(data.metrics).some(
      (x) => x.thresholds && Object.values(x.thresholds).some((t) => !t.ok),
    ),
    porTipo: {},
  };
  for (const [key, metric] of Object.entries(m)) {
    const match = key.match(/^http_req_duration\{tipo:(.+)\}$/);
    if (match) out.porTipo[match[1]] = trend(metric);
    // Contadores de estado de los escenarios de autenticación (6.2.7): estado_<tipo>_<código>.
    if (key.startsWith('estado_')) (out.estados ??= {})[key.slice(7)] = metric.values.count;
    if (key.startsWith('ms_')) (out.duraciones ??= {})[key.slice(3)] = trend(metric);
  }
  return out;
}

export function handleSummaryTo(data) {
  const result = summarize(data);
  return {
    [`/out/${result.nombre}.json`]: JSON.stringify(result, null, 2) + '\n',
    stdout: `${JSON.stringify(result)}\n`,
  };
}
