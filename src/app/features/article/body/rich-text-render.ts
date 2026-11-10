import { htmlIssues, isInternalPath, plainText } from '../../../content/rich-text';

export interface RichTextOptions {
  /**
   * Ruta del artículo (/viajes/<slug>). Las anclas (#capitulo) se resuelven contra ella:
   * con <base href="/"> un «#capitulo» suelto llevaría a la portada.
   */
  basePath: string;
  /** 2 si el bloque no está dentro de un capítulo: sus subtítulos pasan a h2. */
  headingLevel?: 2 | 3;
  /** Capitular en el primer párrafo que no sea el aviso de demostración (en cursiva). */
  dropcap?: boolean;
}

/** Aviso para lectores de pantalla en los enlaces que abren otra pestaña. */
export const EXTERNAL_HINT = '<span class="visually-hidden"> (se abre en una pestaña nueva)</span>';

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };
const escape = (text: string) => text.replace(/[&<>"]/g, (char) => ESCAPES[char]);

/**
 * Prepara el HTML de un TextBlock para mostrarlo con la directiva RichHtml (rich-html.ts), que
 * lo vuelve a sanear con el saneador de Angular (nunca bypassSecurityTrustHtml).
 * - Fuera de la lista blanca: solo el texto, escapado (el contenido ya se valida al cargarlo;
 *   esto es una segunda barrera por si llega algo sin validar). La lista blanca solo admite las
 *   formas canónicas, así que las expresiones de abajo reconocen todo lo que pasa la validación.
 * - Enlaces https: pestaña nueva con rel="noopener noreferrer" y aviso accesible.
 * - Anclas: resueltas contra la ruta del artículo.
 */
export function renderRichText(html: string, options: RichTextOptions): string {
  if (htmlIssues(html).length) {
    const text = plainText(html);
    return text ? `<p>${escape(text)}</p>` : '';
  }
  let out = html.replace(
    /<a href="(https:\/\/[^"]+)">([\s\S]*?)<\/a>/g,
    (_, href: string, label: string) =>
      `<a href="${href}" rel="noopener noreferrer" target="_blank">${label}${EXTERNAL_HINT}</a>`,
  );
  out = out.replace(
    /<a href="#([a-z0-9-]+)">/g,
    (_, id: string) => `<a href="${options.basePath}#${id}">`,
  );
  if (options.headingLevel === 2) out = out.replace(/<(\/?)h3>/g, '<$1h2>');
  if (options.dropcap) out = out.replace(/<p>(?!\s*<em>)/, '<p class="has-dropcap">');
  return out;
}

/**
 * Destino de un clic en un enlace del texto: una ruta interna que debe resolver el router,
 * o null si el navegador debe seguirlo (externo, otra pestaña, teclas modificadoras).
 */
export function internalHref(event: MouseEvent): string | null {
  if (event.defaultPrevented || event.button !== 0) return null;
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return null;
  const target = event.target;
  const link = target instanceof Element ? target.closest('a') : null;
  if (!link || link.hasAttribute('target') || link.hasAttribute('download')) return null;
  // Mismo criterio que la validación: lo que el navegador resolvería fuera del sitio
  // (//dominio, /\dominio…) nunca se entrega al router.
  const href = link.getAttribute('href') ?? '';
  return isInternalPath(href) ? href : null;
}
