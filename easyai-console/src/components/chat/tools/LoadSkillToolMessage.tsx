/**
 * LoadSkill tool message renderer component.
 * Compact card: icon + label + skill name + status indicator.
 * Expandable to view skill content rendered as Markdown.
 * - Success: green checkmark
 * - Failure: red alert icon with error details on hover (native tooltip)
 * - Running: pulsing dot
 */

import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Sparkles, CheckCircle2, AlertCircle, ChevronDown } from 'lucide-react';
import type { ToolMessageProps } from './types';
import { extractOutput } from './parsers';
import { markdownCodeComponents } from '../markdownCodeComponents';

/** Extract skill name from tool call args */
function extractSkillName(args: string): string {
  try {
    const parsed = JSON.parse(args);
    if (parsed.name) return parsed.name as string;
  } catch {
    // ignore parse error
  }
  return '';
}

/**
 * Strip the XML wrapper and metadata header from skill output,
 * keeping only the meaningful body content for display.
 */
function extractSkillBody(output: string): string {
  // Remove <skill_content> / </skill_content> tags
  let body = output
    .replace(/<skill_content name="[^"]*">\n?/, '')
    .replace(/\n?<\/skill_content>$/, '');

  // Remove the "# Skill: xxx" title line
  body = body.replace(/^# Skill: .+\n*/, '');

  // Remove metadata lines (Description / Tags)
  body = body.replace(/^\*\*Description\*\*: .+\n*/m, '');
  body = body.replace(/^\*\*Tags\*\*: .+\n*/m, '');

  // Remove leading separator
  body = body.replace(/^---\n*/, '');

  // Remove base directory info and skill_files block
  body = body.replace(/Base directory for this skill: .+\n?/, '');
  body = body.replace(/Relative paths in this skill are relative to this base directory\.\n?/, '');
  body = body.replace(/<skill_files>[\s\S]*?<\/skill_files>\n?/, '');

  return body.trim();
}

export function LoadSkillToolMessage({
  toolCall,
  result,
  status,
  streamingOutput,
}: ToolMessageProps) {
  const [expanded, setExpanded] = useState(false);

  const skillName = extractSkillName(toolCall.args);
  const output = extractOutput({ result, streamingOutput });
  const isStreaming = (status === 'RUNNING' || status === 'PENDING') && !result;
  const isError = (result?.isError ?? false) || status === 'FAILED';

  const skillBody = !isError && output ? extractSkillBody(output) : '';
  const expandable = !isError && !!skillBody;

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Header row */}
      <div
        className={`px-3 py-2 flex items-center gap-2 transition-colors ${expandable ? 'cursor-pointer hover:bg-muted/50' : ''}`}
        onClick={expandable ? () => setExpanded(!expanded) : undefined}
      >
        <Sparkles className="size-4 shrink-0 text-violet-500 dark:text-violet-400" />
        <span className="text-sm font-medium shrink-0">Load Skill</span>
        <span
          className="text-sm text-violet-600 dark:text-violet-400 font-medium truncate min-w-0 flex-1"
          title={skillName || undefined}
        >
          {skillName || toolCall.args}
        </span>
        {/* Status indicator */}
        {isStreaming ? (
          <span className="size-2 shrink-0 rounded-full bg-muted-foreground animate-pulse" />
        ) : isError ? (
          <span className="shrink-0 cursor-help" title={output || 'Failed'}>
            <AlertCircle className="size-4 text-destructive" />
          </span>
        ) : (
          <CheckCircle2 className="size-4 shrink-0 text-green-500" />
        )}
        {expandable && (
          <ChevronDown
            className={`size-4 shrink-0 text-muted-foreground transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`}
          />
        )}
      </div>

      {/* Expanded: skill content as Markdown */}
      {expanded && skillBody && (
        <>
          <div className="border-t border-border" />
          <div className="p-3 max-h-[24em] overflow-y-auto">
            <div className="prose prose-xs dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownCodeComponents}>
                {skillBody}
              </ReactMarkdown>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
