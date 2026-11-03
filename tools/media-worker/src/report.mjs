import fs from 'node:fs/promises';
import path from 'node:path';

const kb = (bytes) =>
  bytes == null
    ? '—'
    : bytes >= 1e6
      ? `${(bytes / 1e6).toFixed(2)} MB`
      : `${Math.round(bytes / 1e3)} kB`;
const esc = (value) =>
  String(value ?? '—').replace(
    /[&<>"]/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c],
  );
const yes = (ok) => (ok ? '<span class="ok">sí</span>' : '<span class="ko">no</span>');

function originalRow(label, o) {
  if (!o) return '';
  return `<tr><th>${label}</th><td>${esc(o.name)}</td><td>${esc(o.format)}</td>
    <td>${o.width ? `${o.width} × ${o.height}` : '—'}${o.megapixels ? ` (${o.megapixels} MP)` : ''}</td>
    <td>${kb(o.bytes)}</td><td>${esc(o.icc)}</td><td>${o.hdrGainMap ? 'sí' : 'no'}</td>
    <td>${o.hasGps ? 'sí (solo en el manifiesto privado)' : 'no'}</td><td>${yes(o.identical)}</td></tr>`;
}

function section(r) {
  const p = r.processed;
  const originals = `<table><tr><th></th><th>Archivo</th><th>Formato</th><th>Dimensiones</th><th>Peso</th>
    <th>Perfil ICC</th><th>Mapa HDR</th><th>GPS en el original</th><th>Copia idéntica</th></tr>
    ${originalRow('RAW', r.raw)}${originalRow(r.raw ? 'Exportación' : 'Original', r.original)}</table>`;
  if (!p)
    return `<section><h2>${esc(r.base)}</h2><p class="status">${esc(r.status)}</p>${originals}</section>`;

  const derivatives = p.derivatives
    .map((d) => {
      const check = p.checks.find((c) => c.file === d.file);
      return `<tr><td>${d.format.toUpperCase()}</td><td>${d.width} × ${d.height}</td><td>${kb(d.bytes)}</td>
        <td>${d.ms} ms</td><td>${esc(check.icc)}</td><td>${check.gps.length ? `<span class="ko">${esc(check.gps.join(', '))}</span>` : '<span class="ok">ninguno</span>'}</td>
        <td>${check.privateGroups.length ? `<span class="ko">${esc(check.privateGroups.join(', '))}</span>` : '<span class="ok">ninguno</span>'}</td><td>${yes(check.ok)}</td></tr>`;
    })
    .join('');
  const q = p.quality;
  const largest = p.widths.at(-1);
  const hdr = r.original?.hdrGainMap
    ? '<p class="note">El original contiene un mapa de ganancia HDR. Las versiones públicas son <strong>SDR</strong>: el mapa no se conserva y no reproducen fielmente el HDR. El original se archiva intacto.</p>'
    : '';

  return `<section><h2>${esc(r.base)}</h2><p class="status">${esc(r.status)}</p>${hdr}${originals}
    <h3>Resultado</h3><p>Rango dinámico de las versiones públicas: <strong>${esc(p.dynamicRange)}</strong> · Orientación aplicada: <strong>${p.height > p.width ? 'vertical' : 'horizontal'}, ${p.width} × ${p.height}</strong></p>
    <h3>Tiempos</h3><p>Decodificación y orientación: <strong>${p.decodeMs} ms</strong> · Codificación de ${p.derivatives.length} versiones: <strong>${p.encodeMs} ms</strong> · Total: <strong>${p.decodeMs + p.encodeMs} ms</strong></p>
    <h3>Versiones públicas</h3><table><tr><th>Formato</th><th>Dimensiones</th><th>Peso</th><th>Tiempo</th><th>Perfil ICC</th><th>GPS</th><th>EXIF/XMP</th><th>Correcta</th></tr>${derivatives}</table>
    <h3>Calidad a ${q.width} px</h3><table><tr><th></th><th>ΔE00 medio</th><th>ΔE00 máximo</th><th>Detalle relativo</th></tr>
      <tr><td>AVIF</td><td>${q.avif.deltaE.avg}</td><td>${q.avif.deltaE.max}</td><td>${q.avif.detail}</td></tr>
      <tr><td>WebP</td><td>${q.webp.deltaE.avg}</td><td>${q.webp.deltaE.max}</td><td>${q.webp.detail}</td></tr>
      <tr><td>Si se convirtiera a sRGB</td><td>${q.srgbConversion.avg}</td><td>${q.srgbConversion.max}</td><td>—</td></tr></table>
    <p class="note">ΔE00 frente a una referencia sin pérdidas decodificada del original. Por debajo de 1 es imperceptible; 1–2, apenas perceptible. Detalle relativo = nitidez frente a la referencia (1 = igual).</p>
    <h3>Comparación a tamaño real, 1:1 (recorte central de la versión de ${largest} px)</h3>
    <div class="crops">${['original', 'avif', 'webp']
      .map(
        (k) =>
          `<figure><img src="${esc(p.comparison.files[k])}" alt=""><figcaption>${k === 'original' ? 'Referencia sin pérdidas (decodificada del original)' : k.toUpperCase()}</figcaption></figure>`,
      )
      .join('')}</div>
    <h3>Ampliación 2× de la zona central (sin suavizado)</h3>
    <div class="crops">${['original', 'avif', 'webp']
      .map(
        (k) =>
          `<figure><div class="zoom"><img src="${esc(p.comparison.files[k])}" alt=""></div><figcaption>${k === 'original' ? 'Referencia' : k.toUpperCase()} · 2×</figcaption></figure>`,
      )
      .join('')}</div>
    <p class="note">Versiones completas: <a href="${esc(`${r.id}-${largest}.avif`)}">AVIF ${largest} px</a> · <a href="${esc(`${r.id}-${largest}.webp`)}">WebP ${largest} px</a></p>
    <h3>Vista completa</h3><picture><source type="image/avif" srcset="${esc(`${r.id}-1024.avif`)}"><img class="full" src="${esc(`${r.id}-${Math.min(1024, largest)}.webp`)}" alt=""></picture>
  </section>`;
}

/** Informe en JSON (datos) y HTML (revisión visual). Sin coordenadas GPS. */
export async function writeReport(results, outDir) {
  const data = results.map((r) => ({
    ...r,
    processed: r.processed && {
      ...r.processed,
      placeholder: r.processed.placeholder.startsWith('data:')
        ? `${r.processed.placeholder.length} caracteres`
        : r.processed.placeholder,
    },
  }));
  await fs.writeFile(path.join(outDir, 'informe.json'), `${JSON.stringify(data, null, 2)}\n`);
  const html = `<!doctype html><html lang="es"><meta charset="utf-8"><title>Prueba del procesador multimedia</title>
<style>body{margin:0;background:#0f0e0c;color:#e9e4da;font:14px/1.5 system-ui,sans-serif}main{max-width:1400px;margin:auto;padding:24px 32px 64px}
h1{font:600 26px Georgia,serif}h2{font:600 20px Georgia,serif;margin:40px 0 4px;border-top:1px solid #333;padding-top:24px}h3{font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#d99168;margin:22px 0 8px}
table{border-collapse:collapse;width:100%;font-size:13px}th,td{border-bottom:1px solid #2a2824;padding:5px 8px;text-align:left}th{color:#a8a194;font-weight:500}
.ok{color:#a7b488}.ko{color:#e07a68;font-weight:600}.status{color:#c2ae8a}.note{color:#a8a194;font-size:12.5px}
.crops{display:flex;flex-wrap:wrap;gap:12px}.crops figure{margin:0}.crops img{display:block;width:640px;max-width:none}figcaption{color:#a8a194;font-size:12px}
.zoom{width:640px;height:480px;overflow:hidden}.zoom img{width:1280px;transform:translate(-25%,-25%);transform-origin:0 0;image-rendering:pixelated}
a{color:#86b6c9}table{display:block;overflow-x:auto}.full{max-width:100%;height:auto}</style>
<main><h1>Prueba del procesador multimedia</h1><p class="note">Generado ${new Date().toISOString()}. Las versiones públicas conservan el perfil de color del original; el GPS y el EXIF solo existen en el original archivado y en el manifiesto privado.</p>
${results.map(section).join('\n')}</main></html>`;
  await fs.writeFile(path.join(outDir, 'informe.html'), html);
}
