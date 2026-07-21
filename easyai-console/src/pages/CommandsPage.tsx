import React, { useEffect, useState, useMemo } from 'react';
import { useCommandStore } from '@/services/stores/command-store';
import { Dialog } from '@/components/ui/Dialog';
import { Plus, Pencil, Trash2, Terminal, X } from 'lucide-react';
import type { UserCommand, UserCommandCreateRequest } from '@/types/command';
import { i18n } from '@/utils/i18n';

export const CommandsPage: React.FC = () => {
  const { commands, loading, error, clearError, loadCommands, createCommand, updateCommand, deleteCommand } = useCommandStore();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editingCommand, setEditingCommand] = useState<UserCommand | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  // Form state
  const [formName, setFormName] = useState('');
  const [formDescription, setFormDescription] = useState('');
  const [formAliases, setFormAliases] = useState('');
  const [formTemplate, setFormTemplate] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    loadCommands();
  }, [loadCommands]);

  const filteredCommands = useMemo(() => {
    if (!searchQuery.trim()) return commands;
    const q = searchQuery.toLowerCase();
    return commands.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.description?.toLowerCase().includes(q) ||
        c.aliases.some((a) => a.toLowerCase().includes(q))
    );
  }, [commands, searchQuery]);

  const openCreateDialog = () => {
    setEditingCommand(null);
    setFormName('');
    setFormDescription('');
    setFormAliases('');
    setFormTemplate('');
    setFormError(null);
    setDialogOpen(true);
  };

  const openEditDialog = (cmd: UserCommand) => {
    setEditingCommand(cmd);
    setFormName(cmd.name);
    setFormDescription(cmd.description || '');
    setFormAliases(cmd.aliases.join(', '));
    setFormTemplate(cmd.template);
    setFormError(null);
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!formName.trim()) {
      setFormError(i18n('Command name is required'));
      return;
    }
    if (!/^[a-zA-Z0-9_-]+$/.test(formName.trim())) {
      setFormError(i18n('Command name can only contain letters, numbers, hyphens and underscores'));
      return;
    }

    setSaving(true);
    setFormError(null);
    const request: UserCommandCreateRequest = {
      name: formName.trim(),
      description: formDescription.trim() || undefined,
      aliases: formAliases
        .split(',')
        .map((a) => a.trim())
        .filter(Boolean),
      template: formTemplate,
    };

    try {
      if (editingCommand) {
        await updateCommand(editingCommand.id, request);
      } else {
        await createCommand(request);
      }
      setDialogOpen(false);
    } catch (err) {
      setFormError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteCommand(id);
    } catch {
      // error in store
    }
    setConfirmDeleteId(null);
  };

  return (
    <div className="flex flex-col h-full bg-background">
      <div className="max-w-3xl mx-auto w-full p-6">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold">{i18n('Commands')}</h1>
            <p className="text-sm text-muted-foreground mt-1">{i18n('Manage your custom slash commands')}</p>
          </div>
          <button
            onClick={openCreateDialog}
            className="flex items-center gap-2 px-4 py-2 bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors text-sm"
          >
            <Plus className="w-4 h-4" />
            {i18n('New Command')}
          </button>
        </div>

        {/* Error banner */}
        {error && (
          <div className="mb-4 p-3 bg-destructive/10 text-destructive rounded-md text-sm flex items-center justify-between">
            <span>{error}</span>
            <button onClick={clearError}><X className="w-4 h-4" /></button>
          </div>
        )}

        {/* Search */}
        <div className="mb-4">
          <input
            type="text"
            placeholder={i18n('Search commands...')}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none"
          />
        </div>

        {/* Loading */}
        {loading && (
          <div className="text-center py-8 text-muted-foreground">{i18n('Loading...')}</div>
        )}

        {/* Empty state */}
        {!loading && filteredCommands.length === 0 && (
          <div className="text-center py-12">
            <Terminal className="w-12 h-12 mx-auto text-muted-foreground mb-3" />
            <p className="text-muted-foreground">
              {commands.length === 0
                ? i18n('No commands yet. Create your first custom command.')
                : i18n('No commands match your search.')}
            </p>
          </div>
        )}

        {/* Command list */}
        {!loading && filteredCommands.length > 0 && (
          <div className="space-y-2">
            {filteredCommands.map((cmd) => (
              <div
                key={cmd.id}
                className="rounded-lg border border-border bg-card p-4 hover:bg-accent/50 transition-colors"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="font-mono text-sm font-semibold text-primary">/{cmd.name}</span>
                      {cmd.aliases.length > 0 && (
                        <span className="text-xs text-muted-foreground">
                          ({cmd.aliases.map((a) => `/${a}`).join(', ')})
                        </span>
                      )}
                    </div>
                    {cmd.description && (
                      <p className="text-sm text-muted-foreground mb-1">{cmd.description}</p>
                    )}
                    {cmd.template && (
                      <p className="text-xs text-muted-foreground/70 font-mono truncate max-w-lg">
                        {cmd.template.split('\n')[0]}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    <button
                      onClick={() => openEditDialog(cmd)}
                      className="p-1.5 rounded-md hover:bg-muted transition-colors text-muted-foreground hover:text-foreground"
                      title={i18n('Edit')}
                    >
                      <Pencil className="w-4 h-4" />
                    </button>
                    {confirmDeleteId === cmd.id ? (
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => handleDelete(cmd.id)}
                          className="px-2 py-1 text-xs bg-destructive text-destructive-foreground rounded hover:bg-destructive/90"
                        >
                          {i18n('Confirm')}
                        </button>
                        <button
                          onClick={() => setConfirmDeleteId(null)}
                          className="px-2 py-1 text-xs rounded hover:bg-muted"
                        >
                          {i18n('Cancel')}
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => setConfirmDeleteId(cmd.id)}
                        className="p-1.5 rounded-md hover:bg-destructive/10 transition-colors text-muted-foreground hover:text-destructive"
                        title={i18n('Delete')}
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Create/Edit Dialog */}
      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        title={editingCommand ? i18n('Edit Command') : i18n('New Command')}
      >
        <div className="space-y-4">
          {formError && (
            <div className="p-3 bg-destructive/10 text-destructive rounded-md text-sm">{formError}</div>
          )}

          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Command Name')}</label>
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground font-mono">/</span>
              <input
                type="text"
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder="my-command"
                className="flex-1 px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none font-mono"
                disabled={!!editingCommand}
              />
            </div>
            <p className="text-xs text-muted-foreground mt-1">{i18n('Letters, numbers, hyphens and underscores only')}</p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Description')}</label>
            <input
              type="text"
              value={formDescription}
              onChange={(e) => setFormDescription(e.target.value)}
              placeholder={i18n('Short description shown in autocomplete')}
              className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none"
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Aliases')}</label>
            <input
              type="text"
              value={formAliases}
              onChange={(e) => setFormAliases(e.target.value)}
              placeholder="alias1, alias2"
              className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none font-mono"
            />
            <p className="text-xs text-muted-foreground mt-1">{i18n('Comma-separated alternative names')}</p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">{i18n('Template')}</label>
            <textarea
              value={formTemplate}
              onChange={(e) => setFormTemplate(e.target.value)}
              placeholder={i18n('Prompt template. Use $1, $2, $ARGUMENTS for placeholders.')}
              rows={8}
              className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm focus:ring-2 focus:ring-ring focus:outline-none font-mono resize-y"
            />
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button
              onClick={() => setDialogOpen(false)}
              className="px-4 py-2 text-sm rounded-md hover:bg-muted transition-colors"
            >
              {i18n('Cancel')}
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {saving ? i18n('Saving...') : i18n('Save')}
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
};
