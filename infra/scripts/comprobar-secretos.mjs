#!/usr/bin/env node
/**
 * Comprobación de secretos (fase 6.2.8). Desde la raíz del proyecto:
 *
 *   node infra/scripts/comprobar-secretos.mjs [--jar api/target/cms-api-0.1.0-SNAPSHOT.jar]
 *
 * Qué revisa:
 *   1. VALORES REALES: el contenido de cada secreto de infra/local/secrets/ e infra/bench/secrets/,
 *      los valores de las variables sensibles de los .env reales y la credencial de prueba del
 *      entorno de medición (../blog-bench/credenciales). Se buscan byte a byte en TODOS los
 *      archivos del repositorio (versionados o no, incluidos los artefactos generados: api/target,
 *      dist, .angular…) y dentro del JAR de la API y de sus JAR anidados. Los propios archivos
 *      fuente de los secretos se excluyen de la búsqueda.
 *   2. PATRONES GENÉRICOS: claves privadas, tokens de proveedores conocidos y asignaciones de
 *      contraseñas o claves en archivos de configuración. Cada coincidencia se clasifica como falso
 *      positivo justificado (lista FALSOS_POSITIVOS, con su motivo) o como «revisar».
 *
 * Exclusiones de la búsqueda (documentadas): .git (almacén de Git; su contenido versionado ya se
 * revisa en el árbol de trabajo) y node_modules (dependencias de terceros instaladas desde el
 * lockfile, con claves de ejemplo de sus propios tests).
 *
 * Nunca imprime un secreto ni un valor completo: solo la fuente (nombre del archivo o de la
 * variable), el archivo donde aparece y, en los patrones genéricos, un fragmento enmascarado.
 * Código de salida: 0 sin hallazgos; 1 si aparece un valor real o un patrón sin clasificar.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseArgs } from 'node:util';

const REPO = path.resolve(import.meta.dirname, '../..');
const { values: opt } = parseArgs({ options: { jar: { type: 'string' } } });
const SKIP_DIRS = new Set(['.git', 'node_modules']);
const MIN_SECRET = 8;
const CHUNK = 8 * 1024 * 1024;

// --- Fuentes de secretos ------------------------------------------------------------------------

function secretSources() {
  const sources = [];
  const excluded = new Set();
  for (const dir of ['infra/local/secrets', 'infra/bench/secrets']) {
    const abs = path.join(REPO, dir);
    if (!fs.existsSync(abs)) continue;
    for (const name of fs.readdirSync(abs)) {
      const file = path.join(abs, name);
      if (name === 'README.md' || name.endsWith('.example') || !fs.statSync(file).isFile())
        continue;
      excluded.add(path.resolve(file));
      sources.push({ etiqueta: `${dir}/${name}`, valor: trimEnd(fs.readFileSync(file)) });
    }
  }
  for (const envFile of ['infra/local/.env', 'infra/bench/.env', '.env']) {
    const abs = path.join(REPO, envFile);
    if (!fs.existsSync(abs)) continue;
    excluded.add(path.resolve(abs));
    for (const line of fs.readFileSync(abs, 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m && /PASSWORD|SECRET|TOKEN|KEY/.test(m[1]) && m[2]) {
        sources.push({
          etiqueta: `${envFile}:${m[1]}`,
          valor: Buffer.from(m[2].replace(/^["']|["']$/g, '')),
        });
      }
    }
  }
  const cred = path.resolve(REPO, '../blog-bench/credenciales');
  if (fs.existsSync(cred)) {
    for (const name of fs.readdirSync(cred)) {
      const file = path.join(cred, name);
      if (fs.statSync(file).isFile()) {
        sources.push({
          etiqueta: `credencial de prueba del entorno de medición (${name})`,
          valor: trimEnd(fs.readFileSync(file)),
        });
      }
    }
  }
  const usable = sources.filter((s) => s.valor.length >= MIN_SECRET);
  return { sources: usable, cortos: sources.length - usable.length, excluded };
}

function trimEnd(buffer) {
  let end = buffer.length;
  while (end > 0 && (buffer[end - 1] === 0x0a || buffer[end - 1] === 0x0d)) end--;
  return buffer.subarray(0, end);
}

// --- Patrones genéricos y falsos positivos justificados ------------------------------------------

const PATTERNS = [
  {
    id: 'clave-privada-pem',
    re: /-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/g,
  },
  { id: 'aws-access-key', re: /\bAKIA[0-9A-Z]{16}\b/g },
  { id: 'token-github', re: /\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\b/g },
  { id: 'token-slack', re: /\bxox[baprs]-[A-Za-z0-9-]{10,}/g },
  { id: 'clave-api-sk', re: /\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{24,}/g },
  { id: 'clave-google', re: /\bAIza[0-9A-Za-z_-]{35}\b/g },
  { id: 'jwt', re: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/g },
  {
    id: 'asignacion-de-secreto',
    re: /(?:password|passwd|secret|token|api[_-]?key|hmac[_-]?key)["']?\s*[:=]\s*["']?([^\s"'$<>{}(),;]{12,})/gi,
    soloConfiguracion: true,
  },
];
const CONFIG_FILES =
  /\.(?:ya?ml|properties|env|json|conf|ini|toml|sh|psql|sql)$|(?:^|\/)\.env[^/]*$|Dockerfile$/i;

/** Falsos positivos conocidos, cada uno con su motivo. */
const FALSOS_POSITIVOS = [
  {
    id: 'asignacion-de-secreto',
    ruta: /(?:^|\/)(?:api\/src\/test\/resources|api\/target\/test-classes)\/application\.properties$/,
    motivo:
      'clave HMAC de los tests, pública y documentada como no secreta («no-secreta» en el valor)',
  },
  {
    id: 'asignacion-de-secreto',
    ruta: /(?:^|\/)api\/target\/(?:classes|cms-api[^/]*\.jar!)/,
    texto: /\$\{|configtree|cms\.db\.password\}/,
    motivo: 'referencia a una variable o secreto montado, no un valor',
  },
  {
    id: 'asignacion-de-secreto',
    ruta: /\.(?:ya?ml)$/,
    texto: /PASSWORD_FILE|_FILE:|password: \$\{/,
    motivo: 'ruta o variable de un secreto montado, no un valor',
  },
  {
    id: 'asignacion-de-secreto',
    ruta: /!\/BOOT-INF\/lib\/spring-security-core-[^/]+\.jar!\/org\/springframework\/security\/messages(?:_[A-Za-z_]+)?\.properties$/,
    motivo: 'mensajes de error traducidos de Spring Security (textos de la biblioteca, no valores)',
  },
];

/**
 * Una cabecera PEM es una clave solo si la sigue su cuerpo en base64. Las bibliotecas (Spring
 * Security, Tomcat) contienen la cabecera como constante para RECONOCER claves, sin material.
 */
function pemWithoutKey(text, index, header) {
  const after = text.slice(index + header.length, index + header.length + 120);
  return !/^[\s\\rn"]*[A-Za-z0-9+/]{40,}/.test(after);
}

function classify(id, rel, line, text, index, match) {
  if (id === 'clave-privada-pem' && pemWithoutKey(text, index, match)) {
    return 'cabecera PEM sin material de clave detrás (constante para reconocer claves)';
  }
  for (const fp of FALSOS_POSITIVOS) {
    if (fp.id === id && fp.ruta.test(rel) && (!fp.texto || fp.texto.test(line))) return fp.motivo;
  }
  return null;
}

const mask = (s) => (s.length <= 8 ? '…' : `${s.slice(0, 4)}…(${s.length} caracteres)`);

// --- Recorrido ----------------------------------------------------------------------------------

function* walk(dir, rel = '') {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) continue;
    const abs = path.join(dir, entry.name);
    const r = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) yield* walk(abs, r);
    } else if (entry.isFile()) {
      yield { abs, rel: r };
    }
  }
}

