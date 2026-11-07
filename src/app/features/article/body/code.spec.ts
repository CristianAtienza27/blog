import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Article, CodeBlock, ContentBlock } from '../../../content/models';
import { summary } from '../../../editorial/testing/editorial-fixtures';
import { MotionService } from '../../../motion/motion';
import { ArticleBody } from './article-body';
import { codeLabel, languageLabel } from './code-block';

const ROUTES: CodeBlock = {
  type: 'code',
  language: 'typescript',
  filename: 'app.routes.server.ts',
  code: "export const routes = [\n  { path: '', renderMode: RenderMode.Prerender },\n];",
  caption: 'Rutas estáticas prerenderizadas.',
};

@Component({
  imports: [ArticleBody],
  template: `<app-article-body [article]="article()" />`,
})
class Host {
  readonly article = signal<Article>({ ...summary('codigo'), format: 'reportaje', blocks: [] });
}

async function render(blocks: ContentBlock[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: MotionService, useValue: { load: () => Promise.resolve(null) } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.article.update((article) => ({ ...article, blocks }));
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Bloque de código (rótulo y nombre accesible)', () => {
  it('nombre visible de cada lenguaje; si no se conoce, su identificador', () => {
    expect(languageLabel('typescript')).toBe('TypeScript');
    expect(languageLabel('scss')).toBe('SCSS');
    expect(languageLabel('javascript')).toBe('JavaScript');
    expect(languageLabel('kotlin')).toBe('kotlin');
  });

  it('el nombre de la zona desplazable dice el archivo y, si no lo hay, el lenguaje', () => {
    expect(codeLabel(ROUTES)).toBe('Código: app.routes.server.ts (TypeScript)');
    expect(codeLabel({ language: 'scss' })).toBe('Código SCSS');
  });
});

describe('Bloque de código (componente)', () => {
  it('figura con rótulo, código y pie; más ancha que el texto', async () => {
    const element = await render([ROUTES]);
    const host = element.querySelector('app-code-block');
    expect(host?.classList).toContain('body__wide');
    const figure = host?.querySelector('figure');
    expect(figure?.querySelector('.code__file')?.textContent).toBe('app.routes.server.ts');
    expect(figure?.querySelector('.code__lang')?.textContent).toBe('TypeScript');
    expect(figure?.querySelector('pre > code')).not.toBeNull();
    expect(figure?.querySelector('figcaption')?.textContent?.trim()).toBe(
      'Rutas estáticas prerenderizadas.',
    );
  });

  it('zona desplazable enfocable, con rol, nombre, sin traducir y de izquierda a derecha', async () => {
    const pre = (await render([ROUTES])).querySelector('pre');
    expect(pre?.getAttribute('tabindex')).toBe('0');
    expect(pre?.getAttribute('role')).toBe('region');
    expect(pre?.getAttribute('aria-label')).toBe('Código: app.routes.server.ts (TypeScript)');
    expect(pre?.getAttribute('translate')).toBe('no');
    expect(pre?.getAttribute('dir')).toBe('ltr');
  });

  it('sin archivo ni pie: solo el lenguaje y ningún figcaption vacío', async () => {
    const element = await render([{ type: 'code', language: 'scss', code: 'a {}' }]);
    expect(element.querySelector('.code__file')).toBeNull();
    expect(element.querySelector('.code__lang')?.textContent).toBe('SCSS');
    expect(element.querySelector('app-code-block figcaption')).toBeNull();
    expect(element.querySelector('pre')?.getAttribute('aria-label')).toBe('Código SCSS');
  });

  it('el código es texto literal: escapado, sin elementos y con sus saltos y sangrías', async () => {
    const code = 'if (a < b && c > d) {\n  el.innerHTML = "<script>x</script>";\n}';
    const element = await render([{ type: 'code', language: 'javascript', code }]);
    const node = element.querySelector('pre > code');
    expect(node?.textContent).toBe(code);
    expect(node?.children).toHaveLength(0);
    expect(node?.innerHTML).toContain('&lt;script&gt;');
    expect(node?.innerHTML).toContain('&amp;&amp;');
    // Ni un espacio de la plantilla alrededor del código.
    expect(element.querySelector('pre')?.childNodes).toHaveLength(1);
  });
});
