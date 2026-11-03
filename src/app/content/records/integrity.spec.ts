import { FIXTURE_BODIES, fixtureCollections } from '../testing/fixtures';
import { checkIntegrity, validateContent } from './integrity';
import type { ArticleBodyRecord, ContentCollections } from './records';

const bodies = (list: ArticleBodyRecord[] = FIXTURE_BODIES) =>
  new Map(list.map((b) => [b.slug, b]));

function issuesFor(mutate: (data: ContentCollections) => void, list?: ArticleBodyRecord[]) {
  const data = fixtureCollections();
  mutate(data);
  return checkIntegrity(data, bodies(list));
}

describe('Integridad de las relaciones', () => {
  it('el conjunto de prueba es coherente', () => {
    expect(checkIntegrity(fixtureCollections(), bodies())).toEqual([]);
  });

  it('detecta referencias rotas de artículos', () => {
    const issues = issuesFor((data) => {
      Object.assign(data.articles[0], {
        tags: ['inexistente'],
        author: 'nadie',
        trip: 'perdido',
        project: 'ninguno',
      });
    });
    expect(issues).toEqual(
      expect.arrayContaining([
        'articles[0].tags[0]: etiqueta inexistente «inexistente»',
        'articles[0].author: autor inexistente «nadie»',
        'articles[0].trip: itinerario inexistente «perdido»',
        'articles[0].project: proyecto inexistente «ninguno»',
      ]),
    );
  });

  it('detecta imágenes, galerías y destinos inexistentes dentro de los bloques', () => {
    const broken: ArticleBodyRecord = {
      slug: 'calas-costa-brava',
      blocks: [
        { type: 'image', media: 'fantasma', layout: 'wide' },
        { type: 'gallery', gallery: 'fantasma' },
        { type: 'map', destinations: ['fantasma'], route: false },
      ],
    };
    const issues = issuesFor(
      () => undefined,
      FIXTURE_BODIES.map((b) => (b.slug === broken.slug ? broken : b)),
    );
    expect(issues.join('\n')).toContain('imagen inexistente «fantasma»');
    expect(issues.join('\n')).toContain('galería inexistente «fantasma»');
    expect(issues.join('\n')).toContain('destino inexistente «fantasma»');
  });

  it('las paradas de un itinerario apuntan a destinos y van numeradas desde 1', () => {
    const issues = issuesFor((data) => {
      data.trips[0].stops[1] = { ...data.trips[0].stops[1], order: 3, destination: 'atlantida' };
    });
    expect(issues).toEqual(
      expect.arrayContaining([
        'trips[0].stops[1]: destino inexistente «atlantida»',
        'trips[0].stops[1]: orden 3, se esperaba 2',
      ]),
    );
  });

  it('detecta identificadores y slugs repetidos', () => {
    const issues = issuesFor((data) => data.tags.push({ ...data.tags[0] }));
    expect(issues).toEqual(
      expect.arrayContaining(['tags: id repetido «costa»', 'tags: slug repetido «costa»']),
    );
  });

  it('las categorías son exactamente las cinco secciones', () => {
    const issues = issuesFor((data) => data.categories.reverse());
    expect(issues[0]).toMatch(/^categories: deben ser viajes, fotografia/);
  });

  it('cada artículo tiene su cuerpo y no hay cuerpos huérfanos', () => {
    const withoutFirst = FIXTURE_BODIES.slice(1);
    const orphan: ArticleBodyRecord = {
      slug: 'huerfano',
      blocks: [{ type: 'divider', variant: 'line' }],
    };
    const issues = issuesFor(() => undefined, [...withoutFirst, orphan]);
    expect(issues).toEqual(
      expect.arrayContaining([
        'articles[0]: falta el cuerpo articles/calas-costa-brava.json',
        'articles/huerfano.json: cuerpo sin artículo',
      ]),
    );
  });

  it('el tiempo de lectura guardado coincide con el calculado', () => {
    const issues = issuesFor((data) => (data.articles[0].readingMinutes = 9));
    expect(issues).toEqual(['articles[0].readingMinutes: 9, calculado 1']);
  });

  it('no admite imágenes sin usar ni duplicadas', () => {
    const issues = issuesFor((data) => {
      data.media.push({ ...data.media[0], id: 'copia', path: 'viajes/copia.00000005' });
    });
    expect(issues).toEqual(
      expect.arrayContaining([
        expect.stringContaining('media: original duplicado'),
        expect.stringContaining('media: fuente duplicada'),
        'media: imagen sin usar «copia»',
      ]),
    );
  });

  it('acepta imágenes usadas fuera del contenido (por ejemplo, el menú)', () => {
    const data = fixtureCollections();
    const article = data.articles.find((a) => a.slug === 'construir-revista');
    if (article) article.cover = 'moto';
    const list = FIXTURE_BODIES.map((b) =>
      b.slug === 'construir-revista'
        ? { ...b, blocks: b.blocks.filter((block) => block.type !== 'image') }
        : b,
    );
    expect(checkIntegrity(data, bodies(list))).toEqual(['media: imagen sin usar «codigo»']);
    expect(checkIntegrity(data, bodies(list), { externalMedia: ['codigo'] })).toEqual([]);
  });

  it('las fotografías propias se atribuyen a un autor de la revista', () => {
    const issues = issuesFor((data) => {
      const own = data.media.find((m) => m.credit.source === 'propia');
      if (own) own.credit.author = 'Otra persona';
    });
    expect(issues).toEqual(['media[2].credit.author: «Otra persona» no es un autor de la revista']);
  });

  it('validateContent no comprueba relaciones si la forma ya es incorrecta', () => {
    const data = fixtureCollections() as unknown as Record<keyof ContentCollections, unknown>;
    data.tags = 'no es una lista';
    expect(validateContent(data, bodies())).toEqual(['tags: se esperaba una lista']);
  });
});
