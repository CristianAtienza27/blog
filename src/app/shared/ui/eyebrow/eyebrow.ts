import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Antetítulo editorial con numeración opcional: «01 —— Viajes».
 * Su tipografía la decide la temática (sans, mono o condensada).
 */
@Component({
  selector: 'app-eyebrow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (formattedIndex(); as number) {
      <span class="index">{{ number }}</span>
      <span class="rule" aria-hidden="true"></span>
    }
    <span><ng-content /></span>
  `,
  styleUrl: './eyebrow.scss',
})
export class Eyebrow {
  readonly index = input<number | undefined>();

  protected readonly formattedIndex = computed(() => {
    const value = this.index();
    return value === undefined ? null : String(value).padStart(2, '0');
  });
}
