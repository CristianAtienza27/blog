import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { RETIRED_MEDIA } from '../../editorial/retired-media';
import { SECTIONS } from '../../layout/navigation';
import { jsonFileSource } from '../json/content-source';
import { htmlIssues, isAllowedHref } from '../rich-text';
import { validateContent } from './integrity';
import { countWords, readingMinutes } from './reading-time';
import { COLLECTION_NAMES, type ContentBlockRecord, type ContentCollections } from './records';
import { isRecord } from './validate';

/**
 * Paridad con el validador Java del backend (fase 6.4.1): el corpus compartido de
 * api/src/test/resources/contract-cases da exactamente las mismas incidencias aquí y en
 * ArticleValidatorParityTests. Las divergencias documentadas (Java más estricto) llevan
 * «esperadoTs». Para añadir casos, sin «esperado»:
 *
 *   CONTRATO_VOLCAR=<archivo.json> npx ng test --watch=false --include=src/app/content/records/contract-parity.spec.ts
 *
 * vuelca lo que da TypeScript para revisarlo antes de fijarlo en el corpus.
 */
const CASES = resolve('api/src/test/resources/contract-cases');

const read = <T>(name: string): T => JSON.parse(readFileSync(join(CASES, name), 'utf8')) as T;

interface ArticleCase {
  nombre: string;
  base: string;
  quitar?: string[];
  poner?: Record<string, unknown>;
  bloques?: unknown[];
  cuerpo?: unknown;
  esperado?: string[];
  esperadoTs?: string[];
  divergencia?: string;
}
interface HtmlCase {
  nombre: string;
  html: string;
  esperado?: string[];
}
interface LinkCase {
  href: string;
  esperado?: boolean;
}
interface ReadingCase {
  nombre: string;
  bloques?: unknown[];
  palabras?: number;
  esperado?: [number, number];
}

const articleCases = read<{ casos: ArticleCase[] }>('articulos.json').casos;
const richText = read<{ html: HtmlCase[]; enlaces: LinkCase[] }>('texto-enriquecido.json');
const readingCases = read<{ casos: ReadingCase[] }>('tiempo-de-lectura.json').casos;

interface CatalogCase {
  nombre: string;
  coleccion: 'tags' | 'authors' | 'destinations' | 'trips' | 'projects';
  base: string;
  quitar?: string[];
  poner?: Record<string, unknown>;
  esperado?: string[];
  esperadoTs?: string[];
  divergencia?: string;
}
const catalogCases = read<{ casos: CatalogCase[] }>('catalogo.json').casos;

const OPTIONS = {
  externalMedia: SECTIONS.map((section) => section.preview),
  retiredMedia: RETIRED_MEDIA,
};

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

/** «articles[i]» o «articles/<slug>.json» seguidos de «.», «[» o «:». */
function withPrefix(issue: string, prefix: string): boolean {
  return issue.startsWith(prefix) && ['.', '[', ':'].includes(issue.charAt(prefix.length));
}

/** Construye el caso sobre el contenido real y devuelve las incidencias del artículo, normalizadas. */
function runArticle(
  c: ArticleCase,
  data: ContentCollections,
  bodies: ReadonlyMap<string, unknown>,
): string[] {
  const articles: Record<string, unknown>[] = data.articles.map((a) => ({ ...a }));
  const i = articles.findIndex((a) => a['id'] === c.base);
  if (i < 0) throw new Error(`${c.nombre}: no existe el artículo base «${c.base}»`);
  const original = articles[i];
  const article: Record<string, unknown> = { ...original };
  for (const key of c.quitar ?? []) delete article[key];
  // defineProperty: una clave como «__proto__» se crea como propia (como JSON.parse), no cambia el prototipo.
  for (const key of Object.keys(c.poner ?? {})) {
    Object.defineProperty(article, key, {
      value: c.poner![key],
      enumerable: true,
      writable: true,
      configurable: true,
    });
  }
  const baseBody = bodies.get(original['slug'] as string) as { blocks: unknown[] };
  const body =
    c.cuerpo !== undefined
      ? c.cuerpo
      : {
          ...(article['slug'] !== undefined ? { slug: article['slug'] } : {}),
          blocks: c.bloques ?? baseBody.blocks,
        };
  const fixedMinutes =
    Object.keys(c.poner ?? {}).includes('readingMinutes') ||
    (c.quitar ?? []).includes('readingMinutes');
  const blocks = isRecord(body) ? body['blocks'] : undefined;
  if (!fixedMinutes && Array.isArray(blocks)) {
    try {
      article['readingMinutes'] = readingMinutes(blocks as ContentBlockRecord[]);
    } catch {
      // bloques sin la forma del contrato: se deja el valor del artículo base
    }
  }
  articles[i] = article;
  const caseBodies = new Map(bodies);
  caseBodies.delete(original['slug'] as string);
  caseBodies.set(article['slug'] as string, body);

  let issues: string[];
  try {
    issues = validateContent(
      { ...data, articles } as unknown as Record<keyof ContentCollections, unknown>,
      caseBodies,
      OPTIONS,
    );
  } catch (error) {
    return [`excepción ${(error as Error).name}`];
  }
  const articlePrefix = `articles[${i}]`;
  const bodyPrefix = `articles/${String(article['slug'])}.json`;
  return issues.flatMap((issue) => {
    if (withPrefix(issue, articlePrefix)) return ['articulo' + issue.slice(articlePrefix.length)];
    if (withPrefix(issue, bodyPrefix)) return ['cuerpo' + issue.slice(bodyPrefix.length)];
    return [];
  });
}

