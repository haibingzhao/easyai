import React, { useRef, useState, useEffect, useCallback, useMemo, useImperativeHandle, forwardRef } from 'react';
import { createPortal } from 'react-dom';
import { Maximize2, Minimize2 } from 'lucide-react';
import {
  getShikiHighlighter,
  getCachedHighlight,
  setCachedHighlight,
  stripPreCodeTransformer,
} from '@/utils/shiki-utils';
import type { TemplateValidationError } from '@/types/agent';
import { VariableDropdown, type VariableGroup } from '@/components/agent/VariableDropdown';

export interface JinjaTemplateEditorHandle {
  insertAtCursor: (text: string) => void;
}

interface JinjaTemplateEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  maxLength?: number;
  /** Minimum visible rows (textarea grows with content) */
  rows?: number;
  disabled?: boolean;
  className?: string;
  /** Called when the user clicks the Validate button. */
  onValidate?: () => void;
  /** True while the backend validation request is in flight. */
  validating?: boolean;
  /** Errors returned from the backend validation. */
  validationErrors?: TemplateValidationError[];
  /** True when the last validation succeeded (no errors). Auto-clears after 3 s. */
  validationPassed?: boolean;
  /** Categorized variable groups shown in the toolbar dropdown. When provided, a "Variables" button appears in the toolbar (also visible in fullscreen). */
  variableGroups?: VariableGroup[];
}

const FONT_FAMILY = "'JetBrains Mono', 'Fira Code', 'Consolas', monospace";
const FONT_SIZE = '0.875rem'; // text-sm
const LINE_HEIGHT = '1.5rem';
const LINE_HEIGHT_PX = 24;
const V_PADDING = 16; // px — top/bottom padding of the editing area

/**
 * A textarea with Jinja2 syntax highlighting (via Shiki) and line numbers.
 *
 * Uses a measured-height approach: after each render the textarea's scrollHeight
 * is read and applied to the highlight layer so both layers are pixel-identical.
 * A single scrollable wrapper keeps them in sync without manual scroll handlers.
 */
