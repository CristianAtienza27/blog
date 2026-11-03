import type { Category, SectionId, Tag } from '../models';

export abstract class TaxonomyRepository {
  abstract categories(): Promise<Category[]>;
  abstract category(id: SectionId): Promise<Category | null>;
  abstract tags(): Promise<Tag[]>;
  abstract tag(slug: string): Promise<Tag | null>;
}