/** Un registro del catálogo cambiado sobre el contenido real (6.4.7): sus incidencias, normalizadas. */
function runCatalog(
  c: CatalogCase,
  data: ContentCollections,
  bodies: ReadonlyMap<string, unknown>,
): string[] {
  const items: Record<string, unknown>[] = (
    data[c.coleccion] as unknown as Record<string, unknown>[]
  ).map((r) => ({ ...r }));
  const i = items.findIndex((r) => r['id'] === c.base);
  if (i < 0) throw new Error(`${c.nombre}: no existe «${c.base}» en ${c.coleccion}`);
  const record: Record<string, unknown> = { ...items[i] };
  for (const key of c.quitar ?? []) delete record[key];
  for (const key of Object.keys(c.poner ?? {})) {
    Object.defineProperty(record, key, {
      value: c.poner![key],
      enumerable: true,
      writable: true,
      configurable: true,
    });
  }
  items[i] = record;
  let issues: string[];
  try {
    issues = validateContent(
      { ...data, [c.coleccion]: items } as unknown as Record<keyof ContentCollections, unknown>,
      bodies,
      OPTIONS,
    );
  } catch (error) {
    return [`excepción ${(error as Error).name}`];
  }
  const prefix = `${c.coleccion}[${i}]`;
  return issues.flatMap((issue) =>
    withPrefix(issue, prefix) ? ['registro' + issue.slice(prefix.length)] : [],
  );
}

function readingBlocks(c: ReadingCase): ContentBlockRecord[] {
  if (c.bloques) return c.bloques as ContentBlockRecord[];
  const words = Array.from({ length: c.palabras ?? 0 }, () => 'palabra').join(' ');
  return [{ type: 'text', html: `<p>${words}</p>` }];
}

describe('Paridad del contrato con el validador Java (corpus compartido)', () => {
  it('los 12 artículos reales no tienen incidencias', async () => {
    const { data, bodies } = await loadContent();
    expect(data.articles.length).toBe(12);
    for (const article of data.articles) {
      expect(
        runArticle({ nombre: article.id, base: article.id }, data, bodies),
        article.id,
      ).toEqual([]);
    }
  });

  it('artículos: las incidencias esperadas, caso a caso', async () => {
    const { data, bodies } = await loadContent();
    for (const c of articleCases) {
      const expected = c.esperadoTs ?? c.esperado;
      expect(expected, `${c.nombre}: falta «esperado»`).toBeDefined();
      expect(runArticle(c, data, bodies), c.nombre).toEqual(expected!);
    }
  });

  it('las divergencias están documentadas y difieren de verdad', () => {
    for (const c of articleCases.filter((x) => x.esperadoTs !== undefined)) {
      expect(c.divergencia, c.nombre).toBeTruthy();
      expect(c.esperadoTs, c.nombre).not.toEqual(c.esperado!);
    }
  });

  it('texto enriquecido: las incidencias exactas de htmlIssues', () => {
    for (const c of richText.html) {
      expect(c.esperado, `${c.nombre}: falta «esperado»`).toBeDefined();
      expect(htmlIssues(c.html), c.nombre).toEqual(c.esperado!);
    }
  });

  it('enlaces: isAllowedHref', () => {
    for (const c of richText.enlaces) {
      expect(c.esperado, `${JSON.stringify(c.href)}: falta «esperado»`).toBeDefined();
      expect(isAllowedHref(c.href), JSON.stringify(c.href)).toBe(c.esperado!);
    }
  });

  it('tiempo de lectura: palabras y minutos', () => {
    for (const c of readingCases) {
      const blocks = readingBlocks(c);
      expect(c.esperado, `${c.nombre}: falta «esperado»`).toBeDefined();
      expect([countWords(blocks), readingMinutes(blocks)], c.nombre).toEqual(c.esperado!);
    }
  });

  it('catálogo: las incidencias esperadas de cada registro (6.4.7)', async () => {
    const { data, bodies } = await loadContent();
    for (const c of catalogCases) {
      const expected = c.esperadoTs ?? c.esperado;
      expect(expected, `${c.nombre}: falta «esperado»`).toBeDefined();
      expect(runCatalog(c, data, bodies), c.nombre).toEqual(expected!);
    }
  });

  const dump = process.env['CONTRATO_VOLCAR'];
  if (dump) {
    it('vuelca los resultados de TypeScript (solo con CONTRATO_VOLCAR)', async () => {
      const { data, bodies } = await loadContent();
      writeFileSync(
        dump,
        JSON.stringify(
          {
            articulos: articleCases.map((c) => ({
              nombre: c.nombre,
              ts: runArticle(c, data, bodies),
            })),
            html: richText.html.map((c) => ({ nombre: c.nombre, ts: htmlIssues(c.html) })),
            enlaces: richText.enlaces.map((c) => ({ href: c.href, ts: isAllowedHref(c.href) })),
            catalogo: catalogCases.map((c) => ({
              nombre: c.nombre,
              ts: runCatalog(c, data, bodies),
            })),
            tiempo: readingCases.map((c) => {
              const blocks = readingBlocks(c);
              return { nombre: c.nombre, ts: [countWords(blocks), readingMinutes(blocks)] };
            }),
          },
          null,
          2,
        ),
      );
    });
  }
});
