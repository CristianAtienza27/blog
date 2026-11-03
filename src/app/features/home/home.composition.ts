/**
 * Composición editorial de la portada: qué fotografía y qué lugar abren la revista.
 * Solo identificadores y textos de la propia portada; el contenido sale de los repositorios.
 */
export const HOME_COMPOSITION = {
  hero: {
    /** Stelvio: carretera de montaña entre cumbres, resume viaje, motor y paisaje. */
    media: 'motor-stelvio-bormio',
    /** Lugar de la fotografía: coordenadas, altitud y ubicación del hero. */
    destination: 'paso-del-stelvio',
    kicker: 'Revista digital',
    tagline: ['Explorando lugares.', 'Capturando momentos.', 'Viviendo experiencias.'],
  },
} as const;
