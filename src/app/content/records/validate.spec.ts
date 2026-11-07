import { FIXTURE_BODIES, fixtureCollections, ownMedia } from '../testing/fixtures';
import { countWords, readingMinutes } from './reading-time';
import { COLLECTION_NAMES } from './records';
import { htmlIssues, validateArticleBody, validateCollection } from './validate';

function collectionIssues(name: Parameters<typeof validateCollection>[0], value: unknown) {
  const issues: string[] = [];
  validateCollection(name, value, issues);
  return issues;
}

function bodyIssues(value: unknown) {
  const issues: string[] = [];
  validateArticleBody('cuerpo', value, issues);
  return issues;
}

describe('Validación de la estructura de los registros', () => {
  it('acepta todas las colecciones y cuerpos de prueba', () => {
    const data = fixtureCollections();
    for (const name of COLLECTION_NAMES) expect(collectionIssues(name, data[name])).toEqual([]);
    for (const body of FIXTURE_BODIES) expect(bodyIssues(body)).toEqual([]);
  });

  it('indica la ruta exacta del dato incorrecto', () => {
    const [article] = fixtureCollections().articles;
    const issues = collectionIssues('articles', [{ ...article, tags: ['Costa Brava'] }]);
    expect(issues).toEqual([expect.stringMatching(/^articles\[0\]\.tags\[0\]: slug no válido/)]);
  });

  it('rechaza campos obligatorios ausentes, desconocidos y tipos incorrectos', () => {
    const [tag] = fixtureCollections().tags;
    expect(collectionIssues('tags', [{ id: tag.id, slug: tag.slug }])).toEqual([
      'tags[0].label: campo obligatorio',
    ]);
    expect(collectionIssues('tags', [{ ...tag, color: 'rojo' }])).toEqual([
      'tags[0].color: campo desconocido',
    ]);
    expect(collectionIssues('tags', {})).toEqual(['tags: se esperaba una lista']);
  });

  it('rechaza valores fuera de los enumerados y fechas inexistentes', () => {
    const [article] = fixtureCollections().articles;
    const issues = collectionIssues('articles', [
      { ...article, category: 'cocina', format: 'podcast', publishedAt: '2026-02-30' },
    ]);
    expect(issues).toHaveLength(3);
    expect(issues.join('\n')).toContain('articles[0].category: valor no permitido');
    expect(issues.join('\n')).toContain('articles[0].format: valor no permitido');
    expect(issues.join('\n')).toContain('fecha inexistente');
  });

  it('exige imágenes coherentes: punto focal, anchos, licencia y URL https', () => {
    const [media] = fixtureCollections().media;
    const issues = collectionIssues('media', [
      {
        ...media,
        focal: { x: 1.5, y: 0.5 },
        widths: [1024, 640, 4000],
        credit: { ...media.credit, license: 'Todos los derechos', sourceUrl: 'http://x.test' },
      },
    ]);
    const text = issues.join('\n');
    expect(text).toContain('media[0].focal.x: fuera de rango');
    expect(text).toContain('de menor a mayor');
    expect(text).toContain('ningún ancho puede superar');
    expect(text).toContain('media[0].credit.license: valor no permitido');
    expect(text).toContain('media[0].credit.sourceUrl: URL https no válido');
  });

  it('una foto propia no necesita URL de origen y admite fecha, lugar y cámara', () => {
    expect(collectionIssues('media', [ownMedia('propia', 'viajes')])).toEqual([]);
  });

  it('una foto propia no puede declarar origen externo ni original público', () => {
    const own = ownMedia('propia', 'viajes');
    const issues = collectionIssues('media', [
      {
        ...own,
        credit: { ...own.credit, sourceUrl: 'https://otra.test/foto' },
        original: { ...own.original, url: 'https://otra.test/foto.jpg' },
      },
    ]);
    expect(issues).toEqual([
      'media[0].credit.sourceUrl: una foto propia no tiene URL de origen',
      'media[0].original.url: el original de una foto propia no es público',
    ]);
  });

  it('una fuente externa exige URL de la obra, del original y licencia libre', () => {
    const [external] = fixtureCollections().media;
    const credit: Partial<typeof external.credit> = { ...external.credit };
    const original: Partial<typeof external.original> = { ...external.original };
    delete credit.sourceUrl;
    delete original.url;
    const issues = collectionIssues('media', [
      { ...external, credit: { ...credit, license: 'derechos-reservados' }, original },
    ]);
    expect(issues).toEqual([
      'media[0].credit.sourceUrl: obligatoria en fuentes externas',
      'media[0].original.url: obligatoria en fuentes externas',
      'media[0].credit.license: una fuente externa necesita una licencia libre',
    ]);
  });

  it('no publica coordenadas precisas: como máximo dos decimales', () => {
    const own = ownMedia('propia', 'viajes');
    const issues = collectionIssues('media', [
      { ...own, location: { name: 'Casa', point: { lat: 40.41678, lng: -3.7 } } },
    ]);
    expect(issues).toEqual([
      expect.stringMatching(/^media\[0\]\.location\.point\.lat: como máximo/),
    ]);
  });

  it('los destinos propios de un artículo son una lista opcional de slugs', () => {
    const [article] = fixtureCollections().articles;
    expect(collectionIssues('articles', [{ ...article, destinations: ['tossa'] }])).toEqual([]);
    expect(collectionIssues('articles', [{ ...article, destinations: 'tossa' }])).toEqual([
      expect.stringMatching(/^articles\[0\]\.destinations: /),
    ]);
    expect(collectionIssues('articles', [{ ...article, destinations: ['Tossa de Mar'] }])).toEqual([
      expect.stringMatching(/^articles\[0\]\.destinations\[0\]: slug no válido/),
    ]);
  });

  it('la altitud de un destino es opcional, entera y en un rango terrestre', () => {
    const destinations = fixtureCollections().destinations;
    const stelvio = destinations.find((d) => d.id === 'stelvio')!;
    const withoutElevation = { ...stelvio };
    delete withoutElevation.elevation;
    expect(collectionIssues('destinations', [withoutElevation])).toEqual([]);
    expect(collectionIssues('destinations', [{ ...stelvio, elevation: -12 }])).toEqual([]);
    expect(collectionIssues('destinations', [{ ...stelvio, elevation: 2757.5 }])).toEqual([
      expect.stringMatching(/^destinations\[0\]\.elevation: /),
    ]);
    expect(collectionIssues('destinations', [{ ...stelvio, elevation: 12000 }])).toEqual([
      expect.stringMatching(/^destinations\[0\]\.elevation: /),
    ]);
    expect(collectionIssues('destinations', [{ ...stelvio, elevation: '2757' }])).toEqual([
      expect.stringMatching(/^destinations\[0\]\.elevation: /),
    ]);
  });

  it('la versión optimizada no supera al original y la exposición tiene formato válido', () => {
    const own = ownMedia('propia', 'viajes');
    const issues = collectionIssues('media', [
      {
        ...own,
        original: { ...own.original, width: 1200, height: 900 },
        camera: { exposure: 'rápida' },
      },
    ]);
    expect(issues.join('\n')).toContain('media[0].width: la versión optimizada no puede superar');
    expect(issues.join('\n')).toContain('media[0].camera.exposure: exposición no válido');
  });

  it('valida cada tipo de bloque y rechaza los desconocidos', () => {
    expect(bodyIssues({ slug: 'a', blocks: [{ type: 'video', src: 'x' }] })).toEqual([
      'cuerpo.blocks[0].type: tipo desconocido: «video»',
    ]);
    expect(bodyIssues({ slug: 'a', blocks: [] })).toEqual([
      'cuerpo.blocks: se esperaban al menos 1 elementos',
    ]);
    expect(
      bodyIssues({ slug: 'a', blocks: [{ type: 'code', language: 'TypeScript!', code: 'x' }] }),
    ).toEqual([expect.stringContaining('lenguaje no válido')]);
  });

  it('el mapa necesita un itinerario o una lista de destinos, no ambos', () => {
    const both = { type: 'map', trip: 'a', destinations: ['b'], route: true };
    const none = { type: 'map', route: true };
    expect(bodyIssues({ slug: 'a', blocks: [both] })).toHaveLength(1);
    expect(bodyIssues({ slug: 'a', blocks: [none] })).toHaveLength(1);
  });
});

