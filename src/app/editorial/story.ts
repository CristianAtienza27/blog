import type { ArticleSummary, DestinationRef, Media } from '../content/models';

/** Composiciones de las piezas secundarias: cada publicación se presenta de forma distinta. */
export type StoryLayout = 'diptych' | 'portrait' | 'strip';

/** Una publicación lista para componerse: artículo, lugar principal y extras de la pieza. */
export interface StoryData {
  article: ArticleSummary;
  /** Lugar principal: el de la fotografía de portada si es uno de los destinos del artículo. */
  place: DestinationRef | null;
  layout?: StoryLayout;
  /** Segunda fotografía (díptico). */
  secondary?: Media | null;
}
