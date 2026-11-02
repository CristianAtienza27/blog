import { MediaTone } from '../shared/ui/media/media';

export type SectionId = 'viajes' | 'fotografia' | 'motor' | 'experiencias' | 'tecnologia';

/** Contenido editorial de una página (provisional hasta la fase 4). */
export interface PageCopy {
  heading: string;
  emphasis: string;
  lead: string;
  tone: MediaTone;
}

export interface NavSection extends PageCopy {
  id: SectionId;
  index: number;
  label: string;
  path: string;
  teaser: string;
}

export interface NavPage extends PageCopy {
  label: string;
  path: string;
}

/**
 * Fuente única de la navegación: header, menú, footer y rutas se generan desde aquí.
 * El orden refleja el peso editorial (70 % viajes y experiencias, 30 % tecnología).
 */
export const SECTIONS: readonly NavSection[] = [
  {
    id: 'viajes',
    index: 1,
    label: 'Viajes',
    path: '/viajes',
    teaser: 'Rutas y relatos',
    heading: 'Carreteras, mapas y',
    emphasis: 'lugares lejanos',
    lead: 'Relatos de viaje en primera persona: rutas, ciudades y paisajes contados despacio.',
    tone: 'desert',
  },
  {
    id: 'fotografia',
    index: 2,
    label: 'Fotografía',
    path: '/fotografia',
    teaser: 'Series y procesos',
    heading: 'La luz como',
    emphasis: 'materia prima',
    lead: 'Series fotográficas, procesos y la historia que hay detrás de cada imagen.',
    tone: 'dawn',
  },
  {
    id: 'motor',
    index: 3,
    label: 'Motor',
    path: '/motor',
    teaser: 'Coches y carreteras',
    heading: 'Curvas, asfalto y',
    emphasis: 'máquinas',
    lead: 'Coches, puertos de montaña y la cultura del motor vista desde la carretera.',
    tone: 'road',
  },
  {
    id: 'experiencias',
    index: 4,
    label: 'Experiencias',
    path: '/experiencias',
    teaser: 'Momentos y encuentros',
    heading: 'Lo que ocurre',
    emphasis: 'entre un lugar y otro',
    lead: 'Momentos, encuentros y aprendizajes que no caben en una guía de viaje.',
    tone: 'forest',
  },
  {
    id: 'tecnologia',
    index: 5,
    label: 'Tecnología',
    path: '/tecnologia',
    teaser: 'Desarrollo y notas',
    heading: 'Construir software',
    emphasis: 'con oficio',
    lead: 'Desarrollo web, arquitectura y herramientas: notas de un desarrollador.',
    tone: 'night',
  },
];

export const SECONDARY_PAGES: readonly NavPage[] = [
  {
    label: 'Archivo',
    path: '/archivo',
    heading: 'Todo lo publicado,',
    emphasis: 'en un solo lugar',
    lead: 'El índice completo de la revista, ordenado por fecha y temática.',
    tone: 'sea',
  },
  {
    label: 'Sobre mí',
    path: '/sobre-mi',
    heading: 'Quién escribe',
    emphasis: 'esta revista',
    lead: 'Viajero, fotógrafo aficionado y desarrollador de software. La historia detrás del nombre.',
    tone: 'dawn',
  },
];

export const HOME_PAGE: NavPage = {
  label: 'Portada',
  path: '/',
  heading: 'Una revista de',
  emphasis: 'viajes, luz y código',
  lead: 'Viajes, fotografía, motor, experiencias y tecnología. Muy pronto, los primeros relatos.',
  tone: 'desert',
};

/** Rutas sin parámetros: se prerenderizan en el build. */
export const STATIC_PATHS: readonly string[] = [...SECTIONS, ...SECONDARY_PAGES].map((page) =>
  page.path.slice(1),
);