export const JinjaTemplateEditor = forwardRef<JinjaTemplateEditorHandle, JinjaTemplateEditorProps>(({
  value,
  onChange,
  placeholder,
  maxLength,
  rows = 8,
  disabled = false,
  className,
  onValidate,
  validating = false,
  validationErrors,
  validationPassed = false,
  variableGroups,
}, ref) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);
  const lineNumbersRef = useRef<HTMLDivElement>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const lineMeasurerRef = useRef<HTMLDivElement>(null);

  useImperativeHandle(ref, () => ({
    insertAtCursor(text: string) {
      const ta = textareaRef.current;
      if (!ta) return;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const before = value.slice(0, start);
      const after = value.slice(end);
      onChange(before + text + after);
      // Restore cursor position after the inserted text
      requestAnimationFrame(() => {
        const newPos = start + text.length;
        ta.selectionStart = newPos;
        ta.selectionEnd = newPos;
        ta.focus();
      });
    },
  }), [value, onChange]);

  const [highlighted, setHighlighted] = useState('');
  const [measuredHeight, setMeasuredHeight] = useState(0);
  const [lineHeights, setLineHeights] = useState<number[]>([]);
  const [wrapperWidth, setWrapperWidth] = useState(0);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const lines = value.split('\n');
  const lineCount = lines.length;
  const minHeight = rows * LINE_HEIGHT_PX + V_PADDING * 2;

  // --- Shiki highlighting ---
  useEffect(() => {
    if (!value) {
      setHighlighted('');
      return;
    }

    const lang = 'jinja';
    const cached = getCachedHighlight(value, lang);
    if (cached !== undefined) {
      setHighlighted(cached);
      return;
    }

    let mounted = true;
    const highlight = async () => {
      try {
        const h = await getShikiHighlighter();
        if (!mounted) return;
        const result = h.codeToHtml(value, {
          lang,
          theme: 'github-dark',
          transformers: [stripPreCodeTransformer],
        });
        if (mounted) {
          setHighlighted(result);
          setCachedHighlight(value, lang, result);
        }
      } catch {
        // Shiki error — fall back to plain text
      }
    };
    highlight();
    return () => { mounted = false; };
  }, [value]);

  // Plain-text fallback (shown while Shiki loads, or on error)
  const plainTextHtml = lines
    .map(
      (line) =>
        `<span class="line">${line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || ' '}</span>`,
    )
    .join('\n');

  // Ensure the highlight HTML has the same number of lines as the textarea.
  // Shiki (or the plain-text fallback) may drop trailing empty lines, causing
  // the highlight layer to be visually shorter than the textarea content.
  const highlightHtml = (() => {
    const html = highlighted || plainTextHtml;
    if (!html) return html;
    const renderedCount = (html.match(/<span class="line"/g) || []).length;
    const missing = lineCount - renderedCount;
    if (missing > 0) {
      const padding = Array.from({ length: missing }, () => '<span class="line"> </span>').join('\n');
      return html + '\n' + padding;
    }
    return html;
  })();

  // --- Track wrapper width for line height re-measurement ---
  // Re-observe when isFullscreen changes because createPortal produces a new DOM tree.
  useEffect(() => {
    const wrapper = wrapperRef.current;
    if (!wrapper) return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setWrapperWidth(entry.contentRect.width);
      }
    });
    observer.observe(wrapper);
    return () => observer.disconnect();
  }, [isFullscreen]);

  // --- Measure visual height of each logical line (accounts for soft wrap) ---
  // Uses the Shiki highlight layer's .line elements as primary source because they share
  // the exact same container, CSS properties, and text layout as the textarea.
  // Falls back to the hidden measurer div when Shiki hasn't loaded yet.
  // Also directly updates gutter DOM to ensure line numbers stay in sync.
  useEffect(() => {
    const applyHeights = (heights: number[]) => {
      setLineHeights(heights);
      // Directly update gutter DOM for immediate visual sync
      const gutter = lineNumbersRef.current;
      if (gutter) {
        const divs = gutter.children;
        for (let i = 0; i < divs.length && i < heights.length; i++) {
          (divs[i] as HTMLElement).style.height = `${heights[i]}px`;
        }
      }
    };
    const measure = () => {
      // Primary: measure from Shiki highlight layer
      const shikiContainer = highlightRef.current?.querySelector('.shiki');
      if (shikiContainer) {
        const lineEls = shikiContainer.querySelectorAll<HTMLElement>('.line');
        if (lineEls.length > 0) {
          const heights = Array.from(lineEls).map(
            (el) => el.offsetHeight || LINE_HEIGHT_PX,
          );
          applyHeights(heights);
          return;
        }
      }
      // Fallback: hidden measurer div
      const measurer = lineMeasurerRef.current;
      if (measurer) {
        const heights = Array.from(measurer.children).map(
          (child) => (child as HTMLElement).offsetHeight || LINE_HEIGHT_PX,
        );
        applyHeights(heights);
      }
    };
    measure();
    const rafId = requestAnimationFrame(measure);
    const timeoutId = setTimeout(measure, 150);
    return () => {
      cancelAnimationFrame(rafId);
      clearTimeout(timeoutId);
    };
  }, [value, wrapperWidth, highlighted, isFullscreen]);

  // --- Measure textarea scrollHeight and sync to highlight layer ---
  // Reset height to 0 first so scrollHeight shrinks when lines are deleted.
  // Also re-measure on isFullscreen change because the textarea width (and thus
  // scrollHeight) changes significantly.
  useEffect(() => {
    const ta = textareaRef.current;
    if (ta) {
      requestAnimationFrame(() => {
        ta.style.height = '0px';
        const h = ta.scrollHeight;
        ta.style.height = '';
        setMeasuredHeight(h);
        // In non-fullscreen mode, set highlight layer height to match content.
        // In fullscreen mode, let inset:0 constrain the layer so it can scroll internally.
        if (highlightRef.current) {
          highlightRef.current.style.height = isFullscreen ? '' : `${h}px`;
        }
      });
    }
  }, [value, highlighted, isFullscreen]);

  // --- Scroll sync: textarea → highlight layer + line numbers ---
  const handleScroll = useCallback(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    if (highlightRef.current) {
      highlightRef.current.scrollTop = ta.scrollTop;
      highlightRef.current.scrollLeft = ta.scrollLeft;
    }
    if (lineNumbersRef.current) {
      lineNumbersRef.current.scrollTop = ta.scrollTop;
    }
  }, []);

  // --- Scroll to a specific line (used when clicking a validation error) ---
  const scrollToLine = useCallback((lineNumber: number) => {
    const ta = textareaRef.current;
    if (!ta) return;
    const targetTop = lineHeights.slice(0, lineNumber - 1).reduce((a, b) => a + b, 0);
    ta.scrollTop = Math.max(0, targetTop - LINE_HEIGHT_PX * 2);
    if (highlightRef.current) highlightRef.current.scrollTop = ta.scrollTop;
    if (lineNumbersRef.current) lineNumbersRef.current.scrollTop = ta.scrollTop;
    ta.focus();
  }, [lineHeights]);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange(e.target.value);
    },
    [onChange],
  );

  // --- Exit fullscreen on Escape key ---
  useEffect(() => {
    if (!isFullscreen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsFullscreen(false);
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isFullscreen]);

  // With soft-wrap, line count alone cannot determine height; rely on measured
  // scrollHeight as primary source, fall back to sum of measured line heights.
  const totalLineHeight = useMemo(
    () => lineHeights.reduce((a, b) => a + b, 0),
    [lineHeights],
  );
  const editingHeight = Math.max(measuredHeight, totalLineHeight + V_PADDING * 2, minHeight);

  const editorContent = (
    <div
      className={
        isFullscreen
          ? 'fixed inset-0 z-[9999] flex flex-col bg-[#0d1117]'
          : `rounded-lg overflow-hidden bg-[#0d1117] border border-[#30363d] ${disabled ? 'opacity-60' : ''} ${className || ''}`
      }
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-1.5 bg-[#161b22] border-b border-[#30363d] shrink-0">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-[#58a6ff] capitalize tracking-wide">Jinja2</span>
          {variableGroups && variableGroups.length > 0 && (
            <VariableDropdown
              groups={variableGroups}
              onInsert={(varName) => {
                const ta = textareaRef.current;
                if (!ta) return;
                const start = ta.selectionStart;
                const end = ta.selectionEnd;
                const before = value.slice(0, start);
                const after = value.slice(end);
                const insertion = `{{ ${varName} }}`;
                onChange(before + insertion + after);
                requestAnimationFrame(() => {
                  const newPos = start + insertion.length;
                  ta.selectionStart = newPos;
                  ta.selectionEnd = newPos;
                  ta.focus();
                });
              }}
              totalCount={variableGroups.reduce((sum, g) => sum + g.vars.length, 0)}
            />
          )}
        </div>
        <div className="flex items-center gap-3">
          {onValidate && (
            <button
              type="button"
              onClick={onValidate}
              disabled={disabled || validating || !value.trim()}
              className="flex items-center gap-1.5 px-2.5 py-0.5 rounded text-xs font-medium
                         bg-[#21262d] text-[#c9d1d9] border border-[#30363d]
                         hover:bg-[#30363d] hover:border-[#484f58]
                         disabled:opacity-40 disabled:cursor-not-allowed
                         transition-colors"
            >
              {validating ? (
                <svg className="animate-spin h-3 w-3" viewBox="0 0 24 24" fill="none">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
              ) : (
                <svg className="h-3 w-3" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M8 1a7 7 0 100 14A7 7 0 008 1zm0 12.5a5.5 5.5 0 110-11 5.5 5.5 0 010 11zM6.5 5.5l4 2.5-4 2.5V5.5z" />
                </svg>
              )}
              {validating ? 'Validating…' : 'Validate'}
            </button>
          )}
          {maxLength !== undefined && (
            <span className="text-xs text-[#484f58]">
              {value.length}/{maxLength}
            </span>
          )}
          <button
            type="button"
            onClick={() => setIsFullscreen((prev) => !prev)}
            className="p-1 rounded text-[#8b949e] hover:text-[#e6edf3] hover:bg-[#21262d] transition-colors"
            title={isFullscreen ? 'Exit fullscreen (Esc)' : 'Fullscreen'}
          >
            {isFullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
        </div>
      </div>

      {/* Editing area */}
      <div
        className={isFullscreen ? 'flex min-h-0 overflow-hidden' : 'flex'}
        style={{ height: isFullscreen ? undefined : editingHeight, flex: isFullscreen ? 1 : undefined, minHeight: 0 }}
      >
        {/* Line numbers gutter */}
        <div
          ref={lineNumbersRef}
          className="flex flex-col text-right select-none bg-[#0d1117] border-r border-[#30363d] shrink-0 jinja-hide-scrollbar"
          style={{ overflow: 'auto', paddingTop: V_PADDING, paddingBottom: V_PADDING, paddingLeft: 12, paddingRight: 12 }}
          aria-hidden
        >
          {Array.from({ length: Math.max(lineCount, rows) }, (_, i) => (
            <div
              key={i}
              className="text-[#484f58] flex items-start justify-end shrink-0"
              style={{
                fontFamily: FONT_FAMILY,
                fontSize: FONT_SIZE,
                lineHeight: LINE_HEIGHT,
                height: lineHeights[i] ?? LINE_HEIGHT_PX,
              }}
            >
              <span className="sticky top-0">{i + 1}</span>
            </div>
          ))}
        </div>

        {/* Code editing area */}
        <div ref={wrapperRef} className="flex-1 relative overflow-hidden">
          {/* Hidden line measurer — renders each line with pre-wrap to get visual heights */}
          <div
            ref={lineMeasurerRef}
            aria-hidden
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              boxSizing: 'border-box',
              visibility: 'hidden',
              pointerEvents: 'none',
              padding: `0 ${V_PADDING}px`,
              zIndex: -1,
            }}
          >
            {lines.map((line, i) => (
              <div
                key={i}
                style={{
                  fontFamily: FONT_FAMILY,
                  fontSize: FONT_SIZE,
                  lineHeight: LINE_HEIGHT,
                  whiteSpace: 'pre-wrap',
                  overflowWrap: 'break-word',
                  wordBreak: 'break-all',
                  tabSize: 2,
                  minHeight: LINE_HEIGHT_PX,
                }}
              >
                {line || '\u00A0'}
              </div>
            ))}
          </div>
          {/* Layer 1: Shiki-highlighted code (read-only backdrop) */}
          <div
            ref={highlightRef}
            className="absolute inset-0 pointer-events-none jinja-hide-scrollbar"
            style={{ overflow: 'auto', scrollbarWidth: 'none', padding: V_PADDING }}
          >
            <div
              className="shiki"
              dangerouslySetInnerHTML={{ __html: highlightHtml }}
            />
          </div>

          {/* Layer 2: Transparent textarea (captures input) */}
          <textarea
            ref={textareaRef}
            value={value}
            onChange={handleChange}
            onScroll={handleScroll}
            placeholder={placeholder}
            maxLength={maxLength}
            disabled={disabled}
            spellCheck={false}
            autoCapitalize="off"
            autoComplete="off"
            autoCorrect="off"
            className="jinja-editor-textarea absolute inset-0 w-full resize-none bg-transparent focus:outline-none focus:ring-2 focus:ring-[#58a6ff]/40"
            style={{
              fontFamily: FONT_FAMILY,
              fontSize: FONT_SIZE,
              lineHeight: LINE_HEIGHT,
              padding: V_PADDING,
              boxSizing: 'border-box',
              color: 'transparent',
              caretColor: '#e6edf3',
            }}
          />
        </div>
      </div>

      {/* Validation results */}
      {validationErrors && validationErrors.length > 0 && (
        <div className="border-t border-[#f85149]/30 bg-[#f8514908] px-4 py-2 space-y-1">
          {validationErrors.map((err, i) => (
            <div
              key={i}
              onClick={() => err.lineNumber !== undefined && scrollToLine(err.lineNumber)}
              className={`flex items-start gap-2 text-xs text-[#f85149] ${err.lineNumber !== undefined ? 'cursor-pointer hover:text-[#ffa198]' : ''}`}
            >
              {err.lineNumber !== undefined && (
                <span className="shrink-0 font-mono bg-[#f8514920] px-1 rounded">
                  L{err.lineNumber}{err.startPosition !== undefined ? `:${err.startPosition}` : ''}
                </span>
              )}
              <span>{err.fieldName ? `[${err.fieldName}] ` : ''}{err.message}</span>
            </div>
          ))}
        </div>
      )}

      {/* Validation success indicator */}
      {validationPassed && (!validationErrors || validationErrors.length === 0) && (
        <div className="border-t border-[#3fb950]/30 bg-[#3fb95008] px-4 py-2">
          <div className="flex items-center gap-2 text-xs text-[#3fb950]">
            <svg className="h-3 w-3" viewBox="0 0 16 16" fill="currentColor">
              <path d="M13.78 4.22a.75.75 0 010 1.06l-7.25 7.25a.75.75 0 01-1.06 0L2.22 9.28a.75.75 0 011.06-1.06L6 10.94l6.72-6.72a.75.75 0 011.06 0z" />
            </svg>
            <span>Syntax OK</span>
          </div>
        </div>
      )}

      {/* Scoped styles */}
      <style>{`
        .jinja-editor-textarea::placeholder {
          color: #484f58 !important;
        }
        .jinja-editor-textarea {
          white-space: pre-wrap;
          overflow-wrap: break-word;
          word-break: break-all;
          overflow: auto;
          tab-size: 2;
        }
        .shiki {
          min-height: 100%;
        }
        .shiki .line {
          font-family: ${FONT_FAMILY};
          font-size: ${FONT_SIZE};
          line-height: ${LINE_HEIGHT};
          display: block;
          white-space: pre-wrap;
          overflow-wrap: break-word;
          word-break: break-all;
          tab-size: 2;
          min-height: ${LINE_HEIGHT};
        }
        .jinja-hide-scrollbar::-webkit-scrollbar {
          display: none;
        }
        .jinja-hide-scrollbar {
          scrollbar-width: none;
        }
      `}</style>
    </div>
  );

  if (isFullscreen) {
    return createPortal(editorContent, document.body);
  }

  return editorContent;
});
