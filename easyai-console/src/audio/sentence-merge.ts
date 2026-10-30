import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';

/**
 * Sentence-snapshot merging for the streaming channel.
 *
 * The service re-sends the *whole* current sentence on every update and marks the last one `final`,
 * so this module never concatenates text: one `sentenceId` owns exactly one slot, and each update
 * replaces that slot's content. Two consequences the editor depends on:
 *  - a late snapshot for sentence 1 cannot reorder or duplicate sentence 2;
 *  - once a sentence is final, nothing from that sentence may touch it again, and the user's own
 *    edits win over every later snapshot (sticky for the rest of the round).
 *
 * The HTTP channel keeps its own draft-fragment logic; this class is only used when the server
 * reports `transport=WEBSOCKET`.
 */

export type SentenceApplyResult =
  /** Snapshot written into its slot. */
  | 'applied'
  /** The sentence was already final; the snapshot was dropped. */
  | 'locked'
  /** The user edited the slot; the snapshot was dropped and the edit stays. */
  | 'user-edited'
  /** Not a usable sentence id — ignored, never rendered. */
  | 'ignored';

interface Entry {
  sentenceId: number;
  handle: DictationSlotHandle;
  text: string;
  /** Text this module last wrote, used to detect a manual edit before the next snapshot. */
  lastWritten: string;
  final: boolean;
  userEdited: boolean;
}

export class SentenceMerger {
  private readonly entries = new Map<number, Entry>();
  private highest = 0;
  private gap = false;
  private conflicts = 0;

  constructor(private readonly sink: DictationSink) {}

  /**
   * How many self-contradicting `final` snapshots arrived. Kept for diagnostics only: the protocol
   * says a second final for the same sentence is an anomaly, and the safe reaction is to ignore it,
   * which `locked` already does.
   */
  get conflictingFinals(): number {
    return this.conflicts;
  }

  apply(sentenceId: number, text: string, isFinal: boolean): SentenceApplyResult {
    if (!Number.isInteger(sentenceId) || sentenceId <= 0) return 'ignored';
    let entry = this.entries.get(sentenceId);
    if (!entry) {
      // Out-of-order arrival is recorded but not "repaired": slots keep spoken/arrival order and the
      // round is reported as partial instead of silently flattening the gap.
      if (this.highest > 0 && sentenceId > this.highest + 1) this.gap = true;
      entry = {
        sentenceId,
        handle: this.sink.openSlot(),
        text: '',
        lastWritten: '',
        final: false,
        userEdited: false,
      };
      this.entries.set(sentenceId, entry);
      this.highest = Math.max(this.highest, sentenceId);
    }

    if (entry.final) {
      if (isFinal && text !== entry.text) this.conflicts += 1;
      return 'locked';
    }

    const visible = entry.handle.getText();
    if (visible !== entry.lastWritten) entry.userEdited = true;
    entry.text = text;
    if (entry.userEdited) {
      // The slot now belongs to the user; only the bookkeeping advances.
      if (isFinal) entry.final = true;
      return 'user-edited';
    }

    // Some sinks refuse the write (editor cleared, node detached). Read back instead of assuming the
    // text landed, so the next snapshot knows the real situation.
    entry.handle.setText(text, isFinal);
    entry.lastWritten = entry.handle.getText();
    if (isFinal) entry.final = true;
    return 'applied';
  }

  /**
   * End-of-round sweep for sentences the service never finalised (stop timeout, error, cancel).
   * Non-empty slots keep their text and are closed as final so the editor commits them; empty slots
   * are removed, matching what the HTTP channel does for a silent round.
   */
  finalizeRemaining(): void {
    for (const entry of [...this.entries.values()]) {
      if (entry.final) continue;
      entry.final = true;
      if (entry.userEdited) continue;
      if (entry.handle.getText().trim() === '') {
        entry.handle.remove();
        this.entries.delete(entry.sentenceId);
        continue;
      }
      entry.handle.setText(entry.text, true);
      entry.lastWritten = entry.handle.getText();
    }
  }

  /** Drop non-final slots without closing them — used when the round is abandoned as a whole. */
  discardRemaining(): void {
    for (const entry of [...this.entries.values()]) {
      if (entry.final) continue;
      entry.handle.remove();
      this.entries.delete(entry.sentenceId);
    }
  }

  /** True when the round ended with unfinished sentences or a sentence-id gap. */
  get isPartial(): boolean {
    if (this.gap) return true;
    return [...this.entries.values()].some((entry) => !entry.final);
  }

  /** Slots in sentence order, for the manual polish pass. */
  slots(): DictationSlotHandle[] {
    return [...this.entries.values()]
      .sort((a, b) => a.sentenceId - b.sentenceId)
      .map((entry) => entry.handle);
  }

  text(): string {
    return this.slots().map((handle) => handle.getText()).join('');
  }
}