/** Busca cada valor real en el archivo por bloques (con solape: no se pierde un valor partido). */
function findValues(abs, sources) {
  const hits = new Map();
  const maxLen = Math.max(0, ...sources.map((s) => s.valor.length));
  const fd = fs.openSync(abs, 'r');
  try {
    const buffer = Buffer.alloc(CHUNK + maxLen);
    let carry = 0;
    let read;
    while ((read = fs.readSync(fd, buffer, carry, CHUNK, null)) > 0) {
      const view = buffer.subarray(0, carry + read);
      for (const s of sources) {
        let at = view.indexOf(s.valor);
        while (at !== -1) {
          hits.set(s.etiqueta, (hits.get(s.etiqueta) ?? 0) + 1);
          at = view.indexOf(s.valor, at + 1);
        }
      }
      carry = Math.min(maxLen - 1, view.length);
      if (carry > 0) view.copy(buffer, 0, view.length - carry);
    }
  } finally {
    fs.closeSync(fd);
  }
  return hits;
}

function scanFile(abs, rel, sources, report) {
  report.archivos++;
  for (const [etiqueta, n] of findValues(abs, sources))
    report.valoresReales.push({ fuente: etiqueta, archivo: rel, coincidencias: n });
  const size = fs.statSync(abs).size;
  if (size > 20 * 1024 * 1024) return;
  const text = fs.readFileSync(abs, 'latin1');
  const lines = text.split('\n');
  for (const p of PATTERNS) {
    if (
      p.soloConfiguracion &&
      !CONFIG_FILES.test(rel.replace(/!.*$/, '')) &&
      !CONFIG_FILES.test(rel)
    )
      continue;
    p.re.lastIndex = 0;
    for (const m of text.matchAll(p.re)) {
      const lineNo = text.slice(0, m.index).split('\n').length;
      const line = lines[lineNo - 1] ?? '';
      const motivo = classify(p.id, rel, line, text, m.index, m[0]);
      const entry = { patron: p.id, archivo: rel, linea: lineNo, fragmento: mask(m[1] ?? m[0]) };
      if (motivo) report.falsosPositivos.push({ ...entry, motivo });
      else report.revisar.push(entry);
    }
  }
}

