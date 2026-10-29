import type { ToolResult, ToolResultContentBlock } from '@/types/message';

/**
 * Above this many characters the result is not parsed at all. This is a sanity guard against a
 * pathological payload locking the main thread, not a rendering rule: real transcripts sit orders
 * of magnitude below it, and callers fall back to showing the raw text.
 */
const PATHOLOGICAL_PARSE_LIMIT = 5 * 1024 * 1024;

/**
 * Parse cache keyed by the result object.
 *
 * The object is stable for as long as the loaded transcript is: `mergeToolResults` moves results
 * onto the assistant message but never rewrites the result itself. A re-load builds new objects
 * and therefore parses once more, which is the correct granularity.
 */
const cache = new WeakMap<ToolResult, ToolResultContentBlock[] | null>();

/**
 * Structured blocks of a tool result, parsed on demand and cached.
 *
 * Returns undefined when there is nothing to parse (no result, an over-large payload) or when the
 * text is not a JSON array — callers then render `result.result` as text, which is exactly what
 * they did when no blocks were present.
 */
export function blocksOf(result: ToolResult): ToolResultContentBlock[] | undefined {
  if (result.contentBlocks && result.contentBlocks.length > 0) return result.contentBlocks;

  const cached = cache.get(result);
  if (cached !== undefined) return cached ?? undefined;

  const source = result.result;
  if (!source) {
    cache.set(result, null);
    return undefined;
  }
  if (source.length > PATHOLOGICAL_PARSE_LIMIT) {
    cache.set(result, null);
    return undefined;
  }

  let parsed: ToolResultContentBlock[] | null = null;
  const trimmed = source.trimStart();
  if (trimmed.startsWith('[')) {
    try {
      const value = JSON.parse(source);
      if (Array.isArray(value)) parsed = value as ToolResultContentBlock[];
    } catch {
      // Not JSON — the caller renders the raw text.
    }
  }
  cache.set(result, parsed);
  return parsed ?? undefined;
}
