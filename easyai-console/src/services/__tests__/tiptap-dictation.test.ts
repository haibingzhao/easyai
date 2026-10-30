import type { Editor } from '@tiptap/core';
import { Schema } from '@tiptap/pm/model';
import { EditorState, Plugin, PluginKey, Transaction } from '@tiptap/pm/state';
import { Decoration } from '@tiptap/pm/view';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTiptapDictationSink } from '@/components/knowledge/dictation/tiptapDictation';

/** A headless editor boundary, with real ProseMirror transactions, mapping and decorations. */
function setup() {
  const schema = new Schema({
    nodes: {
      doc: { content: 'paragraph+' },
      paragraph: { content: 'text*', group: 'block' },
      text: { group: 'inline' },
    },
  });
  let state = EditorState.create({
    schema,
    doc: schema.node('doc', null, [schema.node('paragraph')]),
  });
  const dispatch = (tr: Transaction) => { state = state.apply(tr); };
  // Only Tiptap's host/view boundary is stubbed; the sink and its plugin run unchanged.
  const editor = {
    get state() { return state; },
    isDestroyed: false,
    view: { dispatch },
    commands: {
      command: (command: (props: { tr: Transaction }) => boolean) => {
        const tr = state.tr;
        if (!command({ tr })) return false;
        dispatch(tr);
        return true;
      },
    },
    registerPlugin: (plugin: Plugin) => {
      state = state.reconfigure({ plugins: [...state.plugins, plugin] });
      return state;
    },
    unregisterPlugin: (key: PluginKey) => {
      state = state.reconfigure({ plugins: state.plugins.filter((plugin) => plugin.spec.key !== key) });
    },
  } as unknown as Editor;

  // No DOM mounting is needed: a widget's lazy renderer only creates a span and sets two fields.
  vi.stubGlobal('document', {
    createElement: vi.fn(() => ({ className: '', textContent: '' })),
  });
  const widget = vi.spyOn(Decoration, 'widget');
  const sink = createTiptapDictationSink(editor);
  sink.beginRound();

  const preview = () => {
    widget.mockClear();
    const plugin = state.plugins[0];
    const decorations = plugin.props.decorations?.call(plugin, state);
    if (!decorations) return null;
    const render = widget.mock.calls[0]?.[1];
    if (typeof render !== 'function') throw new Error('Expected a lazy dictation widget');
    return render(editor.view, () => state.selection.from).textContent;
  };

  return { editor, sink, preview };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('createTiptapDictationSink preview ownership', () => {
  it('does not replace the newer preview with an older slot’s late draft', () => {
    const { editor, sink, preview } = setup();
    expect(sink.orderedCommits).toBe(true);
    const first = sink.openSlot();
    const second = sink.openSlot();
    first.setText('第一句草稿', false);
    expect(preview()).toBe('第一句草稿');
    second.setText('第二句草稿', false);
    expect(preview()).toBe('第二句草稿');

    first.setText('第一句迟到的更新', false);
    expect(first.getText()).toBe('第一句迟到的更新');
    expect(preview()).toBe('第二句草稿');
    expect(editor.state.doc.textContent).toBe('');

    second.setText('第二句最新草稿', false);
    expect(preview()).toBe('第二句最新草稿');
    sink.dispose();
    expect(editor.state.plugins).toHaveLength(0);
  });

  it('commits the older sentence without clearing the newer preview, then inserts the next sentence in order', () => {
    const { editor, sink, preview } = setup();
    const first = sink.openSlot();
    const second = sink.openSlot();
    first.setText('第一句草稿', false);
    second.setText('第二句草稿', false);

    first.setText('第一句。', true);
    expect(editor.state.doc.textContent).toBe('第一句。');
    expect(preview()).toBe('第二句草稿');
    second.setText('第二句。', true);
    expect(editor.state.doc.textContent).toBe('第一句。第二句。');
    expect(sink.roundText([first, second])).toBe('第一句。第二句。');
    expect(preview()).toBeNull();
    sink.dispose();
  });

  it('only clears a preview when its owning slot is removed', () => {
    const { editor, sink, preview } = setup();
    const first = sink.openSlot();
    const second = sink.openSlot();
    first.setText('第一句草稿', false);
    second.setText('第二句草稿', false);
    first.remove();
    expect(first.getText()).toBe('');
    expect(preview()).toBe('第二句草稿');
    second.remove();
    expect(preview()).toBeNull();
    expect(editor.state.doc.textContent).toBe('');
    sink.dispose();
  });
});
