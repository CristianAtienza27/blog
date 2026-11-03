/**
 * Autoprueba del contenedor con imágenes sintéticas (sin fotos personales).
 *
 *   node src/synthetic.mjs generar    → crea las imágenes de prueba en /data/sintetico
 *   node src/synthetic.mjs verificar  → las procesa y comprueba los resultados
 *
 * Las imágenes llevan perfil Display P3 con colores fuera de sRGB, orientación EXIF 6,
 * GPS, fecha y datos de cámara. Entre ambos pasos puede añadirse una HEIC (HEVC) de
 * prueba con el contenedor test/heic-fixture.Dockerfile, que es el único con x265.
 */
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import sharp from 'sharp';
import { DATA } from './config.mjs';
import { run } from './inspect.mjs';
import { runPoc } from './poc.mjs';
import { regionMean } from './process.mjs';

const DIR = path.join(DATA, 'sintetico');
const W = 4032;
const H = 3024;

/** Patrón de prueba: barrido de tono saturado, rampa de grises, parches puros, detalle fino y marca de orientación. */
function pattern() {
  const data = Buffer.alloc(W * H * 3);
  const hue = (h) => {
    const k = (n) => (n + h * 6) % 6;
    const f = (n) => 1 - Math.max(0, Math.min(k(n), 4 - k(n), 1));
    return [f(5), f(3), f(1)].map((v) => Math.round(v * 255));
  };
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 3;
      let rgb;
      if (x < 300 && y < 300)
        rgb = [255, 255, 255]; // Marca de orientación (esquina superior izquierda almacenada).
      else if (y < H * 0.4) rgb = hue(x / W);
      else if (y < H * 0.5) rgb = Array(3).fill(Math.round((x / W) * 255));
      else if (x > W * 0.55 && y > H * 0.55) {
        // Detalle fino: líneas de 1 y 2 píxeles.
        const v = y < H * 0.75 ? (x + y) % 2 : Math.floor(x / 2) % 2;
        rgb = v ? [240, 240, 240] : [16, 16, 16];
      } else {
        const patch = Math.floor(x / (W / 8));
        rgb = [
          [255, 0, 0],
          [0, 255, 0],
          [0, 0, 255],
          [255, 255, 0],
          [0, 255, 255],
          [255, 0, 255],
          [64, 64, 64],
          [200, 200, 200],
        ][patch];
      }
      data[i] = rgb[0];
      data[i + 1] = rgb[1];
      data[i + 2] = rgb[2];
    }
  }
  return data;
}

const EXIF_ARGS = [
  '-GPSLatitude=40.416775',
  '-GPSLatitudeRef=N',
  '-GPSLongitude=3.703790',
  '-GPSLongitudeRef=W',
  '-DateTimeOriginal=2026:05:12 18:30:00',
  '-Make=Prueba',
  '-Model=Imagen sintética',
  '-LensModel=Lente de prueba',
  '-FNumber=1.8',
  '-ExposureTime=0.004',
  '-ISO=50',
  '-FocalLength=6.9',
];

async function generate() {
  await fs.rm(DIR, { recursive: true, force: true });
  await fs.mkdir(DIR, { recursive: true });
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'sint-'));

  // Perfil Display P3 de libvips, extraído para asignarlo a píxeles sin convertirlos.
  const p3png = path.join(tmp, 'p3.png');
  await sharp({ create: { width: 2, height: 2, channels: 3, background: '#808080' } })
    .withIccProfile('p3')
    .png()
    .toFile(p3png);
  const { stdout: icc } = await run('exiftool', ['-b', '-icc_profile', p3png], {
    encoding: 'buffer',
    maxBuffer: 1e6,
  });
  const iccFile = path.join(tmp, 'p3.icc');
  await fs.writeFile(iccFile, icc);

  const raw = sharp(pattern(), { raw: { width: W, height: H, channels: 3 } });
  const tag = (file, extra = []) =>
    run('exiftool', [
      '-q',
      '-overwrite_original',
      `-icc_profile<=${iccFile}`,
      ...EXIF_ARGS,
      ...extra,
      file,
    ]);

  // JPEG con orientación 6 (girar 90° a la derecha al mostrar).
  const jpeg = path.join(DIR, 'sintetico-p3-orientacion.jpg');
  await raw.clone().jpeg({ quality: 95 }).toFile(jpeg);
  await tag(jpeg, ['-IFD0:Orientation#=6']);

  const png = path.join(DIR, 'sintetico-p3.png');
  await raw.clone().png({ compressionLevel: 6 }).toFile(png);
  await tag(png);

  const webp = path.join(DIR, 'sintetico-p3.webp');
  await raw.clone().webp({ quality: 95 }).toFile(webp);
  await tag(webp);

  // Par DNG + exportación: el DNG es un marcador de posición (no es un RAW real).
  await fs.writeFile(
    path.join(DIR, 'sintetico-par.dng'),
    'Marcador de posicion de un DNG: no es un RAW real.\n',
  );
  await raw
    .clone()
    .resize({ width: 2016 })
    .jpeg({ quality: 92 })
    .toFile(path.join(DIR, 'sintetico-par.jpg'));
  await tag(path.join(DIR, 'sintetico-par.jpg'));

  await fs.rm(tmp, { recursive: true, force: true });
  console.log(`Imágenes sintéticas creadas en ${DIR}:`);
  for (const f of await fs.readdir(DIR)) console.log(`  ${f}`);
}

