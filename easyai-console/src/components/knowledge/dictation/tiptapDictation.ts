import { useEffect, useMemo, useRef } from 'react';
import type { Editor } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';

const CONTEXT_WINDOW_CHARS = 2_000;

interface RoundState {
  /** Insertion point of the current round, mapped through every transaction. */
  anchor: number;
  /** Committed round range, maintained for polish; null before the first sentence lands. */
  roundFrom: number | null;
  roundTo: number | null;
  /** In-progress sentence, rendered as a widget so typing does not pollute undo history. */
  liveText: string;
  /** Slot that owns [liveText]; an older sentence's final pass must not clear a newer preview. */
  liveSlotId: number | null;
}

export interface TiptapDictationHandle extends DictationSink {
  dispose(): void;
}

/**
 * Article-editor sink.
 *
 * Finalized sentences are inserted into the Tiptap document as plain text (one undo step per
 * sentence); the still-open sentence is only a decoration, so draft re-transcriptions never touch
 * the document. The insertion anchor is mapped over transactions, so editing elsewhere while
 * dictating does not move where dictation continues.
 */
export function createTiptapDictationSink(editor: Editor): TiptapDictationHandle {
  const state: RoundState = { anchor: 1, roundFrom: null, roundTo: null, liveText: '', liveSlotId: null };
  const pluginKey = new PluginKey<number>('knowledgeDictation');
  let nextSlotId = 1;

  const clamp = (pos: number) => Math.max(1, Math.min(pos, editor.state.doc.content.size));

  const refresh = () => {
    // A late transcription can land after the page unmounted mid-recording.
    if (editor.isDestroyed) return;
    editor.view.dispatch(editor.state.tr.setMeta(pluginKey, true));
  };

  const plugin = new Plugin<number>({
    key: pluginKey,
    state: {
      init: () => 0,
      apply: (tr, version) => {
        if (tr.docChanged) {
          state.anchor = tr.mapping.map(state.anchor);
          if (state.roundFrom !== null) state.roundFrom = tr.mapping.map(state.roundFrom, -1);
          if (state.roundTo !== null) state.roundTo = tr.mapping.map(state.roundTo, 1);
        }
        return tr.getMeta(pluginKey) ? version + 1 : version;
      },
    },
    props: {
      decorations: (current) => {
        if (!state.liveText) return null;
        const pos = clamp(state.anchor);
        return DecorationSet.create(current.doc, [
          Decoration.widget(
            pos,
            () => {
              const span = document.createElement('span');
              span.className = 'italic text-muted-foreground/60';
              span.textContent = state.liveText;
              return span;
            },
            { side: 1 },
          ),
        ]);
      },
    },
  });

  editor.registerPlugin(plugin);

  const insertSentence = (text: string) => {
    if (!text) return;
    const startsRound = state.roundFrom === null;
    const pos = clamp(state.anchor);
    editor.commands.command(({ tr }) => {
      tr.insertText(text, pos);
      return true;
    });
    if (startsRound) {
      // Later sentences land exactly at roundTo, which the plugin mapping already advances.
      state.roundFrom = pos;
      state.roundTo = pos + text.length;
    }
  };

  const roundRange = () => {
    const size = editor.state.doc.content.size;
    if (state.roundFrom === null || state.roundTo === null) return null;
    const from = Math.max(0, Math.min(state.roundFrom, size));
    const to = Math.max(from, Math.min(state.roundTo, size));
    return { from, to };
  };

  const makeSlot = (): DictationSlotHandle => {
    const id = nextSlotId++;
    let text = '';
    const clearPreviewIfOwned = () => {
      if (state.liveSlotId !== id) return;
      state.liveText = '';
      state.liveSlotId = null;
    };
    return {
      getText: () => text,
      setText: (next, final) => {
        if (editor.isDestroyed) return;
        const clean = next.replace(/\s+/g, ' ');
        text = clean;
        if (final) {
          insertSentence(clean);
          clearPreviewIfOwned();
        } else {
          state.liveText = clean;
          state.liveSlotId = id;
        }
        refresh();
      },
      remove: () => {
        text = '';
        if (state.liveSlotId === id) {
          clearPreviewIfOwned();
          refresh();
        }
      },
      isConnected: () => state.roundFrom !== null,
    };
  };

  return {
    dispose: () => {
      try {
        editor.unregisterPlugin(pluginKey);
      } catch {
        // Editor already destroyed; nothing left to unregister.
      }
    },
    beginRound: () => {
      state.anchor = clamp(editor.state.selection.from);
      state.roundFrom = null;
      state.roundTo = null;
      state.liveText = '';
      state.liveSlotId = null;
      refresh();
    },
    openSlot: makeSlot,
    contextAround: () => {
      const range = roundRange();
      if (!range) return { before: '', after: '' };
      const doc = editor.state.doc;
      return {
        before: doc.textBetween(0, range.from, ' ').slice(-CONTEXT_WINDOW_CHARS),
        after: doc.textBetween(range.to, doc.content.size, ' ').slice(0, CONTEXT_WINDOW_CHARS),
      };
    },
    roundText: () => {
      const range = roundRange();
      return range ? editor.state.doc.textBetween(range.from, range.to, ' ') : '';
    },
    replaceRound: (_slots, text) => {
      const range = roundRange();
      if (!range) return;
      const clean = text.replace(/\s+/g, ' ');
      editor.commands.command(({ tr }) => {
        if (clean) {
          tr.replaceWith(range.from, range.to, tr.doc.type.schema.text(clean));
        } else {
          tr.delete(range.from, range.to);
        }
        return true;
      });
    },
  };
}

const NOOP_SLOT: DictationSlotHandle = {
  getText: () => '',
  setText: () => undefined,
  remove: () => undefined,
  isConnected: () => false,
};

/**
 * Stable sink proxy for the article editor. The real sink is created after the Tiptap editor
 * mounts, so the voice hook can keep a single sink identity across renders.
 */
export function useTiptapDictationSink(editor: Editor | null): DictationSink {
  const handleRef = useRef<TiptapDictationHandle | null>(null);

  useEffect(() => {
    if (!editor) return;
    const handle = createTiptapDictationSink(editor);
    handleRef.current = handle;
    return () => {
      handle.dispose();
      if (handleRef.current === handle) handleRef.current = null;
    };
  }, [editor]);

  return useMemo<DictationSink>(() => ({
    beginRound: () => handleRef.current?.beginRound(),
    openSlot: () => handleRef.current?.openSlot() ?? NOOP_SLOT,
    contextAround: (slots) => handleRef.current?.contextAround(slots) ?? { before: '', after: '' },
    roundText: (slots) => handleRef.current?.roundText(slots) ?? '',
    replaceRound: (slots, text) => handleRef.current?.replaceRound(slots, text),
  }), []);
}
