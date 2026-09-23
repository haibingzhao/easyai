import React, { useEffect, useState } from 'react';
import { CommandService } from '@/services/command-service';
import { useProjectStore } from '@/services/stores/project-store';
import { useAuthStore } from '@/services/stores/auth-store';
import type { SlashCommand } from '@/types/command';
import { Zap, Loader2 } from 'lucide-react';

interface CommandSelectorProps {
  selectedCommands: string[];
  onChange: (commands: string[]) => void;
  disabled?: boolean;
}

export const CommandSelector: React.FC<CommandSelectorProps> = ({ selectedCommands, onChange, disabled }) => {
  const projectId = useProjectStore((state) => state.currentProject?.id);
  const userId = useAuthStore((state) => state.user?.id);
  const scopeKey = JSON.stringify([userId, projectId]);
  const [snapshot, setSnapshot] = useState<{ scopeKey: string; commands: SlashCommand[] } | null>(null);
  const commands = snapshot?.scopeKey === scopeKey ? snapshot.commands : [];
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    CommandService.fetchCommands(null, projectId, controller.signal)
      .then((cmds) => { if (!controller.signal.aborted) setSnapshot({ scopeKey, commands: cmds.filter(c => c.category === 'BUILTIN' || c.category === 'USER') }); })
      .catch(() => { if (!controller.signal.aborted) setSnapshot({ scopeKey, commands: [] }); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [projectId, scopeKey]);

  const toggleCommand = (cmdName: string) => {
    if (selectedCommands.includes(cmdName)) {
      onChange(selectedCommands.filter(c => c !== cmdName));
    } else {
      onChange([...selectedCommands, cmdName]);
    }
  };

  return (
    <div className="space-y-3">
      <div>
        <label className="text-sm font-medium">Commands</label>
        <p className="text-xs text-muted-foreground mt-1">
          Select commands this agent can use.
        </p>
      </div>

      {loading ? (
        <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" />
          Loading commands...
        </div>
      ) : commands.length === 0 ? (
        <p className="text-xs text-muted-foreground italic">No commands available.</p>
      ) : (
        <div className="space-y-2">
          {commands.map((cmd) => {
            const isSelected = selectedCommands.includes(cmd.name);
            return (
              <label
                key={cmd.name}
                className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                  disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'
                } ${
                  isSelected
                    ? 'border-primary bg-primary/5'
                    : disabled ? 'border-border' : 'border-border hover:border-muted-foreground'
                }`}
              >
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => toggleCommand(cmd.name)}
                  disabled={disabled}
                  className="w-4 h-4 rounded border-input text-primary focus:ring-primary"
                />
                <span className="flex-shrink-0 text-muted-foreground">
                  <Zap className="w-4 h-4" />
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium">{cmd.name}</div>
                  {cmd.description && (
                    <div className="text-xs text-muted-foreground">{cmd.description}</div>
                  )}
                </div>
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
};
