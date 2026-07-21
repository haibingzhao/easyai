import React, { useState, useEffect } from 'react';
import { Copy, Check } from 'lucide-react';
import {
  getShikiHighlighter,
  getCachedHighlight,
  setCachedHighlight,
  stripPreCodeTransformer,
} from '@/utils/shiki-utils';

interface CodeBlockProps {
  className?: string;
  children: string;
  /** When true, skip async syntax highlighting and show plain text to avoid flicker during streaming */
  isStreaming?: boolean;
}

const extractFileName = (meta: string): string | null => {
  // Support formats like: "ts chat-store.ts" or "chat-store.ts" or "typescript filename.ts"
  const match = meta.match(/(?:^|\s)(\S+\.\w+)(?:\s|$)/);
  return match ? match[1] : null;
};

const extractLanguage = (className: string): string => {
  const match = className?.match(/language-(\w+)/);
  return match ? match[1] : '';
};

export const CodeBlock: React.FC<CodeBlockProps> = ({ className, children, isStreaming }) => {
  const language = extractLanguage(className || '');
  const meta = className?.replace(/language-\w+/, '')?.trim() || '';
  const fileName = extractFileName(meta);
  const code = children.replace(/\n$/, '');

  // Check module-level cache synchronously on mount to avoid loading flicker
  const cachedHtml = getCachedHighlight(code, language);

  const [highlighted, setHighlighted] = useState<string>(cachedHtml ?? '');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    // Skip highlighting entirely during streaming — show plain text to avoid flicker
    if (isStreaming) return;

    // Skip if cache already has the result for this code+language
    if (getCachedHighlight(code, language) !== undefined) return;

    let mounted = true;
    
    const highlight = async () => {
      try {
        const h = await getShikiHighlighter();
        if (!mounted) return;
        
        const result = h.codeToHtml(code, {
          lang: language || 'text',
          theme: 'github-dark',
          transformers: [stripPreCodeTransformer],
        });
        
        if (mounted) {
          setHighlighted(result);
          setCachedHighlight(code, language, result);
        }
      } catch (error) {
        console.error('Failed to highlight code:', error);
      }
    };

    highlight();
    
    return () => {
      mounted = false;
    };
  }, [code, language, isStreaming]);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // Plain-text fallback: uses per-line <span class="line"> to match Shiki's DOM structure
  // and .shiki .line CSS, ensuring identical layout before/after highlighting completes.
  const plainTextHtml = code
    .split('\n')
    .map(line => `<span class="line">${line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || ' '}</span>`)
    .join('');

  return (
    <div className="my-4 rounded-lg overflow-hidden bg-[#0d1117] border border-[#30363d]">
      <div className="flex items-center justify-between px-4 py-2 bg-[#161b22] border-b border-[#30363d]">
        <div className="flex items-center gap-3">
          {language && (
            <span className="text-xs font-semibold text-[#58a6ff] capitalize tracking-wide">{language}</span>
          )}
          {fileName && (
            <span className="text-xs text-[#c9d1d9] font-medium">{fileName}</span>
          )}
        </div>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs text-[#c9d1d9] hover:bg-[#30363d] rounded-md transition-all duration-200"
          title="Copy code"
        >
          {copied ? <Check size={14} className="text-[#3fb950]" /> : <Copy size={14} />}
          <span>{copied ? 'Copied!' : 'Copy'}</span>
        </button>
      </div>
      <div className="overflow-x-auto">
        <div className="flex">
          {/* Line numbers */}
          <div className="flex flex-col py-4 px-3 text-right select-none bg-[#0d1117] border-r border-[#30363d]">
            {code.split('\n').map((_, i) => (
              <div key={i} className="text-xs text-[#484f58] font-mono leading-6">
                {i + 1}
              </div>
            ))}
          </div>
          {/* Code content — always same DOM structure; falls back to plain text during streaming */}
          <div 
            className="shiki flex-1 p-4 overflow-x-auto"
            dangerouslySetInnerHTML={{ __html: highlighted || plainTextHtml }}
          />
        </div>
      </div>
      <style>{`
        .shiki .line {
          font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
          font-size: 0.875rem;
          line-height: 1.5rem;
          display: block;
          white-space: pre;
        }
      `}</style>
    </div>
  );
};
