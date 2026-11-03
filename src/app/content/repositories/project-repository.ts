import type { Project } from '../models';

export abstract class ProjectRepository {
  abstract list(): Promise<Project[]>;
  abstract bySlug(slug: string): Promise<Project | null>;
}