describe('Fotografías del relato', () => {
  const image = (extra: Record<string, unknown>) =>
    bodyIssues({
      slug: 'a',
      blocks: [{ type: 'image', media: 'cala', layout: 'content', ...extra }],
    });
  const figures = (layout: string, n: number, item: Record<string, unknown> = {}) =>
    bodyIssues({
      slug: 'a',
      blocks: [
        {
          type: 'figures',
          layout,
          items: Array.from({ length: n }, (_, i) => ({ media: `foto-${i}`, ...item })),
        },
      ],
    });

  it('imagen: recorte, alineación y punto focal son opcionales y compatibles', () => {
    expect(image({})).toEqual([]);
    expect(image({ ratio: '4/5', align: 'start', focal: { x: 0.3, y: 0.7 } })).toEqual([]);
    for (const ratio of ['4/5', '1/1', '3/2', '16/9', '21/9']) expect(image({ ratio })).toEqual([]);
  });

  it('imagen: rechaza recortes, alineaciones y puntos focales no válidos', () => {
    expect(image({ ratio: '2/1' })[0]).toContain('blocks[0].ratio');
    expect(image({ align: 'center' })[0]).toContain('blocks[0].align');
    expect(image({ focal: { x: 1.4, y: 0.5 } })[0]).toContain('focal.x: fuera de rango');
    expect(image({ focal: { x: 0.5 } })[0]).toContain('focal.y: campo obligatorio');
  });

  it('imagen a sangre: no se alinea a un margen', () => {
    expect(image({ layout: 'full', align: 'end' })).toEqual([
      'cuerpo.blocks[0].align: una imagen a sangre (full) no se alinea a un margen',
    ]);
    expect(image({ layout: 'wide', align: 'end' })).toEqual([]);
  });

  it('composiciones: díptico de 2, tríptico de 3 y secuencia de 2 o 3', () => {
    expect(figures('diptych', 2)).toEqual([]);
    expect(figures('triptych', 3)).toEqual([]);
    expect(figures('sequence', 2)).toEqual([]);
    expect(figures('sequence', 3)).toEqual([]);
    expect(figures('diptych', 3)).toEqual(['cuerpo.blocks[0].items: diptych: 2 fotografías, no 3']);
    expect(figures('triptych', 2)).toEqual([
      'cuerpo.blocks[0].items: triptych: 3 fotografías, no 2',
    ]);
    expect(figures('sequence', 4)).toEqual([
      'cuerpo.blocks[0].items: sequence: entre 2 y 3 fotografías, no 4',
    ]);
  });

  it('composiciones incompletas o no válidas', () => {
    expect(figures('diptych', 1)).toContain(
      'cuerpo.blocks[0].items: se esperaban al menos 2 elementos',
    );
    expect(figures('mosaico', 2)[0]).toContain('blocks[0].layout');
    expect(figures('diptych', 2, { ratio: '5/4' })[0]).toContain('items[0].ratio');
    expect(figures('diptych', 2, { titulo: 'x' })[0]).toContain(
      'items[0].titulo: campo desconocido',
    );
    expect(
      bodyIssues({ slug: 'a', blocks: [{ type: 'figures', layout: 'diptych' }] })[0],
    ).toContain('blocks[0].items: campo obligatorio');
  });
});

