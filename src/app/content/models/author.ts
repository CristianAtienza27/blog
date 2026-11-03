import { Media } from './media';

export interface Author {
  id: string;
  name: string;
  role: string;
  /** Vacía hasta que el autor la escriba: no se inventa. */
  bio?: string;
  avatar?: Media;
}
