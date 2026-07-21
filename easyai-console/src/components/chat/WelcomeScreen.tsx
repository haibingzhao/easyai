import React from 'react';
import { MessageSquare } from 'lucide-react';
import { i18n } from '../../utils/i18n';

export const WelcomeScreen: React.FC = () => {
  return (
    <div className="flex items-center justify-center h-full">
      <div className="text-center py-6">
        <div className="inline-flex items-center justify-center w-16 h-16 rounded-lg bg-primary/10 mb-4">
          <MessageSquare className="w-8 h-8 text-primary" />
        </div>
        <h2 className="text-xl font-semibold mb-2">{i18n('Collaborate with Agent')}</h2>
        <p className="text-sm text-muted-foreground">{i18n('End-to-end dev tasks with MCP')}</p>
      </div>
    </div>
  );
};
