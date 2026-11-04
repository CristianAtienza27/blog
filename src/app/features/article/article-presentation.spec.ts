import type { Article, ArticleFormat, Media, Trip } from '../../content/models';
import { destination, photo, ref, summary } from '../../editorial/testing/editorial-fixtures';
import {
  FULL_BLEED_MIN,
  articleFacts,
  articlePresentation,
  coverSizes,
  cropFactor,
  formatDate,
  headerVariant,
} from './article-presentation';

const SHARP = photo('nitida', { width: 2400, height: 1600, widths: [640, 1024, 1600, 2400] });
const SOFT = photo('blanda', { width: 1600, height: 1067 });
const PANORAMA = photo('panoramica', { width: 2400, height: 889, widths: [640, 1024, 1600, 2400] });

function article(overrides: Partial<Article> = {}): Article {
  return { ...summary('prueba', { cover: SHARP }), blocks: [], ...overrides };
}

function trip(stops: [string, number?][]): Trip {
  return {
    id: 'ruta',
    slug: 'ruta',
    title: 'Ruta',
    summary: 'Ruta de prueba',
    demo: true,
    stops: stops.map(([name, distanceKm], i) => ({
      order: i + 1,
      destination: destination(name.toLowerCase(), name),
      ...(distanceKm ? { distanceKm } : {}),
    })),
  };
}

const variant = (format: ArticleFormat, cover: Media = SHARP, extra: Partial<Article> = {}) =>
  headerVariant(article({ format, cover, ...extra }), cover);

describe('Variante de cabecera', () => {
  it('cada formato tiene su composición', () => {
    expect(variant('reportaje')).toBe('cinema');
    expect(variant('ensayo-fotografico')).toBe('cinema');
    expect(variant('guia')).toBe('split');
    expect(variant('guia', SHARP, { trip: trip([['A'], ['B', 10]]) })).toBe('cinema');
    expect(variant('ficha')).toBe('plate');
    expect(variant('nota')).toBe('compact');
  });

  it('la sección también cuenta: relatos en lámina y tecnología siempre breve', () => {
    const experiencias = { id: 'experiencias', slug: 'e', name: 'Experiencias', description: '' };
    const tecnologia = { id: 'tecnologia', slug: 't', name: 'Tecnología', description: '' };
    expect(variant('reportaje', SHARP, { category: experiencias } as Partial<Article>)).toBe(
      'plate',
    );
    expect(variant('reportaje', SHARP, { category: tecnologia } as Partial<Article>)).toBe(
      'compact',
    );
  });

  it('sin resolución para ir a sangre cambia a una composición que la conserva nítida', () => {
    expect(SOFT.width).toBeLessThan(FULL_BLEED_MIN.width);
    expect(variant('reportaje', SOFT)).toBe('split');
    expect(variant('ficha', SOFT)).toBe('split');
    // Panorámica: poca altura para el fotograma completo, pero perfecta a lo ancho.
    expect(PANORAMA.height).toBeLessThan(FULL_BLEED_MIN.height);
    expect(variant('ensayo-fotografico', PANORAMA)).toBe('plate');
  });

  it('sin foto utilizable, cabecera breve sin imagen', () => {
    expect(headerVariant(article({ format: 'reportaje' }), null)).toBe('compact');
  });
});

describe('Tamaños de la foto según el recorte', () => {
  it('factor de recorte: solo crece si la foto es más ancha que el marco', () => {
    expect(cropFactor(1.5, 0.8)).toBeCloseTo(1.875);
    expect(cropFactor(1.5, 16 / 9)).toBe(1);
  });

  it('una foto 3:2 en un marco vertical pide casi el doble de ancho', () => {
    expect(coverSizes('cinema', SHARP)).toBe('(width < 900px) 188vw, 100vw');
    expect(coverSizes('split', SOFT)).toBe('(width < 900px) 188vw, 57vw');
    expect(coverSizes('plate', PANORAMA)).toBe(
      '(width < 900px) 180vw, (width >= 1568px) 1440px, 92vw',
    );
    expect(coverSizes('compact', SOFT, true)).toBe(
      '(width < 900px) 90vw, (width >= 1328px) 800px, 61vw',
    );
  });
});

describe('Metadatos de la cabecera', () => {
  it('recorrido de un itinerario: paradas, extremos y distancia', () => {
    const facts = articleFacts(
      article({ trip: trip([['Tossa'], ['Calella', 42], ['Begur', 10], ['Cadaqués', 65]]) }),
    );
    expect(facts.map((fact) => fact.value)).toEqual([
      '2 min de lectura',
      '4 paradas: de Tossa a Cadaqués',
      '117 km aprox.',
    ]);
  });

  it('un lugar con su región y país; varios, solo los nombres', () => {
    const cala = destination('cala', "Cala s'Almunia", { region: 'Mallorca' });
    expect(articleFacts(article({ destinations: [ref(cala)] }))[1].value).toBe(
      "Cala s'Almunia · Mallorca · España",
    );
    const lisboa = destination('lisboa', 'Lisboa', { country: 'PT' });
    const praga = destination('praga', 'Praga', { country: 'CZ' });
    expect(articleFacts(article({ destinations: [ref(lisboa), ref(praga)] }))[1].value).toBe(
      'Lisboa · Praga',
    );
  });

  it('el contenido de demostración no muestra fechas (no son reales)', () => {
    const facts = articleFacts(article({ demo: true, publishedAt: '2026-09-18' }));
    expect(facts.some((fact) => fact.datetime)).toBe(false);
    expect(JSON.stringify(facts)).not.toContain('2026');
  });

  it('el contenido definitivo muestra la fecha con <time> y la actualización si la hay', () => {
    const facts = articleFacts(
      article({ demo: false, publishedAt: '2026-09-18', updatedAt: '2026-10-02' }),
    );
    expect(facts.slice(-2)).toEqual([
      { label: 'Publicado', value: '18 de septiembre de 2026', datetime: '2026-09-18' },
      {
        label: 'Actualizado',
        value: 'Actualizado el 2 de octubre de 2026',
        datetime: '2026-10-02',
      },
    ]);
    expect(formatDate('2026-01-01')).toBe('1 de enero de 2026');
  });
});

describe('Presentación completa', () => {
  it('sección numerada, formato y foto con su recorte', () => {
    const { header } = articlePresentation(article({ format: 'guia', cover: SOFT }));
    expect(header.section).toEqual({ id: 'viajes', index: 1, label: 'Viajes', path: '/viajes' });
    expect(header.format).toBe('Guía');
    expect(header.variant).toBe('split');
    expect(header.cover?.media).toBe(SOFT);
    expect(header.demo).toBe(true);
  });

  it('nunca usa una fotografía retirada: el artículo queda sin foto de cabecera', () => {
    const retired = photo('motor-moto-trail', { width: 2400, height: 1800 });
    const { header } = articlePresentation(article({ format: 'ficha', cover: retired }));
    expect(header.cover).toBeNull();
    expect(header.variant).toBe('compact');
  });

  it('compact con una foto sin resolución para todo el ancho la estrecha a ocho columnas', () => {
    const { header } = articlePresentation(article({ format: 'nota', cover: SOFT }));
    expect(header.cover?.narrow).toBe(true);
    const wide = articlePresentation(article({ format: 'nota', cover: PANORAMA })).header;
    expect(wide.cover?.narrow).toBe(false);
    // Panorámica: se muestra con su proporción, sin recortar.
    expect(wide.cover?.ratio).toBe('2400 / 889');
  });
});
