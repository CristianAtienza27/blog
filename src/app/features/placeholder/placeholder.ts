import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Parallax } from '../../motion/directives/parallax';
import { Reveal } from '../../motion/directives/reveal';
import { SplitText } from '../../motion/directives/split-text';
import { Eyebrow } from '../../shared/ui/eyebrow/eyebrow';
import { Link } from '../../shared/ui/link/link';
import { Media, MediaTone } from '../../shared/ui/media/media';
import { Tag } from '../../shared/ui/tag/tag';

/**
 * Página provisional de sección. Recibe su contenido desde `data` de la ruta
 * (withComponentInputBinding). Se sustituye por las páginas reales en la fase 4.
 */
@Component({
  selector: 'app-placeholder',
  imports: [Eyebrow, Link, Media, Parallax, Reveal, RouterLink, SplitText, Tag],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-section]': 'section()' },
  templateUrl: './placeholder.html',
  styleUrl: './placeholder.scss',
})
export class Placeholder {
  readonly section = input<string>();
  readonly index = input<number>();
  readonly label = input.required<string>();
  readonly heading = input.required<string>();
  readonly emphasis = input.required<string>();
  readonly lead = input.required<string>();
  readonly tone = input<MediaTone>('desert');
}
