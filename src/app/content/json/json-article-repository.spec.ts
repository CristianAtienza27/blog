import { TestBed } from '@angular/core/testing';
import { ArticleRepository, MAX_PAGE_SIZE } from '../repositories/article-repository';
import { FIXTURE_BODIES, fixtureCollections, fixtureSource, ownMedia } from '../testing/fixtures';
import { provideFixtureContent } from '../testing/provide-fixture-content';

function setup(source = fixtureSource()) {
  TestBed.configureTestingModule({ providers: provideFixtureContent(source.source) });
  return { repository: TestBed.inject(ArticleRepository), loads: source.loads };
}

const slugs = (items: { slug: string }[]) => items.map((item) => item.slug);

describe('ArticleRepository (adaptador JSON)', () => {
  it('sin orden expreso, lista en orden editorial (sección y `order`), no por fecha', async () => {
    const { repository } = setup();
    const page = await repository.list();
    expect(slugs(page.items)).toEqual([
      'calas-costa-brava',
      'serie-costa',
      'paso-stelvio',
      'equipaje-ruta',
      'construir-revista',
    ]);
    expect(page).toMatchObject({ total: 5, offset: 0, limit: 12 });
  });

  it('ordena del más reciente al más antiguo solo si se pide', async () => {
    const { repository } = setup();
    const page = await repository.list({ sort: 'newest' });
    expect(slugs(page.items)).toEqual([
      'construir-revista',
      'calas-costa-brava',
      'serie-costa',
      'paso-stelvio',
      'equipaje-ruta',
    ]);
  });

  it('dentro de una sección, el orden editorial manda sobre la fecha', async () => {
    const collections = fixtureCollections();
    // Un segundo artículo de Viajes, más reciente pero con orden 2.
    const second = structuredClone(
      collections.articles.find((a) => a.slug === 'calas-costa-brava'),
    );
    if (!second) throw new Error('falta el artículo de prueba');
    Object.assign(second, { id: 'nuevo', slug: 'nuevo', order: 2, publishedAt: '2026-12-31' });
    collections.articles.push(second);
    const { repository } = setup(
      fixtureSource(collections, [
        ...FIXTURE_BODIES,
        { slug: 'nuevo', blocks: [{ type: 'text', html: '<p>Texto.</p>' }] },
      ]),
    );
    expect(slugs((await repository.list({ category: 'viajes' })).items)).toEqual([
      'calas-costa-brava',
      'nuevo',
    ]);
    expect(slugs((await repository.list({ category: 'viajes', sort: 'newest' })).items)).toEqual([
      'nuevo',
      'calas-costa-brava',
    ]);
  });

  it('ordena del más antiguo al más reciente si se pide', async () => {
    const { repository } = setup();
    const page = await repository.list({ sort: 'oldest', limit: 2 });
    expect(slugs(page.items)).toEqual(['equipaje-ruta', 'paso-stelvio']);
  });

  it('filtra por categoría, etiqueta, formato y destacados', async () => {
    const { repository } = setup();
    expect(slugs((await repository.list({ category: 'motor' })).items)).toEqual(['paso-stelvio']);
    expect(slugs((await repository.list({ tag: 'costa' })).items)).toEqual([
      'calas-costa-brava',
      'serie-costa',
    ]);
    expect(slugs((await repository.list({ format: 'nota' })).items)).toEqual(['equipaje-ruta']);
    expect(slugs((await repository.list({ featured: true })).items)).toEqual([
      'calas-costa-brava',
      'paso-stelvio',
    ]);
    expect((await repository.list({ category: 'motor', tag: 'costa' })).total).toBe(0);
  });

  it('filtra por itinerario, destino y proyecto', async () => {
    const { repository } = setup();
    expect(slugs((await repository.list({ trip: 'alpes' })).items)).toEqual(['paso-stelvio']);
    expect(slugs((await repository.list({ destination: 'begur' })).items)).toEqual([
      'calas-costa-brava',
    ]);
    expect(slugs((await repository.list({ project: 'revista' })).items)).toEqual([
      'construir-revista',
    ]);
  });

  it('expone los destinos de cada artículo: paradas del itinerario y destinos propios', async () => {
    const { repository } = setup();
    const { items } = await repository.list();
    const places = (slug: string) =>
      items.find((a) => a.slug === slug)?.destinations.map((d) => d.slug);
    expect(places('calas-costa-brava')).toEqual(['tossa', 'begur']);
    expect(places('serie-costa')).toEqual(['tossa']);
    expect(places('construir-revista')).toEqual([]);
    // Referencias ligeras: sin portada ni descripción del destino.
    const begur = items.find((a) => a.slug === 'calas-costa-brava')?.destinations[1];
    expect(begur).not.toHaveProperty('cover');
    expect(begur).not.toHaveProperty('summary');
  });

  it('el filtro por destino incluye los destinos propios, no solo las paradas', async () => {
    const { repository } = setup();
    expect(slugs((await repository.list({ destination: 'tossa' })).items)).toEqual([
      'calas-costa-brava',
      'serie-costa',
    ]);
  });

  it('pagina con límites seguros', async () => {
    const { repository } = setup();
    const second = await repository.list({ offset: 2, limit: 2 });
    expect(slugs(second.items)).toEqual(['paso-stelvio', 'equipaje-ruta']);
    expect(second).toMatchObject({ total: 5, offset: 2, limit: 2 });
    expect(await repository.list({ offset: -4, limit: 0 })).toMatchObject({ offset: 0, limit: 1 });
    expect((await repository.list({ limit: 999 })).limit).toBe(MAX_PAGE_SIZE);
    expect((await repository.list({ offset: 50 })).items).toEqual([]);
  });

  it('devuelve el artículo completo con sus relaciones resueltas', async () => {
    const { repository } = setup();
    const article = await repository.bySlug('calas-costa-brava');
    expect(article?.category.name).toBe('Viajes');
    expect(article?.author.name).toBe('Autor de prueba');
    expect(article?.cover?.focal).toEqual({ x: 0.3, y: 0.6 });
    expect(article?.trip?.stops.map((stop) => stop.destination.name)).toEqual([
      'Tossa de Mar',
      'Begur',
    ]);

    const types = article?.blocks.map((block) => block.type);
    expect(types).toEqual(['chapter', 'text', 'map', 'image', 'chapter', 'gallery', 'quote']);
    const [, , map, image, , gallery] = article?.blocks ?? [];
    expect(map).toMatchObject({
      type: 'map',
      route: true,
      points: [
        { label: 'Tossa de Mar', slug: 'tossa', location: { lat: 41.72, lng: 2.93 } },
        { label: 'Begur', slug: 'begur' },
      ],
    });
    expect(image).toMatchObject({ type: 'image', media: { id: 'cala', width: 2400 } });
    expect(gallery).toMatchObject({
      type: 'gallery',
      gallery: {
        slug: 'serie-costa',
        items: [{ media: { id: 'cala' } }, { media: { id: 'puerto' } }],
      },
    });
  });

  it('el dominio no expone los datos internos del original', async () => {
    const { repository } = setup();
    const article = await repository.bySlug('serie-costa');
    expect(article?.cover).not.toHaveProperty('original');
  });

  it('expone los datos de una foto propia sin el original', async () => {
    const { repository } = setup();
    const cover = (await repository.bySlug('paso-stelvio'))?.cover;
    expect(cover).toMatchObject({
      takenAt: '2025-08-14',
      location: { name: 'Paso del Stelvio', point: { lat: 46.53, lng: 10.45 } },
      camera: { aperture: 1.8, exposure: '1/250' },
      credit: { source: 'propia', license: 'derechos-reservados', author: 'Autor de prueba' },
    });
    expect(cover?.credit.sourceUrl).toBeUndefined();
    expect(cover).not.toHaveProperty('original');
  });

  it('sustituir una imagen provisional por una propia no requiere tocar los artículos', async () => {
    const data = fixtureCollections();
    const index = data.media.findIndex((m) => m.id === 'cala');
    data.media[index] = { ...ownMedia('cala', 'viajes'), path: 'viajes/cala.0a0b0c0d' };
    const { repository } = setup(fixtureSource(data));

    const article = await repository.bySlug('calas-costa-brava');
    expect(article?.cover).toMatchObject({ id: 'cala', credit: { source: 'propia' } });
    const gallery = article?.blocks.find((b) => b.type === 'gallery');
    expect(gallery).toMatchObject({
      gallery: { items: [{ media: { path: 'viajes/cala.0a0b0c0d' } }, {}] },
    });
  });

  it('resuelve proyectos, SEO y mapas por lista de destinos', async () => {
    const { repository } = setup();
    const tech = await repository.bySlug('construir-revista');
    expect(tech?.project?.stack.map((tag) => tag.label)).toEqual(['Angular']);
    expect(tech?.seo?.description).toBe('Descripción SEO de prueba');
    const motor = await repository.bySlug('paso-stelvio');
    expect(motor?.blocks.find((b) => b.type === 'map')).toMatchObject({
      points: [{ slug: 'prato' }, { slug: 'stelvio' }],
      route: false,
    });
  });

  it('devuelve null si el artículo no existe, sin intentar cargar su cuerpo', async () => {
    const { repository, loads } = setup();
    expect(await repository.bySlug('no-existe')).toBeNull();
    expect(await repository.bySlug('../../secreto')).toBeNull();
    expect(loads.bodies).toEqual([]);
  });

  it('carga el catálogo una sola vez y los cuerpos solo bajo demanda', async () => {
    const { repository, loads } = setup();
    await repository.list();
    await repository.list({ category: 'viajes' });
    expect(loads.bodies).toEqual([]);
    await repository.bySlug('paso-stelvio');
    expect(loads.bodies).toEqual(['paso-stelvio']);
    expect([...loads.collections.values()].every((count) => count === 1)).toBe(true);
  });

  it('falla con un mensaje claro si falta el cuerpo de un artículo', async () => {
    const { repository } = setup(fixtureSource(fixtureCollections(), []));
    await expect(repository.bySlug('serie-costa')).rejects.toThrow(
      'Falta el cuerpo del artículo «serie-costa»',
    );
  });

  it('en desarrollo rechaza contenido con forma incorrecta', async () => {
    const data = fixtureCollections();
    (data.tags[0] as { slug: string }).slug = 'Con Mayúsculas';
    const { repository } = setup(fixtureSource(data));
    await expect(repository.list()).rejects.toThrow(/tags\[0\]\.slug: slug no válido/);
  });

  it('busca por texto y combina la búsqueda con filtros', async () => {
    const { repository } = setup();
    expect(slugs((await repository.search('costa')).items)).toEqual([
      'calas-costa-brava',
      'serie-costa',
    ]);
    // Coincide por el nombre de un destino: del itinerario o propio del artículo.
    expect(slugs((await repository.search('tossa')).items)).toEqual([
      'calas-costa-brava',
      'serie-costa',
    ]);
    expect(slugs((await repository.search('begur')).items)).toEqual(['calas-costa-brava']);
    // Coincide por la pila tecnológica del proyecto.
    expect(slugs((await repository.search('angular')).items)).toEqual(['construir-revista']);
    expect(slugs((await repository.search('costa', { category: 'fotografia' })).items)).toEqual([
      'serie-costa',
    ]);
    expect((await repository.search('')).total).toBe(0);
  });

  it('los resultados de búsqueda pueden ordenarse por fecha', async () => {
    const { repository } = setup();
    const page = await repository.search('moto', { sort: 'oldest' });
    expect(slugs(page.items)).toEqual(['equipaje-ruta', 'paso-stelvio']);
  });

  it('sugiere artículos relacionados por sección y etiquetas', async () => {
    const { repository } = setup();
    expect(slugs(await repository.related('paso-stelvio'))).toEqual(['equipaje-ruta']);
    expect(slugs(await repository.related('calas-costa-brava'))).toEqual(['serie-costa']);
    expect(await repository.related('no-existe')).toEqual([]);
  });

  it('un destino compartido también relaciona artículos (sin sección ni etiquetas en común)', async () => {
    const collections = fixtureCollections();
    // «Cómo se construye una revista» pasa a tratar Prato, una parada del Stelvio.
    const revista = collections.articles.find((a) => a.slug === 'construir-revista');
    if (revista) revista.destinations = ['prato'];
    const { repository } = setup(fixtureSource(collections));
    // Empate (una etiqueta compartida frente a un destino compartido): manda el orden editorial
    // (Experiencias va antes que Tecnología), no la fecha (la revista es más reciente).
    expect(slugs(await repository.related('paso-stelvio'))).toEqual([
      'equipaje-ruta',
      'construir-revista',
    ]);
  });

  it('expone todos los slugs para prerenderizar', async () => {
    const { repository } = setup();
    expect((await repository.slugs()).sort()).toEqual(
      fixtureCollections()
        .articles.map((a) => a.slug)
        .sort(),
    );
  });
});
