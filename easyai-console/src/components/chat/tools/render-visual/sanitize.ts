/**
 * Whitelist sanitizer for render_visual fragments (defense in depth on top of
 * the sandbox CSP). Blacklists never cover everything, so unknown tags and
 * attributes are dropped rather than filtered by pattern.
 */

const SVG_TAGS = new Set([
  'svg', 'g', 'defs', 'marker', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline',
  'polygon', 'text', 'tspan', 'textpath', 'title', 'desc', 'use', 'symbol', 'clippath',
  'mask', 'pattern', 'lineargradient', 'radialgradient', 'stop', 'foreignobject', 'image',
  'filter', 'fetile', 'feblend', 'feflood', 'fegaussianblur', 'feoffset', 'femergen',
  'femergenode', 'fecomposite', 'fecolormatrix', 'feimage', 'feturbulence', 'fedisplacementmap',
  'fedropshadow', 'femorphology', 'feconvolvematrix', 'anchor', 'switch', 'metadata',
]);

const HTML_TAGS = new Set([
  'div', 'span', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'dl', 'dt', 'dd',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'caption', 'colgroup', 'col',
  'section', 'article', 'header', 'footer', 'nav', 'aside', 'main', 'figure', 'figcaption',
  'blockquote', 'pre', 'code', 'em', 'strong', 'b', 'i', 'u', 's', 'small', 'sub', 'sup',
  'hr', 'br', 'a', 'img', 'video', 'audio', 'source', 'canvas', 'details', 'summary',
  'label', 'button', 'input', 'select', 'option', 'textarea', 'progress', 'meter',
  'style', 'script',
]);

const URL_ATTRIBUTES = new Set(['href', 'src', 'xlink:href', 'poster', 'data']);
const DROPPED_ATTRIBUTES = new Set(['formaction', 'autofocus', 'target', 'ping']);

function isAllowedUrl(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.startsWith('#') || trimmed.startsWith('/') || trimmed.startsWith('./')) return true;
  return /^(https:|data:|blob:|mailto:)/i.test(trimmed);
}

function sanitizeElement(element: Element): void {
  for (const attr of Array.from(element.attributes)) {
    const name = attr.name.toLowerCase();
    if (name.startsWith('on') || DROPPED_ATTRIBUTES.has(name)) {
      element.removeAttribute(attr.name);
      continue;
    }
    if (URL_ATTRIBUTES.has(name) && !isAllowedUrl(attr.value)) {
      element.removeAttribute(attr.name);
      continue;
    }
    if (name === 'type' && element.tagName.toLowerCase() === 'input' && attr.value.toLowerCase() === 'password') {
      element.remove();
      return;
    }
  }
  for (const child of Array.from(element.children)) {
    sanitizeElement(child);
  }
}

/**
 * Parse, strip the document shell (models sometimes emit a full HTML document),
 * drop disallowed tags/attributes and return the sanitized fragment string.
 */
export function sanitizeFragment(code: string): string {
  const withoutXmlDecl = code.replace(/^\s*<\?xml[^>]*\?>/, '');
  const parser = new DOMParser();
  const doc = parser.parseFromString(withoutXmlDecl, 'text/html');
  const body = doc.body;

  for (const child of Array.from(body.children)) {
    sanitizeElement(child);
  }

  // Drop disallowed elements bottom-up so their children are re-parented first.
  const drop = (allowed: Set<string>) => {
    for (const element of Array.from(body.querySelectorAll('*'))) {
      const tag = element.tagName.toLowerCase();
      if (!allowed.has(tag)) {
        // Keep children of structural wrappers, drop everything else entirely.
        if (tag === 'html' || tag === 'head' || tag === 'iframe' || tag === 'object' ||
            tag === 'embed' || tag === 'form' || tag === 'link' || tag === 'meta' ||
            tag === 'base') {
          element.remove();
        } else {
          element.replaceWith(...Array.from(element.childNodes));
        }
      }
    }
  };
  drop(new Set([...SVG_TAGS, ...HTML_TAGS]));

  return body.innerHTML;
}
