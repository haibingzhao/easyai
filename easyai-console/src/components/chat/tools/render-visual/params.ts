/**
 * Argument parsing for render_visual tool calls.
 *
 * During streaming `toolCall.args` holds raw partial JSON (toolcall_delta chunks);
 * once tool_execution_start arrives it is replaced by the complete formatted JSON.
 * The renderer treats "JSON.parse fails" as "still streaming".
 */

export interface RenderVisualArgs {
  title: string;
  code: string;
  loadingMessages: string[];
}

export interface PartialRenderVisualArgs {
  title?: string;
  loadingMessages: string[];
}

/** Matches the backend cap in RenderVisualTool.MAX_FRAGMENT_BYTES. */
export const MAX_FRAGMENT_BYTES = 2 * 1024 * 1024;

export function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

export function isSvgFragment(code: string): boolean {
  return code.trimStart().toLowerCase().startsWith('<svg');
}

export function parseArgs(args: string): RenderVisualArgs | null {
  if (!args) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(args);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const record = parsed as Record<string, unknown>;
  if (typeof record.title !== 'string' || typeof record.code !== 'string') return null;
  const loadingMessages = Array.isArray(record.loadingMessages)
    ? record.loadingMessages.filter((m): m is string => typeof m === 'string').slice(0, 4)
    : [];
  return { title: record.title, code: record.code, loadingMessages };
}

function unescapeJsonString(raw: string): string {
  return raw
    .replace(/\\u([0-9a-fA-F]{4})/g, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\');
}

/** Read the string value following `"key":` — tolerates an unterminated tail. */
function extractStringValue(args: string, key: string): string | null {
  const keyMatch = new RegExp(`"${key}"\\s*:\\s*"`).exec(args);
  if (!keyMatch) return null;
  const start = keyMatch.index + keyMatch[0].length;
  let out = '';
  for (let i = start; i < args.length; i++) {
    const ch = args[i];
    if (ch === '\\') {
      out += args[i] + (args[i + 1] ?? '');
      i++;
      continue;
    }
    if (ch === '"') return unescapeJsonString(out);
    out += ch;
  }
  return unescapeJsonString(out);
}

/** Best-effort metadata for the streaming skeleton (code intentionally skipped). */
export function extractPartialArgs(args: string): PartialRenderVisualArgs {
  const title = extractStringValue(args, 'title') ?? undefined;
  const loadingMessages: string[] = [];
  const arrayMatch = /"loadingMessages"\s*:\s*\[([\s\S]*)$/.exec(args);
  if (arrayMatch) {
    const stringRegex = /"((?:[^"\\]|\\.)*)"/g;
    let match: RegExpExecArray | null;
    while ((match = stringRegex.exec(arrayMatch[1])) !== null && loadingMessages.length < 4) {
      loadingMessages.push(unescapeJsonString(match[1]));
    }
  }
  return { title, loadingMessages };
}
