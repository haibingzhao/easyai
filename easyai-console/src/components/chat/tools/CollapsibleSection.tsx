/**
 * 可折叠内容区域组件
 */

import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { CollapsibleSectionProps } from './types';

export function CollapsibleSection({ 
  title, 
  children, 
  defaultCollapsed = true,
  className = ''
}: CollapsibleSectionProps) {
  const [isCollapsed, setIsCollapsed] = useState(defaultCollapsed);
  
  return (
    <div className={`border border-border rounded-lg overflow-hidden ${className}`}>
      <button 
        type="button"
        onClick={() => setIsCollapsed(!isCollapsed)}
        className="w-full flex items-center gap-2 p-2 hover:bg-muted/50 text-left"
      >
        {isCollapsed ? (
          <ChevronRight className="w-4 h-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronDown className="w-4 h-4 shrink-0 text-muted-foreground" />
        )}
        <span className="text-sm flex-1 min-w-0 flex items-center">{title}</span>
      </button>
      {!isCollapsed && (
        <div className="border-t border-border">
          {children}
        </div>
      )}
    </div>
  );
}