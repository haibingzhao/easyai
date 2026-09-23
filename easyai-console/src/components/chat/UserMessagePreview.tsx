import React, { useState } from 'react';
import { i18n } from '../../utils/i18n';
import { UserMessageContent } from './UserMessage';

interface UserMessagePreviewProps {
  content: string | null;
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
      <UserMessageContent content={content} />
    </div>
  );
};
