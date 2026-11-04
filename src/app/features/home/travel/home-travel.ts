import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { DestinationIndexEntry } from '../../../editorial/destination-index';
import { DestinationIndex } from '../../../editorial/destination-index/destination-index';
import type { StoryData } from '../../../editorial/story';
import { Story } from '../../../editorial/story/story';
import { StoryFeature } from '../../../editorial/story-feature/story-feature';
import { SECTIONS } from '../../../layout/navigation';
import { Reveal } from '../../../motion/directives/reveal';
import { SplitText } from '../../../motion/directives/split-text';
import { Eyebrow } from '../../../shared/ui/eyebrow/eyebrow';
import { Link } from '../../../shared/ui/link/link';

export interface TravelData {
  /** Historia principal (null si el artículo no existe). */
  feature: StoryData | null;
  /** Piezas secundarias, cada una con su composición. */
  stories: StoryData[];
  /** Índice alfabético de destinos con su publicación relacionada. */
  index: DestinationIndexEntry[];
}

const SECTION = SECTIONS.find((section) => section.id === 'viajes')!;

/**
 * Sección Viajes de la portada: cabecera con el titular de la sección, historia principal
 * panorámica, piezas secundarias con composiciones distintas e índice de destinos.
 */
@Component({
  selector: 'app-home-travel',
  imports: [DestinationIndex, Eyebrow, Link, Reveal, RouterLink, SplitText, Story, StoryFeature],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home-travel.html',
  styleUrl: './home-travel.scss',
})
export class HomeTravel {
  readonly travel = input.required<TravelData>();

  protected readonly section = SECTION;
}
