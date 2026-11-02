import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ColorScheme, ColorSchemeService } from '../../../core/theme/color-scheme';

/** Selector de modo de color: Sistema · Claro · Oscuro. */
@Component({
  selector: 'app-theme-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'group', 'aria-label': 'Modo de color' },
  template: `
    @for (option of options; track option.value) {
      <button
        type="button"
        [attr.aria-pressed]="colorScheme.scheme() === option.value"
        (click)="colorScheme.set(option.value)"
      >
        {{ option.label }}
      </button>
    }
  `,
  styleUrl: './theme-toggle.scss',
})
export class ThemeToggle {
  protected readonly colorScheme = inject(ColorSchemeService);

  protected readonly options: { value: ColorScheme; label: string }[] = [
    { value: 'system', label: 'Sistema' },
    { value: 'light', label: 'Claro' },
    { value: 'dark', label: 'Oscuro' },
  ];
}
