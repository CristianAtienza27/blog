import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { SpecItem, SpecsBlock as SpecsData } from '../../../content/models';
import { Reveal } from '../../../motion/directives/reveal';

/** Un valor corto («2757 m», «SS38») se destaca en grande; uno largo ocupa dos columnas. */
export function isShortSpec(item: SpecItem): boolean {
  return `${item.value} ${item.unit ?? ''}`.trim().length <= 12;
}

/**
 * Ficha de datos editorial: lista de definiciones con filetes finos, etiqueta en mono y valor
 * en Fraunces. Solo muestra lo que trae el contenido (sin rellenar huecos).
 * Móvil: dos columnas (los valores largos, a lo ancho). Tableta: tres. Escritorio: cuatro.
 */
@Component({
  selector: 'app-specs-block',
  imports: [Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './specs-block.html',
  styleUrl: './specs-block.scss',
})
export class SpecsBlock {
  readonly specs = input.required<SpecsData>();
  /** Nivel del título: 2 fuera de un capítulo, 3 dentro. */
  readonly level = input<2 | 3>(3);
  /** Id del título, para nombrar la sección. */
  readonly headingId = input.required<string>();

  protected readonly items = computed(() =>
    this.specs().items.map((item) => ({ ...item, short: isShortSpec(item) })),
  );
}
