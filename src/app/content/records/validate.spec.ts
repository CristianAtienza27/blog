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

  it('redondea a minutos, con un mínimo de uno', () => {
    const words = (n: number) => Array.from({ length: n }, () => 'palabra').join(' ');
    expect(readingMinutes([{ type: 'text', html: words(10) }])).toBe(1);
    expect(readingMinutes([{ type: 'text', html: words(1000) }])).toBe(5);
  });
});
