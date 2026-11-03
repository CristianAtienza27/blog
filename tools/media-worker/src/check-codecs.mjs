/**
 * Comprueba que el contenedor tiene lo necesario y nada más:
 * sharp compilado contra el libvips del sistema, HEIC (HEVC) legible, AVIF y WebP
 * codificables, perfiles ICC y EXIF disponibles, y sin x265.
 */
import { createRequire } from 'node:module';
import sharp from 'sharp';
import { run } from './inspect.mjs';

const require = createRequire(import.meta.url);
const results = [];
const check = (name, ok, detail = '') => results.push({ name, ok: Boolean(ok), detail });

const versions = sharp.versions;
check('sharp', versions.sharp === '0.35.5', `sharp ${versions.sharp}`);
check(
  'libvips del sistema',
  versions.vips === process.env.VIPS_VERSION,
  `libvips ${versions.vips} (esperado ${process.env.VIPS_VERSION})`,
);

let prebuilt = false;
try {
  require.resolve('@img/sharp-linux-x64/package.json');
  prebuilt = true;
} catch {
  // Correcto: no hay binarios precompilados.
}
check(
  'sin binarios precompilados de sharp',
  !prebuilt,
  prebuilt ? '@img/sharp-linux-x64 presente' : '',
);

const format = sharp.format;
check('lee HEIF/HEIC', format.heif.input.file && format.heif.input.buffer, 'heifload');
check('escribe AVIF', format.heif.output.file, 'heifsave (AV1)');
check('lee y escribe JPEG', format.jpeg.input.file && format.jpeg.output.file);
check('lee y escribe PNG', format.png.input.file && format.png.output.file);
check('lee y escribe WebP', format.webp.input.file && format.webp.output.file);

const listing = async (cmd, args) => {
  try {
    const { stdout, stderr } = await run(cmd, args);
    return `${stdout}\n${stderr}`;
  } catch (error) {
    return `${error.stdout ?? ''}\n${error.stderr ?? ''}`;
  }
};
const decoders = await listing('heif-dec', ['--list-decoders']);
const encoders = await listing('heif-enc', ['--list-encoders']);
check('decodificador HEVC (libde265)', /libde265/i.test(decoders), 'para fotos HEIC del iPhone');
check('decodificador AV1 (dav1d)', /dav1d/i.test(decoders), 'para verificar los AVIF generados');
check('codificador AV1 (aom)', /aom/i.test(encoders), 'para generar AVIF');
check('sin codificador HEVC (x265)', !/x265/i.test(encoders), 'no se necesita codificar HEIC');

const packages = await listing('dpkg-query', ['-W', '-f', '${Package} ${Version}\\n']);
check('sin paquetes x265', !/x265/i.test(packages));

const exiftool = (await listing('exiftool', ['-ver'])).trim();
check('exiftool', /^\d+\.\d+/.test(exiftool), `exiftool ${exiftool}`);

// Perfil Display P3 integrado en libvips: se conserva y se reconoce al releerlo.
const p3 = await sharp({ create: { width: 8, height: 8, channels: 3, background: '#00ff00' } })
  .withIccProfile('p3')
  .png()
  .toBuffer();
const p3Meta = await sharp(p3).metadata();
check('perfiles ICC (lcms2)', p3Meta.hasProfile && p3Meta.icc?.length > 0, 'Display P3 embebido');

const libheif = (await listing('heif-info', ['--version'])).trim().split('\n')[0];
console.log(
  `\nmedia-worker · Node ${process.versions.node} · ${libheif ? `libheif ${libheif}` : ''}\n`,
);
for (const r of results)
  console.log(`${r.ok ? '✔' : '✘'} ${r.name}${r.detail ? ` — ${r.detail}` : ''}`);
console.log(
  `\nDecodificadores HEIF:\n${decoders.trim()}\n\nCodificadores HEIF:\n${encoders.trim()}\n`,
);

const failed = results.filter((r) => !r.ok);
if (failed.length) {
  console.error(`${failed.length} comprobaciones fallidas`);
  process.exit(1);
}
console.log('Todas las comprobaciones son correctas.');
