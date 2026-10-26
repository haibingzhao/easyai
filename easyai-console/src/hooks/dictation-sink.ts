import { readMessageEditorText } from '@/utils/attachment-utils';

/** Chat-side context window handed to the dictation-refine model, per side. */
const CONTEXT_WINDOW_CHARS = 2_000;

/**
 * One dictated sentence.
 *
 * `final` marks the end-of-sentence correction pass: a rich-text editor commits the sentence into
 * the document on `final`, while the chat composer writes every update straight into its spans.
 */
export interface DictationSlotHandle {
  getText(): string;
  setText(text: string, final: boolean): void;
  remove(): void;
  isConnected(): boolean;
}

/**
 * Where dictated text lands. The voice hook owns capture/segmentation; the sink owns rendering, so
 * the same recorder can drive the chat composer's contenteditable and the article editor's Tiptap
 * document.
 */
export interface DictationSink {
  /** A new recording round starts: DOM sinks do nothing, editor sinks pin the insertion point. */
  beginRound(): void;
  openSlot(): DictationSlotHandle;
  contextAround(slots: DictationSlotHandle[]): { before: string; after: string };
  roundText(slots: DictationSlotHandle[]): string;
  replaceRound(slots: DictationSlotHandle[], text: string): void;
}

/** Structural ref type: works with both `useRef<T>(null)` typings. */
export interface EditableDivRef {
  readonly current: HTMLDivElement | null;
}

/**
 * Chat-composer sink: one `<span data-dict-slot>` per sentence appended to the editor end.
 * Behaviour is unchanged from the original in-hook implementation.
 */
export function createContentEditableSink(
  editorRef: EditableDivRef,
  onChanged: () => void,
): DictationSink {
  const elements = new WeakMap<DictationSlotHandle, HTMLSpanElement>();

  const makeSlot = (el: HTMLSpanElement): DictationSlotHandle => {
    const handle: DictationSlotHandle = {
      getText: () => el.textContent ?? '',
      setText: (text) => {
        // A late transcription can arrive after the composer HTML was cleared on send; keep the
        // original behaviour of re-attaching rather than silently dropping the text.
        if (!el.isConnected) editorRef.current?.appendChild(el);
        el.textContent = text;
        onChanged();
      },
      remove: () => el.remove(),
      isConnected: () => el.isConnected,
    };
    elements.set(handle, el);
    return handle;
  };

  return {
    beginRound: () => undefined,
    openSlot: () => {
      const editor = editorRef.current;
      const el = document.createElement('span');
      el.dataset.dictSlot = String(editor?.querySelectorAll('[data-dict-slot]').length ?? 0);
      editor?.appendChild(el);
      return makeSlot(el);
    },
    contextAround: (slots) => {
      const editor = editorRef.current;
      const first = elements.get(slots[0]);
      const last = elements.get(slots[slots.length - 1]);
      if (!editor || !first || !last) return { before: '', after: '' };
      const head = document.createElement('div');
      const tail = document.createElement('div');
      let walk: 'head' | 'skip' | 'tail' = 'head';
      for (const node of Array.from(editor.childNodes)) {
        if (node === first) {
          // A single-slot round ends at `first`; there is no middle to skip.
          walk = slots.length === 1 ? 'tail' : 'skip';
          continue;
        }
        if (node === last) { walk = 'tail'; continue; }
        if (walk === 'head') head.appendChild(node.cloneNode(true));
        else if (walk === 'tail') tail.appendChild(node.cloneNode(true));
      }
      return {
        before: readMessageEditorText(head).slice(-CONTEXT_WINDOW_CHARS),
        after: readMessageEditorText(tail).slice(0, CONTEXT_WINDOW_CHARS),
      };
    },
    roundText: (slots) => slots.map((slot) => slot.getText()).join(''),
    replaceRound: (slots, text) => {
      slots.forEach((slot, index) => slot.setText(index === 0 ? text : '', true));
    },
  };
}
