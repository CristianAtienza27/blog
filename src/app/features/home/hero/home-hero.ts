import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SITE_NAME } from '../../../core/config/site-config';
import { countryName, formatCoordinates, formatElevation } from '../../../editorial/geo';
import { SECTIONS } from '../../../layout/navigation';
import { useMotion } from '../../../motion/use-motion';
import { Media } from '../../../shared/ui/media/media';
import { creditLine } from '../../../shared/ui/media/media-credit';
import type { HeroData } from '../home.resolver';

/**
 * Ancho real de la foto recortada a pantalla completa (relación 1,8:1 con object-fit: cover):
 * en escritorio llena el alto (180vh) o el ancho; en móvil ocupa el 64 % del alto (≈116vh).
 * Con «100vw» el navegador elegiría un archivo demasiado pequeño para el recorte.
 */
export const HERO_SIZES = '(width < 640px) max(100vw, 116vh), max(100vw, 180vh)';

/**
 * Hero de la portada: un fotograma de apertura en tres planos.
 *
 * - Entrada: animaciones CSS desde el primer fotograma del HTML del servidor (sin esperar a
 *   GSAP ni a la hidratación). La fotografía nunca empieza oculta: solo un zoom lento.
 * - Scroll: GSAP mueve los planos a velocidades distintas (parallax) y desvanece los textos.
 *   Anima solo los envoltorios `data-hero-*`; las animaciones CSS van en sus hijos, así que
 *   nunca compiten por la misma propiedad del mismo elemento.
 * - Reduced motion: ni entrada ni scroll.
 */
@Component({
  selector: 'app-home-hero',
  imports: [Media, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './home-hero.html',
  styleUrl: './home-hero.scss',
})
export class HomeHero {
  readonly hero = input.required<HeroData>();

  protected readonly sizes = HERO_SIZES;
  protected readonly sections = SECTIONS.map((section) => section.label).join(' · ');
  protected readonly name = (() => {
    const [first, ...rest] = SITE_NAME.split(' ');
    return { first, last: rest.join(' ') };
  })();

  protected readonly place = computed(() => {
    const destination = this.hero().destination;
    if (!destination) return null;
    return {
      coordinates: formatCoordinates(destination.location),
      elevation:
        destination.elevation === undefined ? null : formatElevation(destination.elevation),
      name: destination.name,
      area: [destination.region, countryName(destination.country)].filter(Boolean).join(' · '),
    };
  });

  protected readonly credit = computed(() => creditLine(this.hero().media.credit));

  constructor() {
    useMotion(({ api, el, reduce, mobile }) => {
      if (reduce) return;
      const { gsap } = api;
      const media = el.querySelector('[data-hero-media]');
      const copy = el.querySelector('[data-hero-copy]');
      const chrome = el.querySelectorAll('[data-hero-chrome]');
      if (!media || !copy) return;

      // Parallax: la foto avanza más despacio que la página y se acerca un poco.
      const scroll = { trigger: el, start: 'top top', end: 'bottom top', scrub: true };
      const timeline = gsap.timeline({ scrollTrigger: scroll, defaults: { ease: 'none' } });
      timeline.to(media, { yPercent: mobile ? 6 : 12, scale: mobile ? 1 : 1.05 }, 0);
      // En escritorio el texto se separa de la foto: sube más rápido y se desvanece.
      if (!mobile) timeline.to(copy, { yPercent: -22, autoAlpha: 0, duration: 0.6 }, 0);

      // Metadatos, crédito e indicador desaparecen en cuanto empieza el scroll.
      gsap.to(chrome, {
        autoAlpha: 0,
        ease: 'none',
        scrollTrigger: { trigger: el, start: 'top top', end: '+=180', scrub: true },
      });
    });
  }
}
