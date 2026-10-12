import { create } from 'zustand';
import { streamQuickAsk } from '../quick-ask-service';
import type { QuickAskTurn } from '../quick-ask-service';

export interface SideAskMessage {
  role: 'user' | 'assistant';
  content: string;
  /** Set when the stream failed; rendered as an error banner instead of markdown. */
  error?: string;
}

interface SideAskState {
  open: boolean;
  selectedQuote: string;
  messages: SideAskMessage[];
  isStreaming: boolean;
  modelConfigId: string | null;
  /** Mailbox signal consumed by MessageEditor to insert a quote chip. */
  pendingQuote: string | null;
  abortController: AbortController | null;
  openWithQuote: (text: string) => void;
  setModelConfigId: (id: string) => void;
  ask: (question: string) => Promise<void>;
  abort: () => void;
  abortAndClose: () => void;
  requestQuoteToEditor: (text: string) => void;
  clearPendingQuote: () => void;
}

export const useSideAskStore = create<SideAskState>()((set, get) => ({
  open: false,
  selectedQuote: '',
  messages: [],
  isStreaming: false,
  modelConfigId: null,
  pendingQuote: null,
  abortController: null,

  openWithQuote: (text) => {
    if (get().isStreaming) {
      get().abortController?.abort();
    }
    set({
      open: true,
      selectedQuote: text,
      messages: [],
      isStreaming: false,
      abortController: null,
    });
  },

  setModelConfigId: (id) => set({ modelConfigId: id }),

  requestQuoteToEditor: (text) => set({ pendingQuote: text }),

  clearPendingQuote: () => set({ pendingQuote: null }),

  abortAndClose: () => {
    get().abortController?.abort();
    set({
      open: false,
      isStreaming: false,
      abortController: null,
      messages: [],
      selectedQuote: '',
    });
  },

  abort: () => {
    get().abortController?.abort();
    set({ isStreaming: false, abortController: null });
  },

  ask: async (question) => {
    const state = get();
    if (state.isStreaming || !state.modelConfigId) return;
    const controller = new AbortController();
    const history: QuickAskTurn[] = state.messages
      .filter((m) => m.content !== '')
      .map((m) => ({
        role: m.role,
        content: m.content,
      }));
    set((s) => ({
      isStreaming: true,
      abortController: controller,
      messages: [
        ...s.messages,
        { role: 'user', content: question },
        { role: 'assistant', content: '' },
      ],
    }));

    const patchLastAssistant = (patch: (last: SideAskMessage) => SideAskMessage) =>
      set((s) => {
        const messages = [...s.messages];
        const last = messages[messages.length - 1];
        if (last && last.role === 'assistant') {
          messages[messages.length - 1] = patch(last);
        }
        return { messages };
      });

    const appendToLastAssistant = (text: string) =>
      patchLastAssistant((last) => ({ ...last, content: last.content + text }));

    const failLastAssistant = (errorMessage: string) =>
      patchLastAssistant((last) => ({ ...last, error: errorMessage }));

    try {
      await streamQuickAsk(
        {
          modelConfigId: state.modelConfigId,
          question,
          selectedText: get().selectedQuote,
          history,
        },
        controller.signal,
        (event) => {
          if (event.type === 'text_delta') {
            appendToLastAssistant(event.delta);
          } else if (event.type === 'error') {
            failLastAssistant(event.errorMessage ?? 'unknown error');
          }
        },
      );
    } catch (error) {
      if ((error as Error).name !== 'AbortError') {
        failLastAssistant((error as Error).message);
      }
    } finally {
      set((s) => ({
        isStreaming: false,
        abortController: null,
        messages: s.messages.filter((m) => m.role !== 'assistant' || !!m.content || !!m.error),
      }));
    }
  },
}));
