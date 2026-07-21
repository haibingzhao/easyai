import React, { useState } from 'react';
import { X } from 'lucide-react';
import type { TaskSummary, PresetTask } from '@/services/swarm-service';
import { NodeMessageList } from './NodeMessageList';
import { SwarmTeamProgress } from '@/components/swarm/SwarmTeamProgress';
import { SwarmDeliberationProgress } from '@/components/swarm/SwarmDeliberationProgress';
import { i18n } from '@/utils/i18n';

interface NodeDetailPanelProps {
  runId?: string;
  taskId: string;
  task: TaskSummary;
  presetTask?: PresetTask;
  agentMap?: Map<string, string>;
  onClose: () => void;
  hideHeader?: boolean;
}

type TabId = 'config' | 'messages' | 'team' | 'deliberation';

export const NodeDetailPanel: React.FC<NodeDetailPanelProps> = ({
  runId,
  taskId,
  task,
  presetTask,
  agentMap,
  onClose,
  hideHeader = false,
}) => {
  const [activeTab, setActiveTab] = useState<TabId>('config');

  const tabs: { id: TabId; label: string }[] = [
    { id: 'config', label: 'Config' },
    { id: 'messages', label: 'Messages' },
    ...(task.type === 'TEAM' ? [{ id: 'team' as TabId, label: 'Team' }] : []),
    ...(task.type === 'DELIBERATION' ? [{ id: 'deliberation' as TabId, label: 'Deliberation' }] : []),
  ];

  return (
    <div className="w-full border-l border-border flex flex-col flex-1 min-h-0">
      {/* Header */}
      {!hideHeader && (
        <div className="flex items-center justify-between px-3 py-2 border-b border-border">
          <h4 className="text-sm font-medium truncate">
            {task.type === 'DELIBERATION' && presetTask?.deliberation
              ? `${presetTask.deliberation.participants.length} ${i18n('Participants')}`
              : task.type === 'TEAM' && presetTask?.team
                ? presetTask.team.leader
                : task.agentId || task.id}
          </h4>
          <button onClick={onClose} className="p-1 rounded hover:bg-muted">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Tabs */}
      <div className="flex border-b border-border">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={[
              'flex-1 px-3 py-2 text-xs font-medium transition-colors',
              activeTab === tab.id
                ? 'border-b-2 border-primary text-primary'
                : 'text-muted-foreground hover:text-foreground',
            ].join(' ')}
          >
            {i18n(tab.label)}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 min-h-0 overflow-y-auto">
        {activeTab === 'config' && (
          <div className="p-3 space-y-3">
            {/* Basic info */}
            <div>
              <div className="text-xs text-muted-foreground mb-1">{i18n('ID')}</div>
              <div className="text-sm font-mono break-all">{task.id}</div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground mb-1">{i18n('Type')}</div>
              <div className="text-sm">
                <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-xs font-medium ${
                  task.type === 'DELIBERATION'
                    ? 'text-purple-600 bg-purple-50 dark:bg-purple-950'
                    : task.type === 'TEAM'
                      ? 'text-teal-600 bg-teal-50 dark:bg-teal-950'
                      : 'text-blue-600 bg-blue-50 dark:bg-blue-950'
                }`}>
                  {task.type === 'DELIBERATION' ? 'Deliberation' : task.type === 'TEAM' ? 'Team' : 'Single'}
                </span>
              </div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground mb-1">{i18n('Status')}</div>
              <div className="text-sm">{task.status}</div>
            </div>
            {/* Agent / Participants / Leader */}
            {task.type === 'DELIBERATION' && presetTask?.deliberation ? (
              <>
                <div>
                  <div className="text-xs text-muted-foreground mb-1">{i18n('Participants')}</div>
                  <div className="flex flex-wrap gap-1.5">
                    {presetTask.deliberation.participants.map((p) => (
                      <span
                        key={p}
                        className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-violet-50 text-violet-700 dark:bg-violet-950 dark:text-violet-300"
                      >
                        {agentMap?.get(p) || p}
                      </span>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground mb-1">{i18n('Judge')}</div>
                  <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300">
                    {agentMap?.get(presetTask.deliberation.judge) || presetTask.deliberation.judge}
                  </span>
                </div>
              </>
            ) : task.type === 'TEAM' && presetTask?.team ? (
              <>
                <div>
                  <div className="text-xs text-muted-foreground mb-1">{i18n('Leader')}</div>
                  <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-teal-50 text-teal-700 dark:bg-teal-950 dark:text-teal-300">
                    {agentMap?.get(presetTask.team.leader) || presetTask.team.leader}
                  </span>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground mb-1">{i18n('Members')}</div>
                  <div className="flex flex-wrap gap-1.5">
                    {presetTask.team.members.map((m) => (
                      <span
                        key={m}
                        className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300"
                      >
                        {agentMap?.get(m) || m}
                      </span>
                    ))}
                  </div>
                </div>
              </>
            ) : (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Agent')}</div>
                <div className="text-sm">{task.agentId}</div>
              </div>
            )}

            {/* Dependencies from preset config */}
            {presetTask && presetTask.dependsOn.length > 0 && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Dependencies')}</div>
                <div className="text-sm space-y-0.5">
                  {presetTask.dependsOn.map((dep) => (
                    <div key={dep} className="font-mono text-xs truncate">{dep}</div>
                  ))}
                </div>
              </div>
            )}

            {/* Token usage */}
            {(task.inputTokens > 0 || task.outputTokens > 0) && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Tokens')}</div>
                <div className="text-sm">
                  {i18n('Input')}: {task.inputTokens} / {i18n('Output')}: {task.outputTokens}
                </div>
              </div>
            )}

            {/* Worker iterations */}
            {task.workerIterations > 0 && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Iterations')}</div>
                <div className="text-sm">{task.workerIterations}</div>
              </div>
            )}

            {/* Summary */}
            {task.summary && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Summary')}</div>
                <div className="text-sm text-foreground/80 line-clamp-4">{task.summary}</div>
              </div>
            )}

            {/* Error */}
            {task.error && (
              <div>
                <div className="text-xs text-muted-foreground mb-1">{i18n('Error')}</div>
                <div className="text-sm text-red-500 break-words">{task.error}</div>
              </div>
            )}
          </div>
        )}

        {activeTab === 'messages' && (
          runId ? (
            <NodeMessageList runId={runId} taskId={taskId} taskStatus={task.status} />
          ) : (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
              {i18n('No messages available')}
            </div>
          )
        )}

        {activeTab === 'team' && task.type === 'TEAM' && runId && (
          <SwarmTeamProgress runId={runId} taskId={taskId} taskStatus={task.status} />
        )}

        {activeTab === 'deliberation' && task.type === 'DELIBERATION' && runId && (
          <SwarmDeliberationProgress runId={runId} taskId={taskId} taskStatus={task.status} />
        )}
      </div>
    </div>
  );
};
