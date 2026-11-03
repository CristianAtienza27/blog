import { DemoFlag } from './common';
import { Media } from './media';
import { Tag } from './taxonomy';

export const PROJECT_STATUSES = ['activo', 'pausado', 'archivado'] as const;
export type ProjectStatus = (typeof PROJECT_STATUSES)[number];

/** Proyecto de software de la sección Tecnología. */
export interface Project extends DemoFlag {
  id: string;
  slug: string;
  name: string;
  summary: string;
  status: ProjectStatus;
  year: number;
  stack: Tag[];
  /** Solo si existe de verdad: nunca una URL inventada. */
  repositoryUrl?: string;
  cover?: Media;
}
