# media-worker

Procesador de fotografías de la revista. Archiva los originales sin modificarlos y genera las
versiones públicas en AVIF y WebP. Se ejecuta siempre dentro de un contenedor Linux, tanto en
local (Docker Desktop) como, más adelante, en Azure.

## Dependencias y versiones

| Componente                                               | Versión                                    | Origen                                        | Licencia           | Uso                                       |
| -------------------------------------------------------- | ------------------------------------------ | --------------------------------------------- | ------------------ | ----------------------------------------- |
| Imagen base                                              | `node:26.13.0-trixie-slim` (digest fijado) | Docker Hub                                    | MIT / Debian       | Node 26 sobre Debian 13                   |
| libvips                                                  | 8.18.7                                     | código fuente, SHA-256 verificado             | LGPL-2.1+          | procesamiento de imagen                   |
| sharp                                                    | 0.35.5                                     | npm, **compilado contra el libvips anterior** | Apache-2.0         | API de Node para libvips                  |
| node-addon-api / node-gyp                                | 8.9.2 / 13.1.0                             | npm (solo durante la compilación)             | MIT                | compilar sharp                            |
| libheif                                                  | 1.23.6                                     | Debian trixie                                 | LGPL-3.0           | contenedor HEIF/HEIC/AVIF                 |
| libde265                                                 | 1.0.15                                     | Debian trixie                                 | LGPL-3.0           | **decodifica HEVC** (HEIC del iPhone)     |
| dav1d                                                    | 1.5.1                                      | Debian trixie                                 | BSD-2              | decodifica AV1 (verificar los AVIF)       |
| aom                                                      | 3.12.1                                     | Debian trixie                                 | BSD-2              | **codifica AVIF**                         |
| libjpeg-turbo, libspng, libwebp, lcms2, libexif, highway | Debian trixie                              | Debian                                        | BSD / MIT / Apache | JPEG, PNG, WebP, perfiles ICC, EXIF, SIMD |
| exiftool                                                 | 13.25                                      | Debian trixie                                 | Artistic / GPL     | leer EXIF e inspeccionar las salidas      |

libvips se compila con `--auto-features=disabled` y solo HEIF, JPEG, PNG, WebP, lcms, EXIF,
highway y zlib activados. **No incluye x265** (no se codifica HEIC), ni RAW, TIFF, PDF, SVG ni
ImageMagick. `npm run media:codecs` lo comprueba.

`node_modules` se instala dentro de la imagen (Linux) y nunca se monta desde Windows. Sin
dependencias opcionales, los binarios precompilados de sharp (`@img/*`) no se instalan.

## Comandos (desde la raíz del proyecto)

```bash
npm run media:build     # construye la imagen
npm run media:codecs    # verifica códecs y versiones
npm run media:test      # autoprueba con imágenes sintéticas
npm run media:externa -- <url de Commons> --id <id> --seccion <carpeta>
npm run media:propia -- <archivo de entrada/> --id <id> --seccion <carpeta> [--raw <DNG>] [--reemplazar]
npm run media:sync      # regenera lo que falte o haya cambiado y elimina versiones huérfanas
npm run media:verify    # comprueba versiones y metadatos de todas las imágenes
npm run media:poc       # informe de prueba con las fotos de media-src/entrada
```

- **externa:** descarga el original de Wikimedia Commons (licencia admitida, SHA-1 verificado
  contra el publicado y esperas respetuosas si Commons limita las peticiones), registra autoría,
  procedencia y licencia en `src/content/media.json` y genera las versiones públicas.
- **propia:** archiva una foto propia sin modificarla, guarda su EXIF completo (GPS incluido) solo
  en el manifiesto privado, registra a Cristian Atienza como autor con derechos reservados y copia
  a los campos públicos la fecha y los datos de cámara. El texto alternativo queda vacío y la
  validación de contenido falla hasta que se escriba. Con `--raw`, archiva también el Apple
  ProRAW (DNG) sin modificarlo ni revelarlo. Con `--reemplazar`, sustituye una imagen existente
  manteniendo su id: artículos y componentes no cambian y el original anterior se conserva.
- Los campos editoriales (`alt`, `caption`, `focal`, `location`…) se escriben a mano en
  `media.json`; los técnicos (`path`, `widths`, `placeholder`…) los rellena el procesador.

## Parámetros

Están centralizados en `src/config.mjs` (`MEDIA_CONFIG`). Cambiarlos no requiere tocar la
arquitectura: los nombres de archivo llevan un hash del original y de los ajustes, así que
`media:sync` regenera las versiones y la caché nunca sirve las antiguas.

| Parámetro    | Valor                                                                                                                              |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| AVIF         | calidad 60, esfuerzo 3, 4:2:0, 8 bits                                                                                              |
| WebP         | calidad 78, esfuerzo 5, submuestreo inteligente                                                                                    |
| Anchos       | 640, 1024 y 1600 px; 2400 px solo para portadas, bloques a pantalla completa, galerías horizontales y panorámicas (relación ≥ 2:1) |
| Vista previa | WebP de 24 px en sRGB                                                                                                              |

## Carpetas

| Ruta                                | Contenido                                                         | Git |
| ----------------------------------- | ----------------------------------------------------------------- | --- |
| `media-src/entrada/`                | fotos propias tal como salen del iPhone (montada en solo lectura) | no  |
| `media-src/originales/`             | originales archivados sin modificar, renombrados por id           | no  |
| `media-src/manifiesto.privado.json` | EXIF completo de las fotos propias, **GPS incluido**              | no  |
| `src/content/media.json`            | registros de imagen: textos, créditos, licencias y datos técnicos | sí  |
| `public/media/<carpeta>/`           | versiones públicas AVIF y WebP                                    | sí  |

## Decisiones de procesamiento

- **Orientación:** se aplica la de HEIF (`irot`/`imir`) y la etiqueta EXIF `Orientation`; las
  versiones públicas se guardan ya giradas y sin etiqueta.
- **Color:** se conserva el perfil del original (Display P3 en el iPhone) incrustado en AVIF y
  WebP. No se convierte a sRGB: los navegadores gestionan el color y, en la prueba sintética, la
  conversión a sRGB alteraba los colores saturados (ΔE00 medio 2,9, máximo 10,7). Excepciones:
  la vista previa difuminada (sRGB, sin perfil) y los originales CMYK, que se convierten a sRGB.
- **Metadatos:** las versiones públicas no llevan EXIF, XMP, IPTC ni GPS, solo el perfil ICC.
  Se verifica con exiftool en cada archivo (`media:verify`).
- **HDR:** las fotos HDR del iPhone incluyen un mapa de ganancia (Apple o ISO 21496-1). **Las
  versiones públicas son SDR: el mapa de ganancia no se conserva y no son una reproducción HDR
  fiel.** El original se archiva intacto, con su HDR.
- **Apple ProRAW (DNG):** se archiva sin modificar y no se revela; las versiones públicas salen
  de la exportación JPEG/HEIC asociada.

## Reutilización en Azure (futuro, no implementado)

La imagen no depende de Windows ni del proyecto Angular: lee y escribe en volúmenes (`/data`,
`/content`, `/public-media`). Más adelante podrá ejecutarse como trabajo de Azure Container Apps,
leyendo de un contenedor privado de Blob Storage y escribiendo en el público, invocado por el
backend Spring Boot. En la web, el origen de las imágenes se configura con la variable
`MEDIA_BASE_URL` (por defecto `/media`). Nada de lo relativo a Azure está implementado todavía.
