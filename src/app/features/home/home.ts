import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { HomeHero } from './hero/home-hero';
import type { HomeData } from './home.resolver';
import { HomeIntro } from './intro/home-intro';
import { HomeTravel } from './travel/home-travel';

/** Portada: hero, presentación y viajes. Las demás secciones llegan en las siguientes fases. */
@Component({
  selector: 'app-home',
  imports: [HomeHero, HomeIntro, HomeTravel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  /** Datos del resolver de la ruta (withComponentInputBinding). */
  readonly home = input.required<HomeData>();
}
