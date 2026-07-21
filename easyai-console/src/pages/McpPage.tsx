import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Server } from 'lucide-react';
import { i18n } from '@/utils/i18n';
import { McpServerManager } from '@/components/agent/McpServerPlaceholder';

export const McpPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-3xl mx-auto p-6">
        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <button
            onClick={() => navigate('/')}
            className="p-1.5 rounded-md hover:bg-muted transition-colors"
            title={i18n('Back')}
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-muted flex items-center justify-center">
              <Server className="w-4 h-4 text-muted-foreground" />
            </div>
            <div>
              <h1 className="text-lg font-semibold">{i18n('MCP')}</h1>
              <p className="text-xs text-muted-foreground">
                {i18n('Manage your MCP server connections, enable or add new tool capabilities.')}
              </p>
            </div>
          </div>
        </div>

        {/* MCP Server Manager */}
        <McpServerManager />
      </div>
    </div>
  );
};

export default McpPage;
