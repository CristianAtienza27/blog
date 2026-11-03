import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SECTIONS } from '../../layout/navigation';
import { Eyebrow } from '../../shared/ui/eyebrow/eyebrow';
import { HomeHero } from './hero/home-hero';
import type { HomeData } from './home.resolver';

/**
 * Portada. Fase 4.1: hero definitivo; el resto de secciones llegará en las siguientes
 * subfases (de momento, un índice provisional de las secciones).
 */
@Component({
  selector: 'app-home',
  imports: [Eyebrow, HomeHero, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  /** Datos del resolver de la ruta (withComponentInputBinding). */
  readonly home = input.required<HomeData>();

  protected readonly sections = SECTIONS;

  protected pad(index: number): string {
    return String(index).padStart(2, '0');
  }
}
