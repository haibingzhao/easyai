import { createHighlighter, type Highlighter } from 'shiki';

let highlighter: Highlighter | null = null;
let highlighterPromise: Promise<Highlighter> | null = null;

// Module-level highlight cache: avoids re-highlighting identical code on remount
const highlightCache = new Map<string, string>();
const MAX_CACHE_SIZE = 100;

/**
 * Get the shared Shiki highlighter singleton.
 * Lazily initializes on first call and caches the instance.
 */
export const getShikiHighlighter = async (): Promise<Highlighter> => {
  if (highlighter) return highlighter;

  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['github-dark'],
      langs: [
        'typescript', 'javascript', 'python', 'java', 'kotlin', 'bash',
        'json', 'yaml', 'markdown', 'html', 'css', 'sql', 'xml', 'toml',
        'go', 'rust', 'shell', 'jinja', 'groovy',
      ],
    }).then((h) => {
      highlighter = h;
      return h;
    });
  }

  return highlighterPromise;
};

/**
 * Get cached highlighted HTML for the given code+language.
 * Returns undefined if not cached.
 */
export function getCachedHighlight(code: string, language: string): string | undefined {
  return highlightCache.get(`${language}:${code}`);
}

/**
 * Store highlighted HTML in cache with LRU-like eviction.
 */
export function setCachedHighlight(code: string, language: string, html: string): void {
  const key = `${language}:${code}`;
  if (highlightCache.size >= MAX_CACHE_SIZE) {
    const firstKey = highlightCache.keys().next().value;
    if (firstKey) highlightCache.delete(firstKey);
  }
  highlightCache.set(key, html);
}

/**
 * Shiki transformer that strips outer <pre><code> tags.
 * Useful when embedding highlighted code in custom containers.
 */
export const stripPreCodeTransformer = {
  name: 'strip-pre-code',
  postprocess(html: string) {
    return html
      .replace(/^<pre[^>]*>/, '')
      .replace(/<\/pre>\s*$/, '')
      .replace(/^<code[^>]*>/, '')
      .replace(/<\/code>\s*$/, '');
  },
};
