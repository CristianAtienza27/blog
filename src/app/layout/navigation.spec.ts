import { routes } from '../app.routes';
import { serverRoutes } from '../app.routes.server';
import { HOME_PAGE, SECONDARY_PAGES, SECTIONS, STATIC_PATHS } from './navigation';

describe('Navegación', () => {
  const routePaths = routes.map((route) => route.path);

  it('cada sección y página tiene su ruta', () => {
    for (const page of [HOME_PAGE, ...SECTIONS, ...SECONDARY_PAGES]) {
      expect(routePaths).toContain(page.path.slice(1));
    }
  });

  it('la sección técnica se llama Tecnología y vive en /tecnologia', () => {
    const tech = SECTIONS.find((section) => section.id === 'tecnologia');
    expect(tech?.label).toBe('Tecnología');
    expect(tech?.path).toBe('/tecnologia');
  });

  it('la numeración de secciones es correlativa', () => {
    expect(SECTIONS.map((section) => section.index)).toEqual([1, 2, 3, 4, 5]);
  });

  it('las páginas provisionales llevan noindex', () => {
    for (const path of ['', ...STATIC_PATHS]) {
      const route = routes.find((candidate) => candidate.path === path);
      expect(route?.data?.['seo']?.noindex).toBe(true);
    }
  });

  it('las páginas estáticas se prerenderizan', () => {
    const prerendered = serverRoutes.map((route) => route.path);
    for (const path of STATIC_PATHS) expect(prerendered).toContain(path);
  });
});
