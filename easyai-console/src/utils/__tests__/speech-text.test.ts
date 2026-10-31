import { describe, expect, it } from 'vitest';
import { MAX_SPEECH_CHARS, stripMarkdownForSpeech } from '../speech-text';

describe('stripMarkdownForSpeech', () => {
  it('drops a fenced code block instead of reading the source', () => {
    const markdown = ['Narration first.', '```kotlin', 'val x = 1', '```', 'Closing line.'].join('\n');
    expect(stripMarkdownForSpeech(markdown)).toBe('Narration first.\nClosing line.');
  });

  it('drops tilde-fenced blocks and an unterminated fence to the end', () => {
    expect(stripMarkdownForSpeech('Intro\n~~~\nraw\n~~~\nOutro')).toBe('Intro\nOutro');
    expect(stripMarkdownForSpeech('Intro\n```\nraw\nstill raw')).toBe('Intro');
  });

  it('only closes a fence with a run of the same char that is at least as long', () => {
    // A three-backtick line inside a four-backtick block is content, not a close: treating it as a
    // close would reopen on the real closing fence and swallow the trailing prose.
    expect(stripMarkdownForSpeech('````\ncode\n```\n````\nAfter')).toBe('After');
    // A fence carrying an info string never closes a block.
    expect(stripMarkdownForSpeech('```\na\n```js\n```\nAfter')).toBe('After');
  });

  it('keeps backticks inside a tilde-fenced block from closing it', () => {
    expect(stripMarkdownForSpeech('~~~\n```not a close\n~~~\nOutro')).toBe('Outro');
  });

  it('drops images rather than speaking their alt text', () => {
    expect(stripMarkdownForSpeech('Before ![a red cube](cube.png) after')).toBe('Before after');
    expect(stripMarkdownForSpeech('![only an image](x.png)')).toBe('');
  });

  it('unwraps links to their text and keeps a bare autolink address', () => {
    expect(stripMarkdownForSpeech('See [the docs](https://example.com/x) now')).toBe('See the docs now');
    expect(stripMarkdownForSpeech('<https://example.com/x>')).toBe('https://example.com/x');
  });

  it('strips emphasis and inline-code markers while keeping the words', () => {
    expect(stripMarkdownForSpeech('a **bold** and `code` and ~~gone~~ word')).toBe('a bold and code and gone word');
    expect(stripMarkdownForSpeech('an _italic_ word')).toBe('an italic word');
    expect(stripMarkdownForSpeech('__strong__')).toBe('strong');
  });

  it('leaves multiplication asterisks and underscore identifiers alone', () => {
    expect(stripMarkdownForSpeech('2*3*4')).toBe('2*3*4');
    expect(stripMarkdownForSpeech('set MY_CONST_NAME first')).toBe('set MY_CONST_NAME first');
    expect(stripMarkdownForSpeech('snake_case_name')).toBe('snake_case_name');
  });

  it('unwraps single-character emphasis inside CJK prose', () => {
    // Full-width punctuation is the boundary: no space ever sits around the markers.
    expect(stripMarkdownForSpeech('这是*重点*，请注意')).toBe('这是重点，请注意');
    expect(stripMarkdownForSpeech('他说：*不要*。')).toBe('他说：不要。');
    expect(stripMarkdownForSpeech('（*轻声*）')).toBe('（轻声）');
  });

  it('removes heading, list and blockquote markers, including the closing hashes', () => {
    const markdown = ['## Title', '- first', '1. second', '> quoted', '- [x] done', '## Closed ##'].join('\n');
    expect(stripMarkdownForSpeech(markdown)).toBe('Title\nfirst\nsecond\nquoted\ndone\nClosed');
  });

  it('does not turn a prose pipe into a comma', () => {
    expect(stripMarkdownForSpeech('run `a|b` then x | y')).toBe('run a|b then x | y');
  });

  it('drops horizontal rules and reference definitions', () => {
    expect(stripMarkdownForSpeech('before\n---\n[1]: https://example.com\nafter')).toBe('before\nafter');
  });

  it('reads a table as a column legend plus one row per record', () => {
    const markdown = ['| Model | Voices |', '| --- | --- |', '| 3.1-flash | 139 |'].join('\n');
    expect(stripMarkdownForSpeech(markdown)).toBe('【表格，列：Model、Voices】\n3.1-flash，139');
  });

  it('keeps every record of a multi-row table and returns to prose afterwards', () => {
    const markdown = [
      'Intro',
      '| Model | Voices |',
      '| --- | --- |',
      '| 3.1-flash | 139 |',
      '| qwen3-tts | 62 |',
      'Outro',
    ].join('\n');
    expect(stripMarkdownForSpeech(markdown)).toBe(
      'Intro\n【表格，列：Model、Voices】\n3.1-flash，139\nqwen3-tts，62\nOutro'
    );
  });

  it('strips inline markup inside cells and never emits a pipe', () => {
    const markdown = [
      '| **Name** | [Docs](https://x.test) |',
      '| --- | --- |',
      '| 3.1-flash | ok |',
    ].join('\n');
    const spoken = stripMarkdownForSpeech(markdown);
    expect(spoken).toBe('【表格，列：Name、Docs】\n3.1-flash，ok');
    expect(spoken).not.toContain('|');
  });

  it('falls back to bare cells when the header is empty', () => {
    const markdown = ['|  |  |', '| --- | --- |', '| 3.1-flash | 139 |'].join('\n');
    expect(stripMarkdownForSpeech(markdown)).toBe('3.1-flash，139');
  });

  it('drops HTML tags but keeps the text between them', () => {
    expect(stripMarkdownForSpeech('a <div class="x">kept</div> b')).toBe('a kept b');
  });

  it('collapses runs of spaces and normalises CRLF', () => {
    expect(stripMarkdownForSpeech('a    b\r\n\r\nc')).toBe('a b\nc');
  });

  it('returns an empty string for a reply that is only markup no one should hear', () => {
    expect(stripMarkdownForSpeech('```\ncode only\n```')).toBe('');
    expect(stripMarkdownForSpeech('')).toBe('');
  });
});

describe('MAX_SPEECH_CHARS', () => {
  it('mirrors the backend read-aloud cap', () => {
    expect(MAX_SPEECH_CHARS).toBe(20000);
  });
});
