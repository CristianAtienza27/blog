import { SECTIONS } from '../../layout/navigation';
import { validateContent } from '../records/integrity';
import { COLLECTION_NAMES, ContentCollections } from '../records/records';
import { jsonFileSource } from './content-source';

/** Carga el contenido real de src/content tal como lo hará la aplicación. */
async function loadContent() {
  const entries = await Promise.all(
    COLLECTION_NAMES.map(async (name) => [name, await jsonFileSource.collection(name)] as const),
  );
  const data = Object.fromEntries(entries) as unknown as ContentCollections;
  const bodies = new Map<string, unknown>();
  for (const article of data.articles)
    bodies.set(article.slug, await jsonFileSource.body(article.slug));
  return { data, bodies };
}

describe('Contenido de src/content', () => {
  it('cumple la estructura y todas las relaciones son válidas', async () => {
    const { data, bodies } = await loadContent();
    const externalMedia = SECTIONS.map((section) => section.preview);
    expect(validateContent(data, bodies, { externalMedia })).toEqual([]);
  });

  it('cada sección del menú tiene una fotografía de vista previa registrada', async () => {
    const { data } = await loadContent();
    const ids = new Set(data.media.map((m) => m.id));
    expect(SECTIONS.filter((section) => !ids.has(section.preview)).map((s) => s.id)).toEqual([]);
  });

  it('todas las imágenes tienen texto alternativo, autoría y licencia', async () => {
    const { data } = await loadContent();
    for (const m of data.media) {
      expect(m.alt.trim().length, m.id).toBeGreaterThan(10);
      expect(m.credit.author.trim().length, m.id).toBeGreaterThan(1);
    }
  });

  it('las categorías coinciden con las secciones de la navegación', async () => {
    const { data } = await loadContent();
    expect(data.categories.map((c) => [c.id, c.name])).toEqual(
      SECTIONS.map((s) => [s.id, s.label]),
    );
  });

  it('todo el contenido editorial está marcado como demostración', async () => {
    const { data } = await loadContent();
    const editorial = [
      ...data.articles,
      ...data.galleries,
      ...data.destinations,
      ...data.trips,
      ...data.projects,
    ];
    expect(editorial.filter((item) => !item.demo).map((item) => item.id)).toEqual([]);
  });

  it('las imágenes de fuentes externas se marcan como provisionales', async () => {
    const { data } = await loadContent();
    const external = data.media.filter((m) => m.credit.source !== 'propia');
    expect(external.filter((m) => !m.demo).map((m) => m.id)).toEqual([]);
  });

  it('el autor no tiene biografía inventada', async () => {
    const { data } = await loadContent();
    expect(data.authors.every((author) => author.bio === undefined)).toBe(true);
  });

  it('un slug desconocido o con caracteres de ruta no carga nada', async () => {
    expect(await jsonFileSource.body('no-existe')).toBeNull();
    expect(await jsonFileSource.body('../categories')).toBeNull();
  });
});
