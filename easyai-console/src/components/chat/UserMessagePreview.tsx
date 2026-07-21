import React, { useState } from 'react';
import { i18n } from '../../utils/i18n';

interface UserMessagePreviewProps {
  content: string | null;
}

/** Parse message content and render command prefix (e.g. /goal) as a styled chip */
function renderPreviewContent(content: string) {
  const match = content.match(/^(\/[a-zA-Z_]\w*)([\s\S]*)$/);
  if (!match) return content;

  const cmdToken = match[1];
  const rest = match[2];

  return (
    <>
      <span className="command-chip">{cmdToken}</span>
      {rest}
    </>
  );
}

/**
 * Renders a preview of the currently visible user message in the toolbar.
 * - Max 2 lines with ellipsis truncation by default
 * - Double-click to expand/collapse full text
 * - Returns null when content is empty or null
 */
export const UserMessagePreview: React.FC<UserMessagePreviewProps> = ({ content }) => {
  const [expanded, setExpanded] = useState(false);

  if (!content || !content.trim()) return null;

  return (
    <div
      className={`text-sm text-muted-foreground cursor-default select-none mr-auto transition-colors ${
        expanded ? '' : 'line-clamp-2'
      }`}
      onDoubleClick={(e) => {
        e.stopPropagation();
        setExpanded((prev) => !prev);
      }}
      title={i18n('Double-click to expand/collapse')}
      style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}
    >
      {renderPreviewContent(content)}
    </div>
  );
};
