import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { CodeBlock as CodeData } from '../../../content/models';

/** Nombre visible de cada lenguaje. Si no está en la lista, se muestra su identificador. */
const LANGUAGE_LABELS: Readonly<Record<string, string>> = {
  bash: 'Bash',
  css: 'CSS',
  html: 'HTML',
  java: 'Java',
  javascript: 'JavaScript',
  json: 'JSON',
  scss: 'SCSS',
  sql: 'SQL',
  typescript: 'TypeScript',
  xml: 'XML',
  yaml: 'YAML',
};

export function languageLabel(language: string): string {
  return LANGUAGE_LABELS[language] ?? language;
}

/** Nombre accesible de la zona desplazable: el archivo si lo hay; si no, el lenguaje. */
export function codeLabel(block: Pick<CodeData, 'language' | 'filename'>): string {
  const language = languageLabel(block.language);
  return block.filename ? `Código: ${block.filename} (${language})` : `Código ${language}`;
}

/**
 * Fragmento de código del relato: rótulo con el archivo y el lenguaje, el código tal cual y
 * un pie opcional.
 * - Sin coloreado de sintaxis ni numeración: texto en mono con los colores del sistema.
 * - Las líneas no se parten: si no caben, el bloque se desplaza en horizontal (no la página).
 *   La zona desplazable recibe el foco para recorrerla con las flechas del teclado.
 * - El código se interpola (nunca innerHTML): Angular lo escapa y el servidor y el navegador
 *   producen el mismo texto. Sin espacios entre <pre>, <code> y el código.
 */
@Component({
  selector: 'app-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let c = code();
    <figure class="code">
      <div class="code__frame">
        <div class="code__head">
          @if (c.filename) {
            <span class="code__file" translate="no" dir="ltr">{{ c.filename }}</span>
          }
          <span class="code__lang t-meta">{{ language() }}</span>
        </div>
        <pre
          class="code__scroll"
          tabindex="0"
          role="region"
          [attr.aria-label]="label()"
          translate="no"
          dir="ltr"
        ><code>{{ c.code }}</code></pre>
      </div>
      @if (c.caption) {
        <figcaption class="code__caption t-caption">{{ c.caption }}</figcaption>
      }
    </figure>
  `,
  styleUrl: './code-block.scss',
})
export class CodeBlock {
  readonly code = input.required<CodeData>();

  protected readonly language = computed(() => languageLabel(this.code().language));
  protected readonly label = computed(() => codeLabel(this.code()));
}
