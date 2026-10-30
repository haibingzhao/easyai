import { describe, expect, it } from 'vitest';
import { SentenceMerger } from '../sentence-merge';
import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';

interface FakeSlot extends DictationSlotHandle {
  id: number;
  text: string;
  removed: boolean;
  writes: Array<{ text: string; final: boolean }>;
}

/** Records writes only: ordering, replacement and conflict logic all live in SentenceMerger. */
class FakeSink implements DictationSink {
  readonly orderedCommits = false;
  readonly slots: FakeSlot[] = [];

  beginRound() {}

  openSlot(): FakeSlot {
    const slot: FakeSlot = {
      id: this.slots.length,
      text: '',
      removed: false,
      writes: [],
      getText: () => slot.text,
      setText: (text, final) => {
        if (slot.removed) return;
        slot.text = text;
        slot.writes.push({ text, final });
      },
      remove: () => { slot.removed = true; },
      isConnected: () => !slot.removed,
    };
    this.slots.push(slot);
    return slot;
  }

  contextAround() { return { before: '', after: '' }; }
  roundText(slots: DictationSlotHandle[]) { return slots.map((slot) => slot.getText()).join(''); }
  replaceRound(slots: DictationSlotHandle[], text: string) {
    slots.forEach((slot, index) => slot.setText(index === 0 ? text : '', true));
  }
}

function setup() {
  const sink = new FakeSink();
  return { sink, merger: new SentenceMerger(sink) };
}

describe('SentenceMerger', () => {
  it('replaces a sentence in place instead of appending snapshots', () => {
    const { sink, merger } = setup();
    merger.apply(1, '帮我看', false);
    merger.apply(1, '帮我看一下', false);
    merger.apply(1, '帮我看一下英特尔', false);
    expect(sink.slots).toHaveLength(1);
    expect(sink.slots[0].text).toBe('帮我看一下英特尔');
  });

  it('keeps one slot per sentence and writes each final once', () => {
    const { sink, merger } = setup();
    merger.apply(1, '第一句', false);
    merger.apply(1, '第一句。', true);
    merger.apply(2, '第二句', false);
    expect(sink.slots.map((slot) => slot.text)).toEqual(['第一句。', '第二句']);
    expect(sink.slots[0].writes.filter((write) => write.final)).toHaveLength(1);
  });

  it('drops a draft and a second final after the sentence was finalised', () => {
    const { sink, merger } = setup();
    merger.apply(1, '最终。', true);
    expect(merger.apply(1, '最终后的草稿', false)).toBe('locked');
    expect(merger.apply(1, '互相矛盾的第二个最终', true)).toBe('locked');
    expect(sink.slots[0].text).toBe('最终。');
    expect(merger.conflictingFinals).toBe(1);
  });

  it('keeps an empty begin-of-sentence slot in order', () => {
    const { sink, merger } = setup();
    merger.apply(1, '', false);
    merger.apply(2, '第二句', false);
    expect(sink.slots).toHaveLength(2);
    expect(sink.slots[0].text).toBe('');
  });

  it('lets a manual edit win for the rest of the round', () => {
    const { sink, merger } = setup();
    merger.apply(1, '识别文本', false);
    sink.slots[0].text = '用户改过';
    expect(merger.apply(1, '新的识别结果', false)).toBe('user-edited');
    expect(merger.apply(1, '迟到的最终结果', true)).toBe('user-edited');
    expect(sink.slots[0].text).toBe('用户改过');
  });

  it('ignores non-positive or non-integer sentence ids', () => {
    const { sink, merger } = setup();
    expect(merger.apply(0, '心跳不该建槽位', false)).toBe('ignored');
    expect(merger.apply(1.5, '不是句子', false)).toBe('ignored');
    expect(sink.slots).toHaveLength(0);
  });

  it('closes unfinished sentences on stop and removes empty ones', () => {
    const { sink, merger } = setup();
    merger.apply(1, '已完成。', true);
    merger.apply(2, '未完成的部分', false);
    merger.apply(3, '  ', false);
    merger.finalizeRemaining();
    const lastWrite = (slot: FakeSlot) => slot.writes[slot.writes.length - 1];
    expect(lastWrite(sink.slots[0])).toEqual({ text: '已完成。', final: true });
    expect(sink.slots[1].text).toBe('未完成的部分');
    expect(lastWrite(sink.slots[1]).final).toBe(true);
    expect(sink.slots[2].removed).toBe(true);
    expect(merger.isPartial).toBe(false);
  });

  it('reports a sentence-id gap instead of silently flattening it', () => {
    const { merger } = setup();
    merger.apply(1, '一', false);
    merger.apply(3, '三', false);
    expect(merger.isPartial).toBe(true);
    // Slots keep arrival order; the round is reported as partial rather than reordered.
    expect(merger.slots()).toHaveLength(2);
  });

  it('discards unfinished slots without closing them', () => {
    const { sink, merger } = setup();
    merger.apply(1, '已完成。', true);
    merger.apply(2, '被取消的句子', false);
    merger.discardRemaining();
    expect(sink.slots[1].removed).toBe(true);
    expect(merger.slots()).toHaveLength(1);
    expect(merger.text()).toBe('已完成。');
  });
});
