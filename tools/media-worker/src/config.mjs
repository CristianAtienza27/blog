import path from 'node:path';

/**
 * Parámetros del procesador, centralizados. Cambiar un valor aquí no requiere tocar
 * la arquitectura: el procesador recalcula las versiones al sincronizar.
 */
export const MEDIA_CONFIG = {
  /** Anchos de las versiones públicas (nunca se amplía el original). */
  widths: [640, 1024, 1600, 2400],
  /**
   * 2400 px solo para imágenes que se ven a pantalla completa: panorámicas,
   * portadas, bloques de imagen «full» y galerías horizontales. El resto llega a 1600 px.
   */
  fullscreenWidth: 2400,
  /** Relación de aspecto a partir de la cual una foto se considera panorámica. */
  panoramaRatio: 2,
  /** Validado con una foto real (iPhone 17 Pro Max) y la autoprueba sintética. */
  avif: { quality: 60, effort: 3, chromaSubsampling: '4:2:0', bitdepth: 8 },
  webp: { quality: 78, effort: 5, smartSubsample: true },
  /** Vista previa difuminada (sRGB, sin perfil incrustado: unos cientos de bytes). */
  placeholder: { width: 24, quality: 45 },
  /** Límite de seguridad de píxeles al decodificar (las panorámicas superan los 100 MP). */
  limitInputPixels: 300_000_000,
};

/** Huella de la codificación: si cambian los ajustes, cambia el nombre de los archivos. */
export const ENCODING_FINGERPRINT = JSON.stringify([
  MEDIA_CONFIG.avif,
  MEDIA_CONFIG.webp,
  MEDIA_CONFIG.placeholder,
]);

/** Carpeta de datos privada (media-src/ en el repositorio, fuera de Git). */
export const DATA = process.env.MEDIA_DATA ?? '/data';
/** Contenido de la web (src/content). */
export const CONTENT = process.env.MEDIA_CONTENT ?? '/content';
/** Versiones públicas (public/media; en producción, un contenedor de Azure Blob Storage). */
export const PUBLIC = process.env.MEDIA_PUBLIC ?? '/public-media';

export const DIRS = {
  /** Originales propios tal como salen del iPhone, a la espera de importarse. */
  inbox: path.join(DATA, 'entrada'),
  /** Originales archivados sin modificar, renombrados por id. */
  originals: path.join(DATA, 'originales'),
  /** Resultados de la prueba de concepto. */
  output: path.join(DATA, 'poc'),
};

/** Manifiesto privado: EXIF completo (GPS incluido). Nunca se publica. */
export const MANIFEST = path.join(DATA, 'manifiesto.privado.json');

/** Alias usados por la prueba de concepto. */
export const WIDTHS = MEDIA_CONFIG.widths;
export const AVIF = MEDIA_CONFIG.avif;
export const WEBP = MEDIA_CONFIG.webp;

/** Formatos que se procesan; DNG solo se archiva. */
export const RASTER_EXT = new Set(['.heic', '.heif', '.hif', '.jpg', '.jpeg', '.png', '.webp']);
export const RAW_EXT = new Set(['.dng']);

/** Identificación ante la API de Wikimedia (política de User-Agent). */
export const USER_AGENT =
  'CristianAtienzaMediaWorker/0.1 (revista personal; descarga de imágenes con licencia libre)';
