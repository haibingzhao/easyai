import React from 'react';
import { MemoryPanel } from '@/components/knowledge/MemoryPanel';
import { Brain } from 'lucide-react';
import { i18n } from '@/utils/i18n';

export const MemoriesPage: React.FC = () => (
  <div className="h-full bg-background min-h-0">
    <MemoryPanel
      header={
        <>
          <Brain className="w-4 h-4 text-primary" />
          <h1 className="text-sm font-semibold">{i18n('Memories')}</h1>
        </>
      }
    />
  </div>
);
