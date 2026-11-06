import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ViewportScroller } from '@angular/common';
import { Router, provideRouter } from '@angular/router';
import { EXTERNAL_HINT, internalHref, renderRichText } from './rich-text-render';
import { ProseBlock } from './prose-block';

const BASE = { basePath: '/viajes/calas' };

describe('Texto enriquecido: preparación', () => {
  it('el HTML válido pasa igual (párrafos, énfasis, listas y enlaces internos)', () => {
    const html =
      '<p>Uno <em>dos</em> <strong>tres</strong> <code>x</code></p>' +
      '<ul><li><a href="/viajes/otra">otra</a></li></ul><ol><li>a</li></ol>';
    expect(renderRichText(html, BASE)).toBe(html);
  });

  it('enlace externo: pestaña nueva, rel seguro y aviso para lectores de pantalla', () => {
    expect(renderRichText('<p><a href="https://angular.dev">Angular</a></p>', BASE)).toBe(
      `<p><a href="https://angular.dev" rel="noopener noreferrer" target="_blank">Angular${EXTERNAL_HINT}</a></p>`,
    );
  });

  it('las anclas se resuelven contra la ruta del artículo (hay <base href="/">)', () => {
    expect(renderRichText('<p><a href="#cuando-ir">Cuándo</a></p>', BASE)).toBe(
      '<p><a href="/viajes/calas#cuando-ir">Cuándo</a></p>',
    );
  });

  it('subtítulos: h2 fuera de un capítulo, h3 dentro', () => {
    expect(renderRichText('<h3>Ropa</h3>', { ...BASE, headingLevel: 2 })).toBe('<h2>Ropa</h2>');
    expect(renderRichText('<h3>Ropa</h3>', { ...BASE, headingLevel: 3 })).toBe('<h3>Ropa</h3>');
  });

  it('capitular en el primer párrafo que no es el aviso de demostración', () => {
    const html = '<p><em>Artículo de demostración.</em> Aviso.</p><p>La costa.</p><p>Más.</p>';
    expect(renderRichText(html, { ...BASE, dropcap: true })).toBe(
      '<p><em>Artículo de demostración.</em> Aviso.</p><p class="has-dropcap">La costa.</p><p>Más.</p>',
    );
  });

  it.each([
    ['<p>Hola</p><script>alert(1)</script>', '<p>Hola</p>'],
    ['<p onclick="robar()">Hola</p>', '<p>Hola</p>'],
    ['<p>Hola <img src="x" onerror="robar()"></p>', '<p>Hola</p>'],
    ['<p><a href="javascript:robar()">Hola</a></p>', '<p>Hola</p>'],
    ['<p><a href="//dominio.test">Hola</a></p>', '<p>Hola</p>'],
    ['<p><a href="http://inseguro.test">Hola</a></p>', '<p>Hola</p>'],
    ['<p><a href="/viajes" target="_self">Hola</a></p>', '<p>Hola</p>'],
    ['<style>p { color: red }</style><p>Hola</p>', '<p>Hola</p>'],
    ['<iframe src="https://dominio.test"></iframe><p>Hola</p>', '<p>Hola</p>'],
    ['<p>Uno "dos" & tres</p><b>x</b>', '<p>Uno &quot;dos&quot; &amp; tres x</p>'],
  ])('fuera de la lista blanca solo queda el texto, escapado: %s', (html, expected) => {
    expect(renderRichText(html, BASE)).toBe(expected);
  });

  it.each(['/\\dominio.test/x', '/\\\\dominio.test', '/viajes\\x', '/\t/dominio.test'])(
    'una ruta que el navegador sacaría del sitio no llega a ser enlace: %s',
    (href) => {
      // Sin <a> en el HTML (también el del servidor): nada que abrir antes de hidratar,
      // con Ctrl+clic ni con el botón central.
      expect(renderRichText(`<p><a href="${href}">a</a></p>`, BASE)).toBe('<p>a</p>');
    },
  );

  // Variantes que antes pasaban la validación pero el render no reconocía.
  const VARIANTS = [
    '<p><a href="https://angular.dev" >Angular</a></p>',
    '<p><a  href="https://angular.dev">Angular</a></p>',
    '<p><a href="#cuando-ir" >Cuándo</a></p>',
    '<p><a  href="#cuando-ir">Cuándo</a></p>',
    '<p><A HREF="#cuando-ir">Cuándo</A></p>',
    '<p >Texto <h3 >Título</h3 ></p>',
  ];

  it.each(VARIANTS)('variante no canónica: solo texto, nunca un enlace a medias: %s', (html) => {
    expect(renderRichText(html, BASE)).not.toContain('<a');
  });

  it('todo enlace que sale del render es seguro: externo con sus atributos, ancla resuelta', () => {
    const inputs = [
      ...VARIANTS,
      '<p><a href="https://angular.dev">A</a> <a href="#cuando-ir">B</a> <a href="/viajes/x">C</a></p>',
      '<ul><li><a href="https://angular.dev/guide"><strong>D</strong></a></li></ul>',
    ];
    const origin = 'https://revista.invalid';
    for (const html of inputs) {
      for (const [tag, href] of renderRichText(html, BASE).matchAll(/<a href="([^"]*)"[^>]*>/g)) {
        const url = new URL(href, origin + BASE.basePath);
        if (url.origin === origin) {
          // Interno: ruta absoluta del sitio (las anclas, ya resueltas contra el artículo).
          expect(href.startsWith('/'), href).toBe(true);
        } else {
          expect(tag, href).toContain('rel="noopener noreferrer"');
          expect(tag, href).toContain('target="_blank"');
        }
      }
    }
  });
});

