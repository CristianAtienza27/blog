import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import type { Media as MediaAsset } from '../../../content/models';
import { SECTIONS } from '../../../layout/navigation';
import { Parallax } from '../../../motion/directives/parallax';
import { Reveal } from '../../../motion/directives/reveal';
import { SplitText } from '../../../motion/directives/split-text';
import { Eyebrow } from '../../../shared/ui/eyebrow/eyebrow';
import { Media } from '../../../shared/ui/media/media';

export interface IntroData {
  label: string;
  title: string;
  emphasis: string;
  lead: string;
  /** null si la fotografía no existe: la presentación se muestra solo con texto. */
  media: MediaAsset | null;
}

/**
 * Presentación de la revista, justo después del hero: titular a siete columnas, entradilla
 * desplazada y una fotografía vertical más baja que el titular. Cierra con accesos
 * tipográficos a las cinco secciones, cada uno con el acento de su temática.
 */
@Component({
  selector: 'app-home-intro',
  imports: [Eyebrow, Media, Parallax, Reveal, RouterLink, SplitText],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home-intro.html',
  styleUrl: './home-intro.scss',
})
export class HomeIntro {
  readonly intro = input.required<IntroData>();

  protected readonly sections = SECTIONS;
  /**
   * Recorte 4:5 de una foto 3:2: el ancho real es 1,9 veces el del marco. Desde 1600 px la
   * columna de contenido deja de crecer (1200 px), y la foto también.
   */
  protected readonly photoSizes =
    '(width >= 1600px) 40vw, (width >= 900px) 54vw, (width >= 640px) 86vw, 134vw';

  protected pad(index: number): string {
    return String(index).padStart(2, '0');
  }
}
