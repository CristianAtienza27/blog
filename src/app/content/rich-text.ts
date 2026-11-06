/**
 * Política del texto enriquecido de los artículos (TextBlock.html): una lista blanca de
 * etiquetas de texto y enlaces. La aplican la validación del contenido (al cargarlo o, en el
 * futuro, al guardarlo desde el panel) y la página al mostrarlo: una sola definición.
 */
export const ALLOWED_TAGS: ReadonlySet<string> = new Set([
  'p',
  'h3',
  'em',
  'strong',
  'a',
  'code',
  'ul',
  'ol',
  'li',
  'br',
]);

const TAG = /<\/?([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>/g;

/** Cualquier cosa entre < y >: se compara entera con las formas canónicas. */
const ANY_TAG = /<[^<>]*>/g;

/**
 * Formas canónicas, las únicas que se admiten y las que reconoce el render: etiquetas en
 * minúsculas y sin espacios, <br>, <br/> o <br />, y enlaces exactamente como
 * <a href="…">. Así validación y render aplican el mismo criterio.
 */
const CANONICAL = /^<(?:\/?(?:p|h3|em|strong|code|ul|ol|li|a)|br ?\/?)>$/;
const LINK = /^<a href="([^"]*)">$/;

// Destinos de un enlace: ruta interna, ancla a un capítulo (#capitulo) o https.
const INTERNAL = /^\/(?!\/)[^"<>\s\\]*$/;
const ANCHOR = /^#[a-z0-9-]+$/;
const EXTERNAL = /^https:\/\/[^"<>\s\\]+$/;

const ORIGIN = 'https://revista.invalid';

/**
 * Ruta interna segura: empieza por una sola «/», sin barras invertidas ni espacios (el
 * navegador trata «\» como «/» y elimina tabuladores y saltos de línea: «/\dominio» o
 * «/<tab>/dominio» acabarían en otro dominio), y el navegador la resuelve en el mismo origen.
 */
export function isInternalPath(href: string): boolean {
  if (!INTERNAL.test(href)) return false;
  try {
    return new URL(href, ORIGIN).origin === ORIGIN;
  } catch {
    return false;
  }
}

/** Destino admitido en un enlace del texto enriquecido. */
export function isAllowedHref(href: string): boolean {
  return isInternalPath(href) || ANCHOR.test(href) || EXTERNAL.test(href);
}

/** Incidencias de un HTML respecto a la lista blanca (vacía si es válido). */
export function htmlIssues(html: string): string[] {
  const issues: string[] = [];
  let inLink = false;
  for (const [raw] of html.matchAll(ANY_TAG)) {
    const parts = /^<\/?([a-zA-Z][a-zA-Z0-9-]*)(.*)>$/s.exec(raw);
    if (!parts) {
      issues.push('carácter < suelto');
      continue;
    }
    const name = parts[1].toLowerCase();
    const attributes = parts[2].replace(/\/\s*$/, '').trim();
    const link = LINK.exec(raw);
    if (!ALLOWED_TAGS.has(name)) {
      issues.push(`etiqueta no permitida: <${name}>`);
    } else if (link) {
      if (!isAllowedHref(link[1])) issues.push(`atributos no permitidos en <a>: href="${link[1]}"`);
      if (inLink) issues.push('enlace dentro de otro enlace');
      inLink = true;
    } else if (attributes && !raw.startsWith('</')) {
      issues.push(`atributos no permitidos en <${name}>: ${attributes}`);
    } else if (!CANONICAL.test(raw)) {
      issues.push(
        `formato no admitido: ${raw} (se espera <${raw.startsWith('</') ? '/' : ''}${name}>)`,
      );
    } else if (raw === '</a>') {
      inLink = false;
    }
  }
  if (html.replace(ANY_TAG, '').includes('<')) issues.push('carácter < suelto');
  return issues;
}

/** Texto sin etiquetas (scripts y estilos se descartan con su contenido). */
export function plainText(html: string): string {
  return html
    .replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(TAG, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
