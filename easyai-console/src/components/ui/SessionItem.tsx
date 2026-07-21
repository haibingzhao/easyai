import React, { useState } from 'react';
import { Trash2 } from 'lucide-react';
import type { SessionListItem } from '@/services/session-service';
import { i18n } from '@/utils/i18n';

interface SessionItemProps {
  session: SessionListItem;
  isSelected: boolean;
  showDelete?: boolean;
  onSelect: (id: string) => void;
  onDelete?: (id: string) => void;
}

export const SessionItem: React.FC<SessionItemProps> = ({
  session,
  isSelected,
  showDelete = false,
  onSelect,
  onDelete,
}) => {
  const [isHovered, setIsHovered] = useState(false);

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (onDelete) {
      onDelete(session.id);
    }
  };

  return (
    <div
      className={`flex items-center justify-between p-3 rounded-md border border-border cursor-pointer transition-colors ${
        isSelected ? 'bg-muted' : 'hover:bg-muted'
      }`}
      onClick={() => onSelect(session.id)}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          {session.streaming && (
            <span className="flex items-center gap-1 text-xs font-medium text-primary shrink-0">
              <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
              {i18n('Running')}
            </span>
          )}
          <span className="font-medium truncate">{session.title || i18n('Untitled')}</span>
        </div>
        <div className="text-xs text-muted-foreground">
          {new Date(session.createdAt).toLocaleString()}
        </div>
      </div>
      {showDelete && onDelete && (isHovered || isSelected) && (
        <button
          type="button"
          onClick={handleDelete}
          className="p-2 text-muted-foreground hover:text-destructive transition-colors"
          title={i18n('Delete Session')}
        >
          <Trash2 className="w-4 h-4" />
        </button>
      )}
    </div>
  );
};
