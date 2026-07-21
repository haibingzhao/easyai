import React, { useState, useEffect } from 'react';
import { Loader2, AlertCircle, FileText, Eye, Code } from 'lucide-react';
import { readFileContent } from '@/services/file-browser-service';
import type { FileContentResponse } from '@/services/file-browser-service';
import { getShikiHighlighter, stripPreCodeTransformer } from '@/utils/shiki-utils';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { i18n } from '@/utils/i18n';
import { useProjectStore } from '@/services/stores/project-store';

interface FileViewerProps {
  filePath: string;
}

/** Map file extension to shiki language identifier */
const EXT_TO_LANG: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript',
  js: 'javascript', jsx: 'javascript',
  kt: 'kotlin', kts: 'kotlin',
  java: 'java',
  py: 'python',
  go: 'go',
  rs: 'rust',
  json: 'json',
  yaml: 'yaml', yml: 'yaml',
  xml: 'xml',
  html: 'html', htm: 'html',
  css: 'css', scss: 'css',
  sql: 'sql',
  md: 'markdown', mdx: 'markdown',
  sh: 'bash', bash: 'bash', zsh: 'bash',
  toml: 'toml',
};

/** Markdown file extensions */
const MD_EXTS = new Set(['md', 'mdx']);

/** Text-based file extensions and names that can be rendered as plain text */
const TEXT_EXTS = new Set([
  'txt', 'log', 'csv', 'tsv', 'env',
  'gitignore', 'dockerignore', 'qoderignore', 'editorconfig',
  'properties', 'cfg', 'conf', 'ini',
]);

export const FileViewer: React.FC<FileViewerProps> = ({ filePath }) => {
  const [data, setData] = useState<FileContentResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [mdMode, setMdMode] = useState<'preview' | 'code'>('preview');
  const currentProject = useProjectStore((s) => s.currentProject);

  // Reset mdMode to preview when switching files
  useEffect(() => {
    setMdMode('preview');
  }, [filePath]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setData(null);

    const projectId = currentProject?.id || '';
    readFileContent(filePath, projectId)
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.message || 'Failed to load file');
          setLoading(false);
        }
      });

    return () => { cancelled = true; };
  }, [filePath, currentProject?.id]);

  // Extract filename and extension
  const fileName = filePath.split('/').pop() || filePath;
  const ext = fileName.includes('.') ? fileName.split('.').pop()?.toLowerCase() || '' : '';
  const isDotFile = fileName.startsWith('.') && !fileName.includes('.', 1);

  if (loading) {
    return (
      <div className="h-full flex items-center justify-center text-muted-foreground">
        <Loader2 className="w-5 h-5 animate-spin mr-2" />
        <span className="text-sm">{i18n('Loading file...')}</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2">
        <AlertCircle className="w-5 h-5 text-destructive" />
        <span className="text-sm">{i18n('Failed to load file')}</span>
        <span className="text-xs text-muted-foreground">{error}</span>
      </div>
    );
  }

  if (!data) return null;

  // Markdown rendering with Preview/Code toggle
  if (MD_EXTS.has(ext)) {
    return (
      <div className="h-full flex flex-col overflow-hidden">
        {/* Preview/Code toggle bar */}
        <div className="flex items-center gap-1 px-4 py-1.5 border-b border-border shrink-0 bg-background">
          <button
            onClick={() => setMdMode('preview')}
            className={`flex items-center gap-1 px-2 py-1 text-xs rounded-md transition-colors ${
              mdMode === 'preview' ? 'bg-muted font-medium text-foreground' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <Eye className="w-3.5 h-3.5" />
            Preview
          </button>
          <button
            onClick={() => setMdMode('code')}
            className={`flex items-center gap-1 px-2 py-1 text-xs rounded-md transition-colors ${
              mdMode === 'code' ? 'bg-muted font-medium text-foreground' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            <Code className="w-3.5 h-3.5" />
            Code
          </button>
          <span className="ml-auto text-xs text-muted-foreground">{fileName}</span>
        </div>
        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {mdMode === 'preview' ? (
            <div className="prose max-w-none text-sm p-4">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {data.content}
              </ReactMarkdown>
            </div>
          ) : (
            <CodeView content={data.content} language="markdown" fileName={fileName} />
          )}
        </div>
      </div>
    );
  }

  // Known code/text file — render with syntax highlighting
  const lang = EXT_TO_LANG[ext] || (TEXT_EXTS.has(ext) || isDotFile ? 'text' : null);

  if (lang !== null) {
    return (
      <CodeView content={data.content} language={lang === 'text' ? '' : lang} fileName={fileName} />
    );
  }

  // Unknown/binary type
  return (
    <div className="h-full flex flex-col items-center justify-center text-muted-foreground gap-2">
      <FileText className="w-8 h-8" />
      <span className="text-sm font-medium">{fileName}</span>
      <span className="text-xs">{i18n('Preview not available')}</span>
    </div>
  );
};

/** Code view with syntax highlighting (reuses Shiki pattern from CodeBlock) */
const CodeView: React.FC<{ content: string; language: string; fileName: string }> = ({
  content,
  language,
  fileName,
}) => {
  const [highlighted, setHighlighted] = useState('');

  useEffect(() => {
    let mounted = true;

    if (!language) {
      setHighlighted('');
      return;
    }

    const highlight = async () => {
      try {
        const h = await getShikiHighlighter();
        if (!mounted) return;

        const result = h.codeToHtml(content, {
          lang: language,
          theme: 'github-dark',
          transformers: [stripPreCodeTransformer],
        });

        if (mounted) setHighlighted(result);
      } catch {
        // Fall back to plain text on error
        if (mounted) setHighlighted('');
      }
    };

    highlight();
    return () => { mounted = false; };
  }, [content, language]);

  const lines = content.split('\n');

  // Plain text fallback HTML
  const plainTextHtml = lines
    .map((line) => `<span class="line">${line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') || ' '}</span>`)
    .join('');

  return (
    <div className="h-full flex flex-col overflow-hidden bg-[#0d1117]">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-2 bg-[#161b22] border-b border-[#30363d] shrink-0">
        {language && (
          <span className="text-xs font-semibold text-[#58a6ff] capitalize tracking-wide">
            {language}
          </span>
        )}
        <span className="text-xs text-[#c9d1d9] font-medium">{fileName}</span>
      </div>

      {/* Code area */}
      <div className="flex-1 overflow-auto">
        <div className="flex min-h-full">
          {/* Line numbers */}
          <div className="flex flex-col py-4 px-3 text-right select-none bg-[#0d1117] border-r border-[#30363d] shrink-0">
            {lines.map((_, i) => (
              <div key={i} className="text-xs text-[#484f58] font-mono leading-6">
                {i + 1}
              </div>
            ))}
          </div>

          {/* Code content */}
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
