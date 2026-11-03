/** Las cinco secciones de la revista. El orden refleja su peso editorial. */
export const SECTION_IDS = ['viajes', 'fotografia', 'motor', 'experiencias', 'tecnologia'] as const;

export type SectionId = (typeof SECTION_IDS)[number];

/** Categoría editorial: corresponde a una sección. */
export interface Category {
  id: SectionId;
  slug: string;
  name: string;
  description: string;
}

/** Etiqueta temática transversal. */
export interface Tag {
  id: string;
  slug: string;
  label: string;
}