describe('Lista blanca de HTML', () => {
  it('admite etiquetas de texto y enlaces relativos, anclas o https', () => {
    expect(
      htmlIssues(
        '<p>Uno <em>dos</em> <strong>tres</strong> <code>x</code><br /></p>' +
          '<h3>Título</h3><ul><li><a href="/viajes">a</a> <a href="#c">b</a></li></ul>' +
          '<ol><li><a href="https://angular.dev">c</a></li></ol>',
      ),
    ).toEqual([]);
  });

  it('rechaza scripts, estilos, atributos y enlaces peligrosos', () => {
    expect(htmlIssues('<script>alert(1)</script>')).toContain('etiqueta no permitida: <script>');
    expect(htmlIssues('<img src="x">')).toContain('etiqueta no permitida: <img>');
    expect(htmlIssues('<p onclick="x()">a</p>')[0]).toContain('atributos no permitidos en <p>');
    expect(htmlIssues('<a href="javascript:alert(1)">a</a>')[0]).toContain('atributos');
    expect(htmlIssues('<a href="http://inseguro.test">a</a>')[0]).toContain('atributos');
    expect(htmlIssues('<p>3 < 4</p>')).toContain('carácter < suelto');
  });

  it('un enlace que empieza por // es externo disfrazado de interno: no se admite', () => {
    expect(htmlIssues('<a href="//dominio.test/x">a</a>')[0]).toContain('atributos');
    expect(htmlIssues('<a href="/viajes" target="_blank">a</a>')[0]).toContain('atributos');
    expect(htmlIssues('<a href="#Mal Ancla">a</a>')[0]).toContain('atributos');
    expect(htmlIssues('<a href="https://dominio.test/ruta con espacios">a</a>')[0]).toContain(
      'atributos',
    );
  });

  it.each([
    ['barra invertida', '/\\dominio.test/x'],
    ['dos barras invertidas', '/\\\\dominio.test'],
    ['barra invertida tras la ruta', '/viajes\\x'],
    ['tabulador entre barras', '/\t/dominio.test'],
    ['salto de línea entre barras', '/\n/dominio.test'],
    ['https con barra invertida', 'https://dominio.test\\x'],
  ])('ruta que el navegador sacaría del sitio (%s): no se admite', (_, href) => {
    expect(htmlIssues(`<p><a href="${href}">a</a></p>`)[0]).toContain('atributos no permitidos');
  });

  it('solo la forma canónica de enlace: la misma que reconoce el render', () => {
    expect(htmlIssues('<p><a href="https://angular.dev">a</a> <a href="#c">b</a></p>')).toEqual([]);
    for (const tag of [
      '<a href="https://angular.dev" >',
      '<a  href="#c">',
      '<a\thref="#c">',
      '<A href="#c">',
      '<a href="#c"/>',
    ]) {
      expect(htmlIssues(`<p>${tag}a</a></p>`), tag).not.toEqual([]);
    }
  });

  it('solo etiquetas en minúsculas y sin espacios; <br> admite sus tres formas', () => {
    expect(htmlIssues('<p>a<br>b<br/>c<br />d</p>')).toEqual([]);
    expect(htmlIssues('<p >a</p>')[0]).toBe('formato no admitido: <p > (se espera <p>)');
    expect(htmlIssues('<P>a</P>')[0]).toBe('formato no admitido: <P> (se espera <p>)');
    expect(htmlIssues('<h3>a</h3 >')[0]).toBe('formato no admitido: </h3 > (se espera </h3>)');
  });

  it('rechaza enlaces anidados y etiquetas sin cerrar', () => {
    expect(htmlIssues('<p><a href="/a"><a href="/b">x</a></a></p>')).toContain(
      'enlace dentro de otro enlace',
    );
    expect(htmlIssues('<p>texto</p')).toContain('carácter < suelto');
  });
});

describe('Tiempo de lectura', () => {
  it('cuenta las palabras de texto, citas, capítulos, fichas y código, sin HTML', () => {
    expect(
      countWords([
        { type: 'text', html: '<p>Una <em>frase</em> corta.</p>' },
        { type: 'quote', text: 'Dos palabras', variant: 'pull' },
        { type: 'chapter', id: 'c', title: 'Capítulo' },
        { type: 'divider', variant: 'line' },
      ]),
    ).toBe(6);
  });

  it('los pies propios de una composición también se leen', () => {
    expect(
      countWords([
        {
          type: 'figures',
          layout: 'diptych',
          items: [{ media: 'a', caption: 'Tres palabras aquí' }, { media: 'b' }],
        },
        { type: 'image', media: 'c', layout: 'content', caption: 'Dos más' },
      ]),
    ).toBe(5);
  });

  it('redondea a minutos, con un mínimo de uno', () => {
    const words = (n: number) => Array.from({ length: n }, () => 'palabra').join(' ');
    expect(readingMinutes([{ type: 'text', html: words(10) }])).toBe(1);
    expect(readingMinutes([{ type: 'text', html: words(1000) }])).toBe(5);
  });
});
