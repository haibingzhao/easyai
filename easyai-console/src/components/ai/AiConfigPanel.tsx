import React, { useState, useCallback, useRef, useEffect } from 'react';
import { aiConfigService } from '@/services/ai-config-service';
import { modelConfigService } from '@/services/model-config-service';
import { i18n } from '@/utils/i18n';
import {
  X, Sparkles, Loader2, AlertCircle, CheckCircle2,
  AlertTriangle, RefreshCw, Square, ChevronDown, User,
} from 'lucide-react';
import type { AiConfigGenerateResponse, ConfigValidationError } from '@/types/ai-config';
import type { ModelProviderConfig } from '@/types/settings';
import { ThinkingBlock } from '@/components/chat/ThinkingBlock';
import { CodeBlock } from '@/components/chat/CodeBlock';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { markdownCodeComponents } from '@/components/chat/markdownCodeComponents';
import { useResizable } from '@/hooks/useResizable';

interface AiConfigPanelProps {
  configType: 'agent' | 'swarm';
  existingConfig?: Record<string, unknown>;
  onApply: (config: Record<string, unknown>) => void;
  onClose?: () => void;
  /** When true, renders without side-panel chrome (no border-l, no close btn, no fixed width) */
  inline?: boolean;
}

type PanelState = 'idle' | 'streaming' | 'result' | 'error';

/** A segment in the streaming output */
type StreamingSegment =
  | { type: 'thinking'; content: string; isFinished: boolean }
  | { type: 'text'; content: string }
  | { type: 'retry_marker'; attempt: number; reason?: string }
  | { type: 'status_update'; tool: string; status: string; message?: string; toolCallId?: string };

/**
 * Check if a string looks like JSON (starts with { or [).
 */
function looksLikeJson(text: string): boolean {
  const trimmed = text.trim();
  return trimmed.startsWith('{') || trimmed.startsWith('[');
}

