import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CodeBlock } from '../CodeBlock';
import { markdownCodeComponents } from '../markdownCodeComponents';

interface ContentSegment {
  type: 'code' | 'text';
  language?: string;
  content: string;
  /** Whether the code block has a closing fence (false = still streaming) */
  isComplete?: boolean;
}

/**
 * Parse text content into segments. Complete code blocks are extracted as 'code'
 * segments with isComplete=true; unclosed fences at the tail become 'code' segments
 * with isComplete=false so they bypass ReactMarkdown and avoid flicker while streaming.
 */
const parseContentSegments = (content: string): ContentSegment[] => {
  const codeBlockRegex = /```(\w*)\n([\s\S]*?)```/g;
  const segments: ContentSegment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = codeBlockRegex.exec(content)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ type: 'text', content: content.slice(lastIndex, match.index) });
    }
    segments.push({ type: 'code', language: match[1] || 'text', content: match[2], isComplete: true });
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < content.length) {
    const remaining = content.slice(lastIndex);
    const unclosedMatch = remaining.match(/^```(\w*)\n([\s\S]*)$/);
    if (unclosedMatch) {
      segments.push({
        type: 'code',
        language: unclosedMatch[1] || 'text',
        content: unclosedMatch[2],
        isComplete: false,
      });
    } else {
      segments.push({ type: 'text', content: remaining });
    }
  }

  return segments;
};

interface TextMarkdownProps {
  content: string;
}

/** Shared markdown renderer for text segments (live and committed). */
export const TextMarkdown: React.FC<TextMarkdownProps> = ({ content }) => {
  const segments = parseContentSegments(content);
  return (
    <div className="prose prose-sm dark:prose-invert max-w-none">
      {segments.map((seg, segIndex) => {
        if (seg.type === 'code') {
          const className = seg.language ? `language-${seg.language}` : undefined;
          return (
            <CodeBlock key={`code-${segIndex}`} className={className} isStreaming={!seg.isComplete}>
              {seg.content.replace(/\n$/, '')}
            </CodeBlock>
          );
        }
        if (!seg.content.trim()) return null;
        return (
          <ReactMarkdown
            key={`text-${segIndex}`}
            remarkPlugins={[remarkGfm]}
            components={markdownCodeComponents}
          >
            {seg.content}
          </ReactMarkdown>
        );
      })}
    </div>
  );
};
