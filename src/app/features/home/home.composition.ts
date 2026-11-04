/**
 * Composición editorial de la portada: qué fotografías, artículos y textos propios la forman.
 * Solo identificadores y textos de la propia portada; el contenido sale de los repositorios.
 * Los textos son provisionales: se sustituyen aquí sin tocar los componentes.
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

  /** Presentación: el concepto de la revista. Sin datos biográficos. */
  intro: {
    label: 'Editorial',
    title: 'Lugares, luz y máquinas.',
    emphasis: 'Y código.',
    lead: 'Un espacio personal sobre viajes, fotografía, motor, experiencias y tecnología.',
    /** Luz entre la niebla: acompaña al titular sin competir con él. */
    media: 'naturaleza-niebla-bosque',
  },

  /** Viajes: una historia principal y piezas secundarias, cada una con su composición. */
  travel: {
    feature: 'itinerario-costa-brava',
    stories: [
      { article: 'ciudades-luz-de-tarde', layout: 'diptych', secondary: 'ciudades-lisboa-alfama' },
      { article: 'calas-de-mallorca', layout: 'portrait' },
      { article: 'alicante-desde-el-puerto', layout: 'strip' },
    ],
  },
} as const;