describe('Texto enriquecido: clics en enlaces', () => {
  function click(html: string, init: MouseEventInit = {}) {
    const host = document.createElement('div');
    host.innerHTML = html;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ...init });
    Object.defineProperty(event, 'target', { value: host.querySelector('a strong, a') });
    return internalHref(event);
  }

  it('rutas internas: las resuelve el router', () => {
    expect(click('<a href="/viajes/otra">a</a>')).toBe('/viajes/otra');
    expect(click('<a href="/viajes/calas#cuando-ir"><strong>a</strong></a>')).toBe(
      '/viajes/calas#cuando-ir',
    );
  });

  it('el navegador sigue los externos, los de otra pestaña y los clics con modificadores', () => {
    expect(click('<a href="https://angular.dev">a</a>')).toBeNull();
    expect(click('<a href="//dominio.test">a</a>')).toBeNull();
    expect(click('<a href="/viajes" target="_blank">a</a>')).toBeNull();
    expect(click('<a href="/viajes">a</a>', { ctrlKey: true })).toBeNull();
    expect(click('<a href="/viajes">a</a>', { metaKey: true })).toBeNull();
    expect(click('<a href="/viajes">a</a>', { button: 1 })).toBeNull();
  });

  it('segunda barrera: nunca entrega al router una ruta que saldría del sitio', () => {
    expect(click('<a href="/\\dominio.test">a</a>')).toBeNull();
    expect(click('<a href="/viajes\\x">a</a>')).toBeNull();
    expect(click('<a href="/\t/dominio.test">a</a>')).toBeNull();
  });
});

@Component({
  imports: [ProseBlock],
  template: `<app-prose-block [html]="html()" />`,
})
class Host {
  readonly html = signal('');
}

async function renderProse(html: string) {
  const scroller = { scrollToAnchor: vi.fn(), setOffset: vi.fn() };
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ViewportScroller, useValue: scroller }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.html.set(html);
  await fixture.whenStable();
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  return { element: fixture.nativeElement as HTMLElement, navigate, router, scroller };
}

describe('Bloque de texto (ProseBlock)', () => {
  it('el saneador de Angular es la segunda barrera: elimina scripts y eventos', async () => {
    // Sin pasar por renderRichText: HTML hostil directamente en [innerHTML].
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { element } = await renderProse(
      '<p onclick="robar()">Hola</p><script>robar()</script><img src="x" onerror="robar()">' +
        '<a href="javascript:robar()">enlace</a>',
    );
    const html = element.querySelector('.prose')?.innerHTML ?? '';
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onclick');
    expect(html).not.toContain('onerror');
    expect(element.querySelector('a')?.getAttribute('href')).toMatch(/^unsafe:/);
  });

  it('conserva los atributos de seguridad del enlace externo', async () => {
    const { element } = await renderProse(
      renderRichText('<p><a href="https://angular.dev">Angular</a></p>', BASE),
    );
    const link = element.querySelector('a');
    expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.textContent).toBe('Angular (se abre en una pestaña nueva)');
  });

  it('una ruta con barra invertida no llega al DOM como enlace', async () => {
    const { element } = await renderProse(
      renderRichText('<p><a href="/\\dominio.test">sitio</a></p>', BASE),
    );
    expect(element.querySelector('a')).toBeNull();
    expect(element.querySelector('.prose')?.textContent).toBe('sitio');
  });

  it('un enlace interno navega con el router, sin recargar la página', async () => {
    const { element, navigate } = await renderProse('<p><a href="/viajes/otra">otra</a></p>');
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
    element.querySelector('a')?.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/viajes/otra');
  });

  it('la misma ancla otra vez: el router la ignoraría, así que se desplaza igualmente', async () => {
    const { element, navigate, router, scroller } = await renderProse(
      '<p><a href="/viajes/calas#cuando-ir">ir</a></p>',
    );
    vi.spyOn(router, 'url', 'get').mockReturnValue('/viajes/calas#cuando-ir');
    element.querySelector('a')?.click();
    expect(scroller.scrollToAnchor).toHaveBeenCalledWith('cuando-ir');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('no intercepta los enlaces externos', async () => {
    const { element, navigate } = await renderProse(
      renderRichText('<p><a href="https://angular.dev">Angular</a></p>', BASE),
    );
    // En el navegador real lo sigue en otra pestaña; aquí se evita salir del entorno de pruebas
    // (este oyente actúa después del del bloque, al llegar el evento al documento).
    let followedByBrowser = false;
    document.addEventListener(
      'click',
      (event) => {
        followedByBrowser = !event.defaultPrevented;
        event.preventDefault();
      },
      { once: true },
    );
    element.querySelector('a')?.click();
    expect(followedByBrowser).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});