export const AiConfigPanel: React.FC<AiConfigPanelProps> = ({
  configType,
  existingConfig,
  onApply,
  onClose,
  inline = false,
}) => {
  const [description, setDescription] = useState('');
  const [state, setState] = useState<PanelState>('idle');
  const [segments, setSegments] = useState<StreamingSegment[]>([]);
  const [result, setResult] = useState<AiConfigGenerateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [retryNote, setRetryNote] = useState('');
  const [submittedDescription, setSubmittedDescription] = useState('');

  // Model selector state (local, does not modify global settings)
  const [models, setModels] = useState<ModelProviderConfig[]>([]);
  const [selectedModelId, setSelectedModelId] = useState<string | null>(
    () => localStorage.getItem('easyai:ai-config:model-id')
  );
  const [modelDropdownOpen, setModelDropdownOpen] = useState(false);

  const abortRef = useRef<{ abort: () => void } | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Resizable panel width (side-panel mode only)
  const [panelWidth, setPanelWidth] = useState(380);
  const [panelResizing, setPanelResizing] = useState(false);

  const panelResizer = useResizable({
    minWidth: 280,
    maxWidth: 700,
    onResize: (w) => setPanelWidth(Math.round(w)),
    direction: 'left',
    onResizeStart: () => setPanelResizing(true),
    onResizeEnd: () => setPanelResizing(false),
  });

  // Persist selected model to localStorage
  useEffect(() => {
    if (selectedModelId) {
      localStorage.setItem('easyai:ai-config:model-id', selectedModelId);
    }
  }, [selectedModelId]);

  // Load models on mount
  useEffect(() => {
    modelConfigService.getUserConfigurations().then((configs) => {
      const enabled = configs.filter(c => c.enabled !== false);
      setModels(enabled);
      if (enabled.length > 0 && !selectedModelId) {
        setSelectedModelId(enabled[0].id);
      } else if (selectedModelId && !enabled.some(c => c.id === selectedModelId)) {
        // Saved model no longer available, fall back to first
        setSelectedModelId(enabled.length > 0 ? enabled[0].id : null);
      }
    }).catch(() => { /* ignore */ });
  }, []);

  // Auto-scroll to bottom during streaming
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [segments]);

  const selectedModel = models.find(m => m.id === selectedModelId);

  const handleGenerate = useCallback((desc?: string) => {
    const text = desc ?? description;
    if (!text.trim()) return;
    setSubmittedDescription(text.trim());
    setState('streaming');
    setSegments([]);
    setResult(null);
    setError(null);

    abortRef.current = aiConfigService.generateConfigStream(
      {
        description: text.trim(),
        configType,
        modelConfigId: selectedModelId ?? undefined,
        existingConfig: existingConfig as Record<string, unknown> & { [key: string]: unknown } | undefined,
      },
      {
        onStreamStart: () => {
          // Start a new text segment for this attempt's output
          setSegments(prev => [...prev, { type: 'text', content: '' }]);
        },
        onThinkingDelta: (delta) => {
          setSegments(prev => {
            const last = prev[prev.length - 1];
            if (last && last.type === 'thinking' && !last.isFinished) {
              return [...prev.slice(0, -1), { ...last, content: last.content + delta }];
            }
            return [...prev, { type: 'thinking', content: delta, isFinished: false }];
          });
        },
        onThinkingEnd: () => {
          setSegments(prev => {
            const last = prev[prev.length - 1];
            if (last && last.type === 'thinking' && !last.isFinished) {
              return [...prev.slice(0, -1), { ...last, isFinished: true }];
            }
            return prev;
          });
        },
        onTextDelta: (delta) => {
          setSegments(prev => {
            const last = prev[prev.length - 1];
            if (last && last.type === 'text') {
              return [...prev.slice(0, -1), { ...last, content: last.content + delta }];
            }
            return [...prev, { type: 'text', content: delta }];
          });
        },
        onRetryStart: (attempt, reason) => {
          // Add retry marker and a new text segment — don't clear history
          setSegments(prev => [
            ...prev,
            { type: 'retry_marker', attempt, reason },
            { type: 'text', content: '' },
          ]);
        },
        onStreamEnd: () => {
          // noop - wait for config_done
        },
        onDone: (response) => {
          setResult(response);
          setState('result');
          abortRef.current = null;
        },
        onStatusUpdate: (tool, status, message, toolCallId) => {
          setSegments(prev => {
            // Terminal status: update the matching running segment in-place
            if (status !== 'running') {
              let idx = -1;
              for (let i = prev.length - 1; i >= 0; i--) {
                const s = prev[i];
                if (s.type !== 'status_update' || s.status !== 'running') continue;
                if (toolCallId ? s.toolCallId === toolCallId : s.tool === tool) {
                  idx = i;
                  break;
                }
              }
              if (idx !== -1) {
                const updated = [...prev];
                const seg = updated[idx] as Extract<StreamingSegment, { type: 'status_update' }>;
                updated[idx] = { ...seg, status };
                return updated;
              }
            }
            return [...prev, { type: 'status_update', tool, status, message, toolCallId }];
          });
        },
        onError: (message) => {
          setError(message);
          setState('error');
          abortRef.current = null;
        },
      }
    );
  }, [description, configType, selectedModelId, existingConfig]);

  const handleAbort = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState('idle');
  }, []);

  const handleRetry = useCallback(() => {
    const retryDesc = retryNote.trim()
      ? `${description}\n\nAdditional notes: ${retryNote.trim()}`
      : description;
    setRetryNote('');
    handleGenerate(retryDesc);
  }, [description, retryNote, handleGenerate]);

  const handleApply = useCallback(() => {
    if (result?.generatedConfig) {
      onApply(result.generatedConfig as unknown as Record<string, unknown>);
    }
  }, [result, onApply]);

  // Cleanup on unmount
  useEffect(() => {
    return () => { abortRef.current?.abort(); };
  }, []);

  const validationErrors = result?.validation.errors ?? [];
  const hasErrors = validationErrors.some((e) => e.severity === 'error');

  return (
    <>
    {/* Resize handle — left edge of side panel */}
    {!inline && (
      <div
        className={`resize-handle ${panelResizing ? 'active' : ''}`}
        onMouseDown={(e) => {
          panelResizer.setCurrentWidth(panelWidth);
          panelResizer.onMouseDown(e);
        }}
        onTouchStart={(e) => {
          panelResizer.setCurrentWidth(panelWidth);
          panelResizer.onTouchStart(e);
        }}
      />
    )}
    <div className={inline
      ? 'flex flex-col'
      : 'shrink-0 border-l border-border flex flex-col h-full bg-background'
    } style={!inline ? { width: panelWidth } : undefined}>
      {/* Header — only in side-panel mode */}
      {!inline && (
        <div className="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
          <div className="flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-primary" />
            <span className="text-sm font-medium">
              {i18n(configType === 'agent' ? 'AI Generate Agent' : 'AI Generate Workflow')}
            </span>
          </div>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Model selector */}
      <div className={inline ? 'py-2' : 'px-4 py-2 border-b border-border shrink-0'}>
        <div className="relative">
          <button
            onClick={() => setModelDropdownOpen(!modelDropdownOpen)}
            className="flex items-center gap-2 w-full px-3 py-1.5 text-xs rounded-md border border-border hover:bg-muted transition-colors"
          >
            <span className="text-muted-foreground">{i18n('Model')}:</span>
            <span className="flex-1 text-left truncate font-medium">
              {selectedModel ? (selectedModel.modelName || selectedModel.modelId) : i18n('Select Model')}
            </span>
            <ChevronDown className={`w-3 h-3 transition-transform ${modelDropdownOpen ? 'rotate-180' : ''}`} />
          </button>
          {modelDropdownOpen && (
            <div className="absolute top-full left-0 right-0 mt-1 bg-popover border border-border rounded-md shadow-lg z-50 max-h-48 overflow-y-auto">
              {models.map(m => (
                <button
                  key={m.id}
                  onClick={() => { setSelectedModelId(m.id); setModelDropdownOpen(false); }}
                  className={`w-full text-left px-3 py-2 text-xs hover:bg-muted transition-colors ${
                    selectedModelId === m.id ? 'bg-muted' : ''
                  }`}
                >
                  <div className="font-medium">{m.name}</div>
                  <div className="text-muted-foreground mt-0.5">{m.modelName || m.modelId}</div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Content area */}
      <div className={inline ? '' : 'flex-1 overflow-y-auto'}>
        {/* Idle: description input */}
        {state === 'idle' && (
          <div className="p-4 space-y-3">
            <textarea
              className="w-full min-h-28 p-3 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary resize-y"
              placeholder={configType === 'agent'
                ? i18n('e.g., A code review agent that can read files and search code...')
                : i18n('e.g., A multi-agent pipeline: analyze code → review → summarize...')
              }
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            {error && (
              <div className="flex items-start gap-2 text-sm text-destructive">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{error}</span>
              </div>
            )}
            <button
              className="w-full flex items-center justify-center gap-1.5 px-4 py-2 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={() => handleGenerate()}
              disabled={!description.trim() || !selectedModelId}
            >
              <Sparkles className="w-3.5 h-3.5" />
              {i18n('Generate')}
            </button>
          </div>
        )}

        {/* Streaming: live thinking + text rendering */}
        {state === 'streaming' && (
          <div ref={scrollRef} className="p-4 space-y-3 overflow-y-auto max-h-[calc(100vh-300px)]">
            {/* User input */}
            {submittedDescription && (
              <div className="flex items-start gap-2 py-2 px-3 text-sm bg-primary/5 border border-primary/20 rounded-md">
                <User className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
                <span className="whitespace-pre-wrap">{submittedDescription}</span>
              </div>
            )}

            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              {i18n('AI is generating...')}
            </div>

            {segments.map((segment, index) => {
              switch (segment.type) {
                case 'thinking':
                  return segment.content.trim() ? (
                    <ThinkingBlock
                      key={`thinking-${index}`}
                      content={segment.content}
                      isStreaming={!segment.isFinished}
                      isFinished={segment.isFinished}
                    />
                  ) : null;

                case 'text': {
                  if (!segment.content.trim()) return null;
                  // Check if content looks like JSON
                  if (looksLikeJson(segment.content)) {
                    // Try to format as JSON, fall back to raw if parse fails
                    let formatted = segment.content;
                    try {
                      formatted = JSON.stringify(JSON.parse(segment.content), null, 2);
                    } catch {
                      // Partial JSON during streaming — show raw
                    }
                    return (
                      <CodeBlock key={`text-${index}`} className="language-json">
                        {formatted}
                      </CodeBlock>
                    );
                  }
                  // Non-JSON: render as markdown
                  return (
                    <div key={`text-${index}`} className="prose prose-sm dark:prose-invert max-w-none">
                      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                        {segment.content}
                      </ReactMarkdown>
                    </div>
                  );
                }

                case 'retry_marker':
                  return (
                    <div key={`retry-${index}`} className="flex items-start gap-2 py-2 px-3 text-xs text-muted-foreground bg-muted rounded-md">
                      <RefreshCw className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                      <div>
                        <span>{i18n('Retrying (attempt')} {segment.attempt}...</span>
                        {segment.reason && (
                          <div className="mt-1 text-yellow-600 dark:text-yellow-500">
                            {segment.reason}
                          </div>
                        )}
                      </div>
                    </div>
                  );

                case 'status_update':
                  return (
                    <div key={`status-${index}`} className="flex items-center gap-2 py-1.5 px-3 text-xs text-muted-foreground bg-blue-50 dark:bg-blue-950/30 border border-blue-200 dark:border-blue-800 rounded-md">
                      {segment.status === 'running' ? (
                        <Loader2 className="w-3.5 h-3.5 shrink-0 animate-spin text-blue-500" />
                      ) : segment.status === 'error' ? (
                        <AlertCircle className="w-3.5 h-3.5 shrink-0 text-red-500" />
                      ) : (
                        <CheckCircle2 className="w-3.5 h-3.5 shrink-0 text-blue-500" />
                      )}
                      <span>
                        {segment.message ?? `${segment.tool}: ${segment.status}`}
                      </span>
                    </div>
                  );

                default:
                  return null;
              }
            })}

            <button
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md border border-border hover:bg-muted transition-colors"
              onClick={handleAbort}
            >
              <Square className="w-3 h-3" />
              {i18n('Stop')}
            </button>
          </div>
        )}

        {/* Error: show accumulated segments + error message */}
        {state === 'error' && (
          <div className="p-4 space-y-3">
            {/* User input */}
            {submittedDescription && (
              <div className="flex items-start gap-2 py-2 px-3 text-sm bg-primary/5 border border-primary/20 rounded-md">
                <User className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
                <span className="whitespace-pre-wrap">{submittedDescription}</span>
              </div>
            )}

            {/* Preserve streaming history on error */}
            {segments.length > 0 && (
              <div className="space-y-3 max-h-64 overflow-y-auto border border-border rounded-md p-3 bg-muted/30">
                {segments.map((segment, index) => {
                  switch (segment.type) {
                    case 'thinking':
                      return segment.content.trim() ? (
                        <ThinkingBlock
                          key={`err-thinking-${index}`}
                          content={segment.content}
                          isFinished
                        />
                      ) : null;

                    case 'text': {
                      if (!segment.content.trim()) return null;
                      if (looksLikeJson(segment.content)) {
                        let formatted = segment.content;
                        try {
                          formatted = JSON.stringify(JSON.parse(segment.content), null, 2);
                        } catch {
                          // Partial/invalid JSON — show raw
                        }
                        return (
                          <CodeBlock key={`err-text-${index}`} className="language-json">
                            {formatted}
                          </CodeBlock>
                        );
                      }
                      return (
                        <div key={`err-text-${index}`} className="prose prose-sm dark:prose-invert max-w-none">
                          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                            {segment.content}
                          </ReactMarkdown>
                        </div>
                      );
                    }

                    case 'retry_marker':
                      return (
                        <div key={`err-retry-${index}`} className="flex items-start gap-2 py-2 px-3 text-xs text-muted-foreground bg-muted rounded-md">
                          <RefreshCw className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          <div>
                            <span>{i18n('Retrying (attempt')} {segment.attempt}...</span>
                            {segment.reason && (
                              <div className="mt-1 text-yellow-600 dark:text-yellow-500">
                                {segment.reason}
                              </div>
                            )}
                          </div>
                        </div>
                      );

                    case 'status_update':
                      return (
                        <div key={`err-status-${index}`} className="flex items-center gap-2 py-1.5 px-3 text-xs text-muted-foreground bg-blue-50 dark:bg-blue-950/30 border border-blue-200 dark:border-blue-800 rounded-md">
                          {segment.status === 'error' ? (
                            <AlertCircle className="w-3.5 h-3.5 shrink-0 text-red-500" />
                          ) : (
                            <CheckCircle2 className="w-3.5 h-3.5 shrink-0 text-blue-500" />
                          )}
                          <span>{segment.message ?? `${segment.tool}: ${segment.status}`}</span>
                        </div>
                      );

                    default:
                      return null;
                  }
                })}
              </div>
            )}

            {/* Error message */}
            {error && (
              <div className="flex items-start gap-2 text-sm text-destructive">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{error}</span>
              </div>
            )}

            {/* Back to input */}
            <button
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md border border-border hover:bg-muted transition-colors"
              onClick={() => { setState('idle'); setError(null); }}
            >
              <RefreshCw className="w-3.5 h-3.5" />
              {i18n('Try Again')}
            </button>
          </div>
        )}

        {/* Result: validation + formatted JSON */}
        {state === 'result' && result && (
          <div className="p-4 space-y-3">
            {/* User input */}
            {submittedDescription && (
              <div className="flex items-start gap-2 py-2 px-3 text-sm bg-primary/5 border border-primary/20 rounded-md">
                <User className="w-3.5 h-3.5 shrink-0 mt-0.5 text-primary" />
                <span className="whitespace-pre-wrap">{submittedDescription}</span>
              </div>
            )}

            {/* Streaming history (thinking + text from generation) */}
            {segments.length > 0 && (
              <div className="space-y-3 max-h-64 overflow-y-auto border border-border rounded-md p-3 bg-muted/30">
                {segments.map((segment, index) => {
                  switch (segment.type) {
                    case 'thinking':
                      return segment.content.trim() ? (
                        <ThinkingBlock
                          key={`hist-thinking-${index}`}
                          content={segment.content}
                          isFinished
                        />
                      ) : null;

                    case 'text': {
                      if (!segment.content.trim()) return null;
                      if (looksLikeJson(segment.content)) {
                        let formatted = segment.content;
                        try {
                          formatted = JSON.stringify(JSON.parse(segment.content), null, 2);
                        } catch { /* keep raw */ }
                        return (
                          <CodeBlock key={`hist-text-${index}`} className="language-json">
                            {formatted}
                          </CodeBlock>
                        );
                      }
                      return (
                        <div key={`hist-text-${index}`} className="prose prose-sm dark:prose-invert max-w-none">
                          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                            {segment.content}
                          </ReactMarkdown>
                        </div>
                      );
                    }

                    case 'retry_marker':
                      return (
                        <div key={`hist-retry-${index}`} className="flex items-start gap-2 py-1.5 px-3 text-xs text-muted-foreground bg-muted rounded-md">
                          <RefreshCw className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          <div>
                            <span>{i18n('Retrying (attempt')} {segment.attempt}...</span>
                            {segment.reason && (
                              <div className="mt-1 text-yellow-600 dark:text-yellow-500">
                                {segment.reason}
                              </div>
                            )}
                          </div>
                        </div>
                      );

                    case 'status_update':
                      return (
                        <div key={`hist-status-${index}`} className="flex items-center gap-2 py-1.5 px-3 text-xs text-muted-foreground bg-blue-50 dark:bg-blue-950/30 border border-blue-200 dark:border-blue-800 rounded-md">
                          {segment.status === 'error' ? (
                            <AlertCircle className="w-3.5 h-3.5 shrink-0 text-red-500" />
                          ) : (
                            <CheckCircle2 className="w-3.5 h-3.5 shrink-0 text-blue-500" />
                          )}
                          <span>{segment.message ?? `${segment.tool}: ${segment.status}`}</span>
                        </div>
                      );

                    default:
                      return null;
                  }
                })}
              </div>
            )}

            {/* Explanation */}
            {result.explanation && (
              <div className="text-xs text-muted-foreground">{result.explanation}</div>
            )}

            {/* Validation status */}
            <div className="flex items-center gap-2">
              {result.validation.valid ? (
                <>
                  <CheckCircle2 className="w-4 h-4 text-green-500" />
                  <span className="text-sm text-green-600">{i18n('Validation passed')}</span>
                </>
              ) : hasErrors ? (
                <>
                  <AlertCircle className="w-4 h-4 text-destructive" />
                  <span className="text-sm text-destructive">{i18n('Validation failed')}</span>
                </>
              ) : null}
              {result.retryCount > 0 && (
                <span className="text-xs text-muted-foreground">
                  ({i18n('Auto-retried')} {result.retryCount}x)
                </span>
              )}
            </div>

            {/* Validation errors */}
            {validationErrors.length > 0 && (
              <div className="space-y-1 max-h-24 overflow-y-auto">
                {validationErrors.map((err: ConfigValidationError, i: number) => (
                  <div
                    key={i}
                    className={`flex items-start gap-2 text-xs ${
                      err.severity === 'error' ? 'text-destructive' : 'text-yellow-600'
                    }`}
                  >
                    {err.severity === 'error' ? (
                      <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                    ) : (
                      <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                    )}
                    <span>
                      <strong>{err.field}:</strong> {err.message}
                    </span>
                  </div>
                ))}
              </div>
            )}

            {/* Generated JSON preview */}
            <pre className="p-3 text-xs rounded-md bg-muted border border-border overflow-auto max-h-64 whitespace-pre-wrap break-all font-mono">
              {JSON.stringify(result.generatedConfig, null, 2)}
            </pre>

            {/* Retry with notes */}
            {hasErrors && (
              <div className="space-y-2">
                <textarea
                  className="w-full min-h-14 p-2 text-xs rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary resize-y"
                  placeholder={i18n('Optional: add notes for retry')}
                  value={retryNote}
                  onChange={(e) => setRetryNote(e.target.value)}
                />
              </div>
            )}

            {/* Actions */}
            <div className="flex gap-2">
              {hasErrors && (
                <button
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md border border-primary text-primary hover:bg-primary/10 transition-colors"
                  onClick={handleRetry}
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  {i18n('Retry')}
                </button>
              )}
              <button
                className="flex-1 px-4 py-2 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                onClick={handleApply}
                disabled={hasErrors}
              >
                {i18n('Apply to Form')}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
    </>
  );
};
