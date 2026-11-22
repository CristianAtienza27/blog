#!/usr/bin/env node
/**
 * Contenido sintético para estimar el crecimiento (fase 6.1.4).
 *
 *   node infra/bench/scripts/growth.mjs <factor> <carpeta de salida fuera del repositorio>
 *
 * Copia src/content (solo lectura) y clona cada artículo factor-1 veces con id y slug nuevos
 * («<slug>-c<k>»), el mismo cuerpo y las mismas referencias. El orden editorial se renumera por
 * sección y los clones no son destacados. No crea imágenes: reutiliza las del catálogo, así que
 * mide el crecimiento de artículos y revisiones, no el de fotografías.
 */
import fs from 'node:fs';
import path from 'node:path';

const REPO = path.resolve(import.meta.dirname, '../../..');
const [factorArg, outArg] = process.argv.slice(2);
const factor = Number(factorArg);
if (!Number.isInteger(factor) || factor < 2 || !outArg) {
  console.error('uso: growth.mjs <factor ≥ 2> <carpeta de salida>');
  process.exit(1);
}
const out = path.resolve(outArg);
const rel = path.relative(REPO, out);
if (!rel.startsWith('..') && !path.isAbsolute(rel)) {
  console.error('la salida debe estar fuera del repositorio');
  process.exit(1);
}

const source = path.join(REPO, 'src/content');
fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(source, out, { recursive: true });

const articles = JSON.parse(fs.readFileSync(path.join(source, 'articles.json'), 'utf8'));
const nextOrder = {};
for (const a of articles) nextOrder[a.category] = Math.max(nextOrder[a.category] ?? 0, a.order);

const result = [...articles];
for (let k = 1; k < factor; k++) {
  for (const a of articles) {
    const slug = `${a.slug}-c${k}`;
    nextOrder[a.category] += 1;
    result.push({ ...a, id: slug, slug, order: nextOrder[a.category], featured: false });
    const body = JSON.parse(
      fs.readFileSync(path.join(source, 'articles', `${a.slug}.json`), 'utf8'),
    );
    fs.writeFileSync(
      path.join(out, 'articles', `${slug}.json`),
      `${JSON.stringify({ ...body, slug }, null, 2)}\n`,
    );
  }
}
fs.writeFileSync(path.join(out, 'articles.json'), `${JSON.stringify(result, null, 2)}\n`);
console.log(`${result.length} artículos en ${out}`);
