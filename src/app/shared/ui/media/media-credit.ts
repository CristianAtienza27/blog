import type { MediaCredit, MediaLicense, MediaSource } from '../../../content/models';

export interface CreditLine {
  author: string;
  source?: string;
  sourceUrl?: string;
  license?: string;
  licenseUrl?: string;
  /** Las versiones publicadas están recortadas y redimensionadas (CC lo exige indicar). */
  adapted: boolean;
}

const SOURCES: Record<MediaSource, string | undefined> = {
  propia: undefined,
  wikimedia: 'Wikimedia Commons',
  unsplash: 'Unsplash',
  pexels: 'Pexels',
};

const LICENSES: Record<MediaLicense, string | undefined> = {
  'derechos-reservados': undefined,
  'CC0-1.0': 'CC0',
  'PDM-1.0': 'Dominio público',
  'CC-BY-2.0': 'CC BY 2.0',
  'CC-BY-3.0': 'CC BY 3.0',
  'CC-BY-4.0': 'CC BY 4.0',
  'CC-BY-SA-2.0': 'CC BY-SA 2.0',
  'CC-BY-SA-3.0': 'CC BY-SA 3.0',
  'CC-BY-SA-4.0': 'CC BY-SA 4.0',
  Unsplash: 'Licencia Unsplash',
  Pexels: 'Licencia Pexels',
};

/** Línea de crédito: autor, procedencia y licencia (con enlaces) de una imagen. */
export function creditLine(credit: MediaCredit): CreditLine {
  const license = LICENSES[credit.license];
  return {
    author: credit.author,
    source: SOURCES[credit.source],
    sourceUrl: credit.sourceUrl,
    license,
    licenseUrl: credit.licenseUrl,
    adapted: credit.license.startsWith('CC-BY'),
  };
}