async function verify() {
  const results = await runPoc({
    inbox: DIR,
    originals: path.join(DATA, 'sintetico-originales'),
    output: path.join(DATA, 'poc-sintetico'),
    manifest: path.join(DATA, 'sintetico-manifiesto.privado.json'),
  });
  const failures = [];
  const expect = (ok, message) => ok || failures.push(message);

  for (const r of results) {
    expect(!r.status.startsWith('error'), `${r.id}: ${r.status}`);
    for (const o of [r.raw, r.original].filter(Boolean))
      expect(o.identical, `${r.id}: el original archivado no es idéntico`);
    const p = r.processed;
    if (!p) continue;
    expect(r.original.hasGps, `${r.id}: el original de prueba debería tener GPS`);
    for (const c of p.checks) {
      expect(c.gps.length === 0, `${c.file}: contiene GPS (${c.gps.join(', ')})`);
      expect(
        c.privateGroups.length === 0,
        `${c.file}: contiene metadatos privados (${c.privateGroups.join(', ')})`,
      );
      // Mismo perfil que el original (aquí, el P3 compacto de libvips, «sP3C»).
      expect(
        c.icc === r.original.icc,
        `${c.file}: perfil «${c.icc}», el original tiene «${r.original.icc}»`,
      );
      expect(/P3/.test(c.icc ?? ''), `${c.file}: perfil «${c.icc}» no es P3`);
    }
    for (const format of ['avif', 'webp']) {
      expect(
        p.derivatives.some((d) => d.format === format),
        `${r.id}: falta ${format}`,
      );
      expect(
        p.quality[format].deltaE.avg < 2,
        `${r.id}: ΔE00 medio ${format} = ${p.quality[format].deltaE.avg}`,
      );
    }
  }

  // Orientación: la marca blanca almacenada arriba a la izquierda debe verse arriba a la derecha.
  const oriented = results.find((r) => r.id === 'sintetico-p3-orientacion');
  if (oriented?.processed) {
    expect(
      oriented.processed.width === H && oriented.processed.height === W,
      'orientación: dimensiones no giradas',
    );
    const file = path.join(DATA, 'poc-sintetico', 'sintetico-p3-orientacion-640.avif');
    const corner = async (left) =>
      Math.min(...(await regionMean(file, { left, top: 0, width: 30, height: 30 })));
    const meta = await sharp(file).metadata();
    expect(
      (await corner(meta.width - 30)) > 230,
      'orientación: la marca no está arriba a la derecha',
    );
    expect((await corner(0)) < 230, 'orientación: la marca sigue arriba a la izquierda');
  }

  const pair = results.find((r) => r.id === 'sintetico-par');
  expect(pair?.raw && pair?.processed, 'par DNG + exportación: no se archivó o no se procesó');

  const p3 = results.find((r) => r.id === 'sintetico-p3');
  if (p3?.processed) {
    console.log(
      `\nΔE00 si se convirtiera a sRGB (colores P3 saturados): medio ${p3.processed.quality.srgbConversion.avg}, máximo ${p3.processed.quality.srgbConversion.max}`,
    );
  }
  if (failures.length) {
    console.error(`\n✘ ${failures.length} comprobaciones fallidas:\n- ${failures.join('\n- ')}`);
    process.exit(1);
  }
  console.log(
    `\n✔ ${results.length} grupos verificados: originales intactos, AVIF y WebP generados, Display P3 conservado, orientación correcta y sin GPS ni EXIF.`,
  );
}

const step = process.argv[2];
if (step === 'generar') await generate();
else if (step === 'verificar') await verify();
else {
  console.error('Uso: node src/synthetic.mjs generar|verificar');
  process.exit(1);
}
