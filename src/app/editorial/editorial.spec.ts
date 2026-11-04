import { articleMeta, articleUrl, mainPlace, placeArea } from './article-meta';
import { buildDestinationIndex } from './destination-index';
import { destination, photo, ref, summary } from './testing/editorial-fixtures';

const TOSSA = destination('tossa-de-mar', 'Tossa de Mar', { cover: photo('tossa') });
const CADAQUES = destination('cadaques', 'Cadaqués', { cover: photo('cadaques') });
const BEGUR = destination('begur', 'Begur');
const LISBOA = destination('lisboa', 'Lisboa', {
  country: 'PT',
  region: 'Lisboa',
  cover: photo('lisboa'),
});
const STELVIO = destination('paso-del-stelvio', 'Paso del Stelvio', {
  country: 'IT',
  region: undefined,
  cover: photo('motor-stelvio-bormio'),
});

describe('Metadatos editoriales', () => {
  it('URL de artículo jerárquica: /<sección>/<slug>', () => {
    expect(articleUrl(summary('calas-de-mallorca'))).toBe('/viajes/calas-de-mallorca');
  });

  it('región y país en español; sin región, solo el país', () => {
    expect(placeArea(TOSSA)).toBe('Girona · España');
    expect(placeArea(STELVIO)).toBe('Italia');
  });

  it('formato y minutos de lectura', () => {
    expect(articleMeta(summary('a', { format: 'reportaje', readingMinutes: 4 }))).toBe(
      'Reportaje · 4 min',
    );
  });

  it('el lugar principal es el retratado en la portada; si no, el primero', () => {
    const article = summary('a', { destinations: [ref(TOSSA), ref(CADAQUES)] });
    expect(mainPlace(article, 'cadaques')?.name).toBe('Cadaqués');
    expect(mainPlace(article, 'otro')?.name).toBe('Tossa de Mar');
    expect(mainPlace(summary('b'))).toBeNull();
  });
});

describe('Índice de destinos', () => {
  const costaBrava = summary('itinerario-costa-brava', {
    destinations: [ref(TOSSA), ref(BEGUR), ref(CADAQUES)],
  });
  const ciudades = summary('ciudades-luz-de-tarde', { destinations: [ref(LISBOA)] });
  const motor = summary('paso-del-stelvio', {
    category: { id: 'motor', slug: 'motor', name: 'Motor', description: '' },
    destinations: [ref(STELVIO), ref(TOSSA)],
  });
  // Del más reciente al más antiguo: el artículo de motor es el más nuevo.
  const articles = [motor, costaBrava, ciudades];
  const index = buildDestinationIndex([TOSSA, STELVIO, LISBOA, CADAQUES, BEGUR], articles, {
    excludeMedia: ['motor-stelvio-bormio'],
    preferSection: 'viajes',
  });

  it('orden alfabético en español y numeración de dos cifras', () => {
    expect(index.map((entry) => [entry.number, entry.name])).toEqual([
      ['01', 'Begur'],
      ['02', 'Cadaqués'],
      ['03', 'Lisboa'],
      ['04', 'Paso del Stelvio'],
      ['05', 'Tossa de Mar'],
    ]);
  });

  it('enlaza la publicación de la sección preferida aunque haya otra más reciente', () => {
    const tossa = index.find((entry) => entry.id === 'tossa-de-mar');
    expect(tossa?.article).toEqual({
      title: costaBrava.title,
      href: '/viajes/itinerario-costa-brava',
    });
    expect(index.find((entry) => entry.id === 'paso-del-stelvio')?.article?.href).toBe(
      '/motor/paso-del-stelvio',
    );
  });

  it('sin artículo relacionado, la línea no enlaza', () => {
    const index2 = buildDestinationIndex([destination('praga', 'Praga')], articles);
    expect(index2[0].article).toBeNull();
  });

  it('vista previa: la portada del destino, salvo las fotos excluidas', () => {
    expect(index.find((entry) => entry.id === 'tossa-de-mar')?.preview?.id).toBe('tossa');
    expect(index.find((entry) => entry.id === 'paso-del-stelvio')?.preview).toBeNull();
    expect(index.find((entry) => entry.id === 'begur')?.preview).toBeNull();
  });

  it('región y país de cada destino', () => {
    expect(index.find((entry) => entry.id === 'lisboa')?.area).toBe('Lisboa · Portugal');
  });
});
