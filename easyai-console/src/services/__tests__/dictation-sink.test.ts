import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createContentEditableSink } from '@/hooks/dictation-sink';
import type { EditableDivRef } from '@/hooks/dictation-sink';

function createEditor() {
  const children: HTMLSpanElement[] = [];
  const editor = {
    appendChild: (el: HTMLSpanElement) => { children.push(el); },
    contains: (el: HTMLSpanElement) => children.includes(el),
    querySelectorAll: () => children,
  } as unknown as HTMLDivElement;
  const ref: EditableDivRef = { current: editor };
  const onChanged = vi.fn();
  return { children, ref, onChanged, sink: createContentEditableSink(ref, onChanged) };
}

describe('contenteditable dictation slots', () => {
  beforeEach(() => {
    vi.stubGlobal('document', {
      createElement: () => ({ dataset: {}, textContent: '', isConnected: true }),
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it('keeps independent corrections in the original slot order', () => {
    const { sink, children, onChanged } = createEditor();
    const first = sink.openSlot();
    const second = sink.openSlot();

    second.setText('第二句。', true);
    first.setText('第一句。', true);

    expect(sink.orderedCommits).toBe(false);
    expect(children.map((el) => el.textContent).join('')).toBe('第一句。第二句。');
    expect(onChanged).toHaveBeenCalledTimes(2);
  });

  it('does not restore cleared slots when corrections arrive out of order', () => {
    const { sink, children, onChanged } = createEditor();
    const first = sink.openSlot();
    const second = sink.openSlot();
    children.length = 0;

    second.setText('旧第二句。', true);
    first.setText('旧第一句。', true);

    expect(children).toHaveLength(0);
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('does not append a removed draft to new input', () => {
    const { sink, children, onChanged } = createEditor();
    const old = sink.openSlot();
    children.length = 0;
    const current = sink.openSlot();
    current.setText('新输入', false);
    onChanged.mockClear();

    old.setText('迟到的旧草稿', false);

    expect(children.map((el) => el.textContent).join('')).toBe('新输入');
    expect(onChanged).not.toHaveBeenCalled();
  });
});
