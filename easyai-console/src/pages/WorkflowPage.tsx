import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { GitBranch, Loader2, Sparkles, Pencil, Trash2, Download, Upload } from 'lucide-react';
import { useSwarmStore } from '@/services/stores/swarm-store';
import { swarmService } from '@/services/swarm-service';
import type { PresetRequest } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { SwarmImportDialog } from '@/components/swarm/SwarmImportDialog';

export const WorkflowPage: React.FC = () => {
  const navigate = useNavigate();
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);
  const [importError, setImportError] = useState<string | null>(null);
  const [importDialogPreset, setImportDialogPreset] = useState<PresetRequest | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { presets, loading, loadPresets, deletePreset, swarmEnabled } = useSwarmStore();

  useEffect(() => {
    loadPresets();
  }, [loadPresets]);

  const handleExport = async (e: React.MouseEvent, name: string) => {
    e.stopPropagation();
    try {
      await swarmService.exportPreset(name);
    } catch {
      // ignore
    }
  };

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImportError(null);
    try {
      const preset = await swarmService.parsePresetFile(file);
      setImportDialogPreset(preset);
    } catch (err) {
      setImportError(err instanceof Error ? err.message : 'Import failed');
    }
    // Reset input so the same file can be selected again
    e.target.value = '';
  };

  if (loading) {
    return (
      <div className="w-full h-full flex items-center justify-center">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (!swarmEnabled && presets.length > 0) {
    // Swarm module is truly disabled on the backend (but presets exist)
    return (
      <div className="w-full h-full flex flex-col items-center justify-center gap-3">
        <GitBranch className="w-10 h-10 text-muted-foreground" />
        <p className="text-muted-foreground">{i18n('Swarm Not Enabled')}</p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto p-6">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-lg font-semibold">{i18n('Select Preset')}</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={handleImportClick}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md border border-border text-foreground hover:bg-muted transition-colors"
          >
            <Upload className="w-3.5 h-3.5" />
            {i18n('Import')}
          </button>
          <button
            onClick={() => navigate('/workflow/create')}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Sparkles className="w-3.5 h-3.5" />
            {i18n('Create Preset')}
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,.swarm.json"
            className="hidden"
            onChange={handleImportFile}
          />
        </div>
      </div>

      {importError && (
        <div className="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
          {importError}
        </div>
      )}

      {presets.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-3 py-16 text-muted-foreground">
          <GitBranch className="w-10 h-10" />
          <p>{i18n('No presets available')}</p>
          <p className="text-xs">{i18n('Click "Create Preset" to get started, or use AI to generate one.')}</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {presets.map((preset) => (
            <div
              key={preset.name}
              role="button"
              tabIndex={0}
              className="relative text-left p-4 rounded-lg border border-border bg-card hover:shadow-md hover:border-primary/40 transition-all cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary"
              onClick={() => navigate(`/workflow/${encodeURIComponent(preset.name)}/run`)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigate(`/workflow/${encodeURIComponent(preset.name)}/run`); }}
            >
              <div className="flex items-center gap-2 mb-2">
                <GitBranch className="w-4 h-4 text-muted-foreground shrink-0" />
                <span className="font-medium text-sm truncate">{preset.title}</span>
                <div className="flex-1" />
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); navigate(`/workflow/edit/${encodeURIComponent(preset.name)}`); }}
                  className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                >
                  <Pencil className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  onClick={(e) => handleExport(e, preset.name)}
                  className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground"
                  title={i18n('Export')}
                >
                  <Download className="w-3.5 h-3.5" />
                </button>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    if (deleteConfirm === preset.name) {
                      deletePreset(preset.name).then(() => setDeleteConfirm(null));
                    } else {
                      setDeleteConfirm(preset.name);
                    }
                  }}
                  className={`p-1.5 rounded hover:bg-destructive/10 ${deleteConfirm === preset.name ? 'text-destructive' : 'text-muted-foreground hover:text-destructive'}`}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
              <p className="text-xs text-muted-foreground line-clamp-2">
                {preset.description || preset.name}
              </p>
              <div className="flex gap-3 mt-3 text-xs text-muted-foreground">
                <span>{preset.tasks.length} tasks</span>
                <span>{preset.agents.length} agents</span>
                {preset.variables.length > 0 && (
                  <span>{preset.variables.length} variables</span>
                )}
              </div>
              {deleteConfirm === preset.name && (
                <div className="mt-2 text-xs text-destructive">
                  {i18n('Click delete again to confirm')}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Import preview dialog */}
      {importDialogPreset && (
        <SwarmImportDialog
          preset={importDialogPreset}
          onClose={() => setImportDialogPreset(null)}
          onImported={() => {
            setImportDialogPreset(null);
            loadPresets();
          }}
        />
      )}
    </div>
  );
};
