/** Read-aloud product limit, mirroring `TtsService.MAX_CHARS` on the backend. */
export const MAX_SPEECH_CHARS = 20_000;

/**
 * Turn assistant Markdown into the plain text a speech engine should read.
 *
 * Rule of thumb: **read less rather than read wrong.** Code blocks, images and HTML are dropped
 * outright — narrating source code or `![alt]` markers is noise. Inline markup is unwrapped rather
 * than deleted, so the words survive.
 *
 * Tables are the one construct that is *translated* rather than flattened. A table row read as
 * "Model，Voices" loses which cell is which, and the director — the only consumer that could turn
 * that back into a spoken sentence — never sees the relationship. So a table block is emitted as a
 * column legend plus one comma-joined row per record:
 *
 * ```
 * 【表格，列：模型、音色数】
 * 3.1-flash，139
 * ```
 *
 * The legend costs one line and gives the planner (and the listener) the column order; the divider
 * row is dropped because it carries alignment only. Deliberately *not* the raw Markdown: the director
 * has to copy every `sourceText` slice verbatim, and a stray `|` would fail the coverage gate and
 * throw away the whole plan.
 *
 * Implemented as a line-oriented scanner with a fenced-block state machine plus a bounded set of
 * inline rules, instead of a full CommonMark parse: the console's `remark` stack is only available
 * transitively through `react-markdown`, and depending on it from a utility would couple the
 * speech path to the renderer's version. The trade-off is deliberate — this cleaner understands the
 * constructs chat answers actually use, and every rule has a case in `speech-text.test.ts`.
 *
 * The result may be empty (a reply that is only a code block or an image); callers must check.
 */
export function stripMarkdownForSpeech(markdown: string): string {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  const kept: string[] = [];
  // The open fence, char + length. The length matters: CommonMark closes a fence only with a run
  // of the same char that is *at least as long*, so a three-backtick line inside a four-backtick
  // block is content. Treating it as a close would reopen on the real closing fence and silently
  // swallow every line after the block.
  let fence: { char: string; length: number } | null = null;

  for (let index = 0; index < lines.length; index += 1) {
    const raw = lines[index];
    const fenceMatch = raw.match(FENCE);
    if (fence) {
      // Inside a fenced block: nothing is read, including the closing marker. A closing fence may
      // not carry an info string, so a fence line with a language tag stays content.
      if (
        fenceMatch &&
        fenceMatch[1][0] === fence.char &&
        fenceMatch[1].length >= fence.length &&
        raw.slice(fenceMatch[0].length).trim() === ''
      ) {
        fence = null;
      }
      continue;
    }
    if (fenceMatch) {
      fence = { char: fenceMatch[1][0], length: fenceMatch[1].length };
      continue;
    }

    // A table is a *block*: the header row is only a header because a divider follows it. Detecting
    // it here (rather than per line) is what keeps a prose line such as `a | b` untouched.
    if (TABLE_ROW.test(raw) && index + 1 < lines.length && TABLE_DIVIDER.test(lines[index + 1])) {
      const header = splitCells(raw);
      const rows: string[][] = [];
      let cursor = index + 2;
      // The block ends at the first line that is not itself a table row. A divider inside the body
      // is not a record, but it is still consumed rather than read aloud.
      while (cursor < lines.length && TABLE_ROW.test(lines[cursor])) {
        if (!TABLE_DIVIDER.test(lines[cursor])) rows.push(splitCells(lines[cursor]));
        cursor += 1;
      }
      const legend = header.filter((cell) => cell.length > 0);
      if (legend.length > 0) kept.push(`【表格，列：${legend.join('、')}】`);
      for (const row of rows) {
        const spoken = row.filter((cell) => cell.length > 0);
        if (spoken.length > 0) kept.push(spoken.join('，'));
      }
      // `-1` because the loop's own increment moves past this block.
      index = cursor - 1;
      continue;
    }

    if (HORIZONTAL_RULE.test(raw) || REFERENCE_DEFINITION.test(raw) || TABLE_DIVIDER.test(raw)) continue;

    // Blockquote markers first: `> ## Title` is both, and the heading has to be recognised after the
    // quote marker is gone. Trailing `#`s are the other half of the ATX syntax.
    let line = raw.replace(BLOCKQUOTE, '');
    if (HEADING.test(line)) line = line.replace(HEADING, '').replace(/\s+#+\s*$/, '');
    line = line.replace(LIST_ITEM, '');
    const cleaned = stripInline(line);
    if (cleaned) kept.push(cleaned);
  }

  return kept.join('\n');
}

const FENCE = /^\s{0,3}(`{3,}|~{3,})/;
const HEADING = /^\s{0,3}#{1,6}\s+/;
const BLOCKQUOTE = /^\s{0,3}(?:>\s?)+/;
const LIST_ITEM = /^\s{0,3}(?:[-*+]|\d{1,9}[.)])\s+(?:\[[ xX]\]\s+)?/;
const HORIZONTAL_RULE = /^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$/;
const TABLE_DIVIDER = /^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$/;
/** A row must be closed by a trailing pipe as well, so a leading-pipe-only line is not a table. */
const TABLE_ROW = /^\s*\|.*\|\s*$/;
const REFERENCE_DEFINITION = /^\s{0,3}\[[^\]]+]:\s+\S+/;

/**
 * Split one table row into its cells.
 *
 * A pipe inside inline code is split too: `\|` is the CommonMark escape and is handled, but keeping
 * the code span intact would mean a mini scanner whose only reward is one rare table.
 */
function splitCells(row: string): string[] {
  const inner = row.trim().replace(/^\|/, '').replace(/\|$/, '');
  return inner.split('|').map((cell) => stripInline(cell.trim()));
}

function stripInline(text: string): string {
  let out = text;
  // Images first: `![alt](src)` would otherwise be unwrapped into a spoken alt text.
  out = out.replace(/!\[[^\]]*\]\([^)]*\)/g, '');
  out = out.replace(/!\[[^\]]*\]\[[^\]]*\]/g, '');
  out = out.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
  out = out.replace(/\[([^\]]*)\]\[[^\]]*\]/g, '$1');
  // Bare links keep their address; the brackets are the only thing being removed.
  out = out.replace(/<((?:https?|mailto):[^>\s]+)>/g, '$1');
  out = out.replace(/<\/?[a-zA-Z][^>]*>/g, '');
  out = out.replace(/`+([^`]*)`+/g, '$1');
  out = out.replace(/~~([^~\n]+)~~/g, '$1');
  out = out.replace(/\*\*([^*\n]+)\*\*/g, '$1');
  out = out.replace(/__([^_\n]+)__/g, '$1');
  // Single-character emphasis only when it is not part of a word, so `2*3*4` and
  // `MY_CONST_NAME` survive. The opener excludes ASCII letters/digits instead of requiring
  // whitespace, because CJK prose puts no space around emphasis (`这是*重点*，请注意`).
  out = out.replace(
    /(^|[^A-Za-z0-9])\*([^*\n]+)\*(?=$|[\s)\]}.!?,;:"'，。！？；：、）】」』》”’…])/g,
    '$1$2'
  );
  out = out.replace(
    /(^|[^A-Za-z0-9])_([^_\n]+)_(?=$|[\s)\]}.!?,;:"'，。！？；：、）】」』》”’…])/g,
    '$1$2'
  );
  out = out.replace(/\\([\\`*_{}[\]()#+\-.!|>~])/g, '$1');
  return out.replace(/[ \t]+/g, ' ').trim();
}
