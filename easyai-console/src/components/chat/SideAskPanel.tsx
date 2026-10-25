import React, { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AlertCircle, Quote, Send, Square, X } from 'lucide-react';
import { markdownCodeComponents } from './markdownCodeComponents';
import { ModelSelector } from './ModelSelector';
import { useSideAskStore } from '@/services/stores/side-ask-store';
import { i18n } from '@/utils/i18n';

const QUOTE_PREVIEW_LENGTH = 60;

/**
 * Ephemeral side panel for asking about selected chat text.
 * History lives only in memory (side-ask-store); nothing is persisted.
 */
export const SideAskPanel: React.FC = () => {
  const selectedQuote = useSideAskStore((s) => s.selectedQuote);
  const messages = useSideAskStore((s) => s.messages);
  const context = useSideAskStore((s) => s.context);
  const contextTruncated = useSideAskStore((s) => s.contextTruncated);
  const contextClipped = useSideAskStore((s) => s.contextClipped);
  const isStreaming = useSideAskStore((s) => s.isStreaming);
  const modelConfigId = useSideAskStore((s) => s.modelConfigId);
  const setModelConfigId = useSideAskStore((s) => s.setModelConfigId);
  const abortAndClose = useSideAskStore((s) => s.abortAndClose);
  const abort = useSideAskStore((s) => s.abort);
  const ask = useSideAskStore((s) => s.ask);
  const [input, setInput] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const submit = () => {
    const question = input.trim();
    if (!question || isStreaming) return;
    setInput('');
    void ask(question);
  };

  return (
    <div className="h-full flex flex-col bg-background">
      <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
        <span className="text-sm font-medium">{i18n('Side Q&A')}</span>
        <button
          onClick={abortAndClose}
          className="p-1 rounded-md hover:bg-muted transition-colors"
          title={i18n('Close panel (history will be lost)')}
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <div className="px-3 py-2 border-b border-border shrink-0">
        <div
          className="flex items-start gap-1.5 px-2 py-1.5 bg-muted rounded-md text-xs text-muted-foreground"
          title={selectedQuote}
        >
          <Quote className="w-3.5 h-3.5 shrink-0 mt-0.5" />
          <span className="line-clamp-2 break-words">
            {selectedQuote.length > QUOTE_PREVIEW_LENGTH
              ? `${selectedQuote.slice(0, QUOTE_PREVIEW_LENGTH)}…`
              : selectedQuote}
          </span>
        </div>
        {context.length > 0 && (
          <div className="mt-1.5 text-[11px] leading-snug text-muted-foreground">
            {i18n('Context: {count} messages from this conversation').replace('{count}', String(context.length))}
            {contextTruncated
              ? ` · ${i18n('earlier messages omitted')}`
              : contextClipped
                ? ` · ${i18n('long messages shortened')}`
                : ''}
          </div>
        )}
      </div>

      <div ref={listRef} className="flex-1 overflow-y-auto px-3 py-3 flex flex-col gap-3">
        {messages.map((message, index) => {
          const isLast = index === messages.length - 1;
          if (message.role === 'user') {
            return (
              <div key={index} className="self-end max-w-[85%] px-3 py-2 rounded-lg bg-primary/10 text-sm whitespace-pre-wrap break-words">
                {message.content}
              </div>
            );
          }
          if (!message.content && !message.error) {
            return isLast && isStreaming ? (
              <div key={index} className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
                <span>{i18n('Generating...')}</span>
              </div>
            ) : null;
          }
          return (
            <div key={index} className="flex flex-col gap-2">
              {message.content && (
                <div className="prose prose-sm dark:prose-invert max-w-none break-words">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                    {message.content}
                  </ReactMarkdown>
                </div>
              )}
              {message.error && (
                <div className="flex items-start gap-1.5 p-2 rounded-md bg-destructive/10 border border-destructive/20">
                  <AlertCircle className="w-3.5 h-3.5 text-destructive shrink-0 mt-0.5" />
                  <span className="text-xs text-destructive whitespace-pre-wrap break-words">
                    {i18n('Quick ask failed')}: {message.error}
                  </span>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="border-t border-border px-3 py-2 flex flex-col gap-2 shrink-0">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          rows={2}
          placeholder={i18n('Ask a follow-up...')}
          className="w-full resize-none bg-muted rounded-md px-2 py-1.5 text-sm outline-none focus:ring-1 focus:ring-primary"
        />
        <div className="flex items-center justify-between">
          <ModelSelector
            selectedId={modelConfigId}
            persistSelection={false}
            onModelChange={(configId) => setModelConfigId(configId)}
          />
          <button
            onClick={isStreaming ? abort : submit}
            disabled={!isStreaming && !input.trim()}
            className={`p-1.5 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
              isStreaming
                ? 'bg-destructive text-destructive-foreground hover:bg-destructive/90'
                : 'bg-primary text-primary-foreground hover:opacity-90'
            }`}
            title={isStreaming ? i18n('Stop') : i18n('Send')}
          >
            {isStreaming ? <Square className="w-4 h-4" /> : <Send className="w-4 h-4" />}
          </button>
        </div>
      </div>
    </div>
  );
};
