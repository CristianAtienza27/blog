import type { Destination, Trip } from '../models';

export interface DestinationQuery {
  /** Código ISO 3166-1 alfa-2. */
  country?: string;
}

export abstract class TravelRepository {
  abstract trips(): Promise<Trip[]>;
  abstract trip(slug: string): Promise<Trip | null>;
  abstract destinations(query?: DestinationQuery): Promise<Destination[]>;
  abstract destination(slug: string): Promise<Destination | null>;
  /** Itinerarios que pasan por un destino. */
  abstract tripsByDestination(slug: string): Promise<Trip[]>;
}
