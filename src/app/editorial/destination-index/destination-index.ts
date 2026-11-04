import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Reveal } from '../../motion/directives/reveal';
import { Media } from '../../shared/ui/media/media';
import type { DestinationIndexEntry } from '../destination-index';
import { PhotoCredit } from '../photo-credit/photo-credit';

/** Vista previa solo donde hay sitio para ella (escritorio). */
const PREVIEW_QUERY = '(width >= 900px)';

/**
 * Índice editorial de destinos en tipografía monoespaciada: número, nombre, región y país,
 * y la publicación relacionada. En escritorio, al pasar el ratón o al recorrerlo con el
 * teclado, la fila toma el acento de la sección y aparece una fotografía de vista previa.
 * En móvil es una lista compacta de filas táctiles, sin depender del hover.
 *
 * Las vistas previas se cargan la primera vez que se activan (nunca en el HTML inicial).
 */
@Component({
  selector: 'app-destination-index',
  imports: [Media, NgTemplateOutlet, PhotoCredit, Reveal, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './destination-index.html',
  styleUrl: './destination-index.scss',
})
export class DestinationIndex {
  readonly entries = input.required<readonly DestinationIndexEntry[]>();
  readonly heading = input('Índice de destinos');

  /** Destino cuya vista previa se muestra. */
  protected readonly active = signal<string | null>(null);
  /** Destinos ya activados: sus imágenes permanecen para el fundido entre una y otra. */
  protected readonly seen = signal<ReadonlySet<string>>(new Set());

  protected readonly activeEntry = computed(
    () => this.entries().find((entry) => entry.id === this.active()) ?? null,
  );
  protected readonly previews = computed(() =>
    this.entries().filter((entry) => entry.preview && this.seen().has(entry.id)),
  );
  protected readonly summary = computed(() => {
    const entries = this.entries();
    const countries = new Set(entries.map((entry) => entry.country)).size;
    return `${entries.length} destinos · ${countries} ${countries === 1 ? 'país' : 'países'}`;
  });

  /** Ratón: al entrar en la fila. El táctil no activa nada (el toque navega). */
  protected onPointerEnter(entry: DestinationIndexEntry, event: PointerEvent): void {
    if (event.pointerType === 'mouse') this.activate(entry);
  }

  /** Teclado: al recibir el foco, si la vista previa cabe en pantalla. */
  protected onFocus(entry: DestinationIndexEntry): void {
    if (matchMedia(PREVIEW_QUERY).matches) this.activate(entry);
  }

  protected clear(): void {
    this.active.set(null);
  }

  /** Al salir del índice con el teclado (no al pasar a otra fila ni al crédito de la foto). */
  protected onFocusOut(event: FocusEvent): void {
    const block = event.currentTarget as HTMLElement;
    if (!block.contains(event.relatedTarget as Node | null)) this.clear();
  }

  private activate(entry: DestinationIndexEntry): void {
    this.active.set(entry.id);
    if (entry.preview && !this.seen().has(entry.id)) {
      this.seen.update((seen) => new Set(seen).add(entry.id));
    }
  }
}
