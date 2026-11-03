import type { Author } from '../models';

export abstract class AuthorRepository {
  abstract list(): Promise<Author[]>;
  abstract byId(id: string): Promise<Author | null>;
}
