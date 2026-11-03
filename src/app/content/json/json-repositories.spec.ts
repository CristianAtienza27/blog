import { TestBed } from '@angular/core/testing';
import { AuthorRepository } from '../repositories/author-repository';
import { MediaRepository } from '../repositories/media-repository';
import { ProjectRepository } from '../repositories/project-repository';
import { TaxonomyRepository } from '../repositories/taxonomy-repository';
import { TravelRepository } from '../repositories/travel-repository';
import { fixtureSource } from '../testing/fixtures';
import { provideFixtureContent } from '../testing/provide-fixture-content';

beforeEach(() => {
  TestBed.configureTestingModule({ providers: provideFixtureContent(fixtureSource().source) });
});

describe('TaxonomyRepository (adaptador JSON)', () => {
  it('devuelve las secciones en su orden editorial y las etiquetas por orden alfabético', async () => {
    const taxonomy = TestBed.inject(TaxonomyRepository);
    expect((await taxonomy.categories()).map((c) => c.id)).toEqual([
      'viajes',
      'fotografia',
      'motor',
      'experiencias',
      'tecnologia',
    ]);
    expect((await taxonomy.tags()).map((t) => t.label)).toEqual([
      'Angular',
      'Costa',
      'Fotografía nocturna',
      'Montaña',
      'Motocicletas',
    ]);
    expect((await taxonomy.category('motor'))?.name).toBe('Motor');
    expect((await taxonomy.tag('montana'))?.label).toBe('Montaña');
    expect(await taxonomy.tag('nada')).toBeNull();
  });
});

describe('MediaRepository (adaptador JSON)', () => {
  it('devuelve imágenes en el orden pedido y omite las inexistentes', async () => {
    const media = TestBed.inject(MediaRepository);
    expect((await media.mediaList(['moto', 'nada', 'cala'])).map((m) => m.id)).toEqual([
      'moto',
      'cala',
    ]);
    expect((await media.media('puerto'))?.credit.license).toBe('CC0-1.0');
    expect(await media.media('nada')).toBeNull();
  });

  it('resuelve las galerías con sus imágenes', async () => {
    const media = TestBed.inject(MediaRepository);
    const gallery = await media.gallery('serie-costa');
    expect(gallery?.items.map((item) => [item.media.id, item.caption])).toEqual([
      ['cala', 'Primera'],
      ['puerto', undefined],
    ]);
    expect(await media.galleries()).toHaveLength(1);
  });
});

describe('TravelRepository (adaptador JSON)', () => {
  it('un itinerario recorre sus destinos en orden', async () => {
    const travel = TestBed.inject(TravelRepository);
    const trip = await travel.trip('alpes');
    expect(trip?.stops.map((stop) => [stop.order, stop.destination.slug, stop.distanceKm])).toEqual(
      [
        [1, 'prato', undefined],
        [2, 'stelvio', 25],
      ],
    );
  });

  it('un destino conoce los itinerarios que pasan por él', async () => {
    const travel = TestBed.inject(TravelRepository);
    expect((await travel.tripsByDestination('begur')).map((t) => t.slug)).toEqual(['costa-brava']);
    expect(await travel.tripsByDestination('nada')).toEqual([]);
  });

  it('filtra destinos por país y resuelve su portada', async () => {
    const travel = TestBed.inject(TravelRepository);
    expect((await travel.destinations({ country: 'it' })).map((d) => d.name)).toEqual([
      'Paso del Stelvio',
      'Prato allo Stelvio',
    ]);
    expect((await travel.destinations()).length).toBe(4);
    expect((await travel.destination('begur'))?.cover?.id).toBe('cala');
    expect(await travel.destination('nada')).toBeNull();
  });
});

describe('ProjectRepository y AuthorRepository (adaptador JSON)', () => {
  it('resuelve la pila tecnológica de cada proyecto', async () => {
    const projects = TestBed.inject(ProjectRepository);
    expect((await projects.list()).map((p) => p.slug)).toEqual(['revista']);
    expect((await projects.bySlug('revista'))?.stack.map((t) => t.slug)).toEqual(['angular']);
    expect(await projects.bySlug('nada')).toBeNull();
  });

  it('devuelve los autores', async () => {
    const authors = TestBed.inject(AuthorRepository);
    expect((await authors.byId('autor-demo'))?.name).toBe('Autor de prueba');
    expect(await authors.list()).toHaveLength(1);
    expect(await authors.byId('nadie')).toBeNull();
  });
});
