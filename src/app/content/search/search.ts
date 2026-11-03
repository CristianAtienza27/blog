/**
 * Búsqueda en memoria para el adaptador JSON. Con la API, la sustituirá la búsqueda
 * de texto completo de PostgreSQL; el contrato del repositorio no cambia.
 */

/** Palabras vacías que no deben exigirse en una búsqueda. */
const STOPWORDS = new Set(
  'a al con de del el en la las lo los o para por que se su un una unos unas y'.split(' '),
);

/** Minúsculas y sin tildes: «Fotografía» y «fotografia» son iguales. */
export function normalize(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}

export function tokenize(value: string): string[] {
  return normalize(value)
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
}

/** Términos de la consulta: sin palabras vacías ni letras sueltas. */
export function queryTerms(query: string): string[] {
  return [...new Set(tokenize(query))].filter(
    (term) => (term.length > 1 || /\d/.test(term)) && !STOPWORDS.has(term),
  );
}

export interface SearchField {
  text: string;
  /** Peso del campo: el título pesa más que el cuerpo. */
  weight: number;
}

export interface SearchDocument {
  id: string;
  fields: SearchField[];
}

interface IndexedDocument {
  id: string;
  fields: { tokens: string[]; weight: number }[];
}

export interface SearchHit {
  id: string;
  score: number;
}

export function buildIndex(documents: readonly SearchDocument[]): IndexedDocument[] {
  return documents.map((doc) => ({
    id: doc.id,
    fields: doc.fields.map((field) => ({ tokens: tokenize(field.text), weight: field.weight })),
  }));
}

/** Puntuación de un término: el mayor peso entre los campos que lo contienen. */
function termScore(doc: IndexedDocument, term: string): number {
  let best = 0;
  for (const field of doc.fields) {
    for (const token of field.tokens) {
      if (token === term) best = Math.max(best, field.weight * 1.5);
      else if (token.startsWith(term)) best = Math.max(best, field.weight);
    }
  }
  return best;
}

/**
 * Devuelve los documentos que contienen todos los términos (o palabras que empiezan
 * por ellos), ordenados por relevancia. Sin términos válidos no hay resultados.
 */
export function search(index: readonly IndexedDocument[], query: string): SearchHit[] {
  const terms = queryTerms(query);
  if (!terms.length) return [];
  const hits: SearchHit[] = [];
  for (const doc of index) {
    let score = 0;
    for (const term of terms) {
      const value = termScore(doc, term);
      if (!value) {
        score = 0;
        break;
      }
      score += value;
    }
    if (score > 0) hits.push({ id: doc.id, score });
  }
  return hits.sort((a, b) => b.score - a.score);
}
