import { buildIndex, normalize, queryTerms, search, tokenize } from './search';

describe('Búsqueda', () => {
  const index = buildIndex([
    {
      id: 'stelvio',
      fields: [
        { text: 'El paso del Stelvio', weight: 5 },
        { text: 'Montaña, Motocicletas', weight: 3 },
      ],
    },
    {
      id: 'fotografia',
      fields: [
        { text: 'La hora azul', weight: 5 },
        { text: 'Fotografía de montaña al anochecer', weight: 2 },
      ],
    },
    { id: 'montana', fields: [{ text: 'Montaña', weight: 5 }] },
  ]);
  const ids = (query: string) => search(index, query).map((hit) => hit.id);

  it('normaliza tildes, diéresis y mayúsculas', () => {
    expect(normalize('Fotografía PINGÜINO Ñandú')).toBe('fotografia pinguino nandu');
    expect(tokenize('Curvas, asfalto y «máquinas»')).toEqual([
      'curvas',
      'asfalto',
      'y',
      'maquinas',
    ]);
  });

  it('descarta palabras vacías y letras sueltas, pero no los números', () => {
    expect(queryTerms('La ruta de la costa y el mar')).toEqual(['ruta', 'costa', 'mar']);
    expect(queryTerms('Ruta 5 ruta')).toEqual(['ruta', '5']);
  });

  it('no distingue tildes ni mayúsculas', () => {
    expect(ids('FOTOGRAFIA')).toEqual(['fotografia']);
    expect(ids('montana')).toContain('stelvio');
  });

  it('encuentra palabras por su comienzo', () => {
    expect(ids('moto')).toEqual(['stelvio']);
  });

  it('exige todos los términos', () => {
    expect(ids('montaña azul')).toEqual(['fotografia']);
    expect(ids('stelvio azul')).toEqual([]);
  });

  it('ordena por relevancia: el título pesa más que el resto', () => {
    expect(ids('montaña')).toEqual(['montana', 'stelvio', 'fotografia']);
  });

  it('sin términos útiles no devuelve resultados', () => {
    expect(ids('')).toEqual([]);
    expect(ids('   de la  ')).toEqual([]);
  });
});
