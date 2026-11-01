import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type TagTone = 'outline' | 'tint' | 'solid';

/** Etiqueta de categoría o tema. Puede ser un enlace: <a app-tag href="…">Islandia</a> */
@Component({
  selector: 'app-tag, a[app-tag]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-tone]': 'tone()' },
  template: '<ng-content />',
  styleUrl: './tag.scss',
})
export class Tag {
  readonly tone = input<TagTone>('outline');
}