function scanJar(jar, sources, report) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'comprobar-secretos-'));
  try {
    const extract = (file, dest) => {
      fs.mkdirSync(dest, { recursive: true });
      const res = spawnSync('jar', ['xf', file], { cwd: dest, encoding: 'utf8' });
      if (res.status !== 0)
        throw new Error(`no se pudo extraer ${path.basename(file)}: ${res.stderr}`);
    };
    const outer = path.join(tmp, 'jar');
    extract(path.resolve(jar), outer);
    const relJar = path.relative(REPO, path.resolve(jar)).replaceAll('\\', '/');
    let nested = 0;
    for (const { abs, rel } of walk(outer)) {
      if (rel.endsWith('.jar')) {
        nested++;
        const dest = path.join(tmp, 'anidado', String(nested));
        extract(abs, dest);
        for (const inner of walk(dest))
          scanFile(inner.abs, `${relJar}!/${rel}!/${inner.rel}`, sources, report);
      }
      scanFile(abs, `${relJar}!/${rel}`, sources, report);
    }
    return nested;
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

// --- Principal ------------------------------------------------------------------------------------

const { sources, cortos, excluded } = secretSources();
const report = { archivos: 0, valoresReales: [], falsosPositivos: [], revisar: [] };
for (const { abs, rel } of walk(REPO)) {
  if (excluded.has(path.resolve(abs))) continue;
  scanFile(abs, rel, sources, report);
}
const jar =
  opt.jar ??
  fs
    .readdirSync(path.join(REPO, 'api/target'), { withFileTypes: true })
    .filter((e) => e.isFile() && /^cms-api-.*\.jar$/.test(e.name))
    .map((e) => path.join('api/target', e.name))[0];
let nestedJars = null;
if (jar) nestedJars = scanJar(path.join(REPO, jar), sources, report);

const summary = {
  fuentesDeSecretos: sources.map((s) => s.etiqueta),
  fuentesDemasiadoCortasParaBuscar: cortos,
  archivosRevisados: report.archivos,
  jar: jar ?? 'no encontrado (compila primero: mvnw package)',
  jarsAnidadosRevisados: nestedJars,
  excluidos: [
    '.git',
    'node_modules',
    ...[...excluded].map((p) => path.relative(REPO, p).replaceAll('\\', '/')),
  ],
};
console.log('Comprobación de secretos');
console.log(JSON.stringify(summary, null, 2));
console.log(`\nValores reales encontrados fuera de sus fuentes: ${report.valoresReales.length}`);
for (const h of report.valoresReales)
  console.log(`  ✗ ${h.fuente} → ${h.archivo} (${h.coincidencias})`);
console.log(`\nPatrones genéricos sin clasificar (revisar): ${report.revisar.length}`);
for (const h of report.revisar)
  console.log(`  ? ${h.patron} → ${h.archivo}:${h.linea} ${h.fragmento}`);
console.log(`\nFalsos positivos justificados: ${report.falsosPositivos.length}`);
const grouped = new Map();
for (const h of report.falsosPositivos) {
  const key = `${h.patron} · ${h.motivo}`;
  grouped.set(key, [...(grouped.get(key) ?? []), `${h.archivo}:${h.linea}`]);
}
for (const [key, where] of grouped)
  console.log(
    `  · ${key}\n      ${where.slice(0, 6).join('\n      ')}${where.length > 6 ? `\n      … y ${where.length - 6} más` : ''}`,
  );
const ok = report.valoresReales.length === 0 && report.revisar.length === 0 && !!jar;
console.log(`\nResultado: ${ok ? 'SIN HALLAZGOS' : 'HAY HALLAZGOS'}`);
process.exit(ok ? 0 : 1);
