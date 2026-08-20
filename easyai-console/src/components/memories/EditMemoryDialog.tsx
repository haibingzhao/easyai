import React, { useState, useEffect } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { X } from 'lucide-react';
import type { MemoryEntry, CreateMemoryRequest, UpdateMemoryRequest } from '@/types/memory';
import { maturityLabelKey } from '@/types/memory';
import { useCategoryStore } from '@/services/stores/category-store';
import { i18n } from '@/utils/i18n';

const SCOPES = ['global', 'project'] as const;
const MATURITY_NONE = 'none';
const MATURITY_OPTIONS = [MATURITY_NONE, 'low', 'medium', 'high'] as const;

interface EditMemoryDialogProps {
  open: boolean;
  onClose: () => void;
  onSave: (request: CreateMemoryRequest) => Promise<void>;
  onUpdate: (
    name: string,
    scope: 'global' | 'project',
    projectPath: string | null,
    request: UpdateMemoryRequest
  ) => Promise<void>;
  memory?: MemoryEntry | null;
  /** Current project path, used as projectPath when creating with scope=project. */
  currentProjectPath?: string | null;
}

export const EditMemoryDialog: React.FC<EditMemoryDialogProps> = ({
  open,
  onClose,
  onSave,
  onUpdate,
  memory,
  currentProjectPath,
}) => {
  const memoryCategories = useCategoryStore((s) => s.memoryCategories);
  const isEdit = !!memory;

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<string>(memoryCategories[0]?.code ?? '');
  const [scope, setScope] = useState<string>('global');
  const [content, setContent] = useState('');
  const [keywords, setKeywords] = useState<string[]>([]);
  const [keywordInput, setKeywordInput] = useState('');
  const [maturity, setMaturity] = useState<string>('low');
  const [scenarios, setScenarios] = useState<string[]>([]);
  const [scenarioInput, setScenarioInput] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (memory) {
      setName(memory.name);
      setDescription(memory.description);
      setType(memory.type);
      setScope(memory.scope);
      setContent(memory.content);
      setKeywords([...memory.keywords]);
      setMaturity(memory.maturity ?? MATURITY_NONE);
      setScenarios([...memory.scenarios]);
    } else {
      setName('');
      setDescription('');
      setType(memoryCategories[0]?.code ?? '');
      setScope('global');
      setContent('');
      setKeywords([]);
      setMaturity('low');
      setScenarios([]);
    }
    setKeywordInput('');
    setScenarioInput('');
  }, [memory, open]);

  const handleAddKeyword = () => {
    const trimmed = keywordInput.trim();
    if (trimmed && !keywords.includes(trimmed)) {
      setKeywords([...keywords, trimmed]);
    }
    setKeywordInput('');
  };

  const handleKeywordKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      handleAddKeyword();
    }
  };

  const handleRemoveKeyword = (kw: string) => {
    setKeywords(keywords.filter((k) => k !== kw));
  };

  const handleAddScenario = () => {
    const trimmed = scenarioInput.trim();
    if (trimmed && !scenarios.includes(trimmed)) {
      setScenarios([...scenarios, trimmed]);
    }
    setScenarioInput('');
  };

  const handleScenarioKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      handleAddScenario();
    }
  };

  const handleRemoveScenario = (sc: string) => {
    setScenarios(scenarios.filter((s) => s !== sc));
  };

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      const maturityValue = maturity === MATURITY_NONE ? null : maturity;
      if (isEdit && memory) {
        await onUpdate(memory.name, memory.scope as 'global' | 'project', memory.projectPath ?? null, {
          description: description.trim(),
          content,
          keywords,
          maturity: maturityValue ?? undefined,
          scenarios,
        });
      } else {
        await onSave({
          name: name.trim(),
          description: description.trim(),
          type,
          scope,
          content,
          keywords,
          maturity: maturityValue,
          scenarios,
          projectPath: scope === 'project' ? (currentProjectPath ?? null) : null,
        });
      }
      onClose();
    } catch (e) {
      console.error('Failed to save memory:', e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={isEdit ? i18n('Edit Memory') : i18n('Create Memory')}>
      <div className="space-y-4">
        {/* Name */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Title')}</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={isEdit}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-50"
            placeholder="Memory title"
          />
        </div>

        {/* Description */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Description (optional)')}</label>
          <input
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            placeholder="Brief description"
          />
        </div>

        {/* Type + Scope + Maturity row */}
        <div className="flex gap-4">
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Type')}</label>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              disabled={isEdit}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-50"
            >
              {memoryCategories.map((c) => (
                <option key={c.code} value={c.code}>
                  {i18n(c.labelKey)}
                </option>
              ))}
            </select>
          </div>
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Scope')}</label>
            <select
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              disabled={isEdit}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-50"
            >
              {SCOPES.map((s) => (
                <option key={s} value={s}>
                  {s === 'global' ? i18n('Global') : i18n('Current Project')}
                </option>
              ))}
            </select>
          </div>
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Maturity')}</label>
            <select
              value={maturity}
              onChange={(e) => setMaturity(e.target.value)}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              {MATURITY_OPTIONS.map((m) => (
                <option key={m} value={m}>
                  {m === MATURITY_NONE ? i18n('None') : i18n(maturityLabelKey(m))}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Keywords */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Keywords')}</label>
          <div className="flex flex-wrap gap-1.5 mb-2">
            {keywords.map((kw) => (
              <span
                key={kw}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs bg-muted border border-border"
              >
                {kw}
                <button
                  onClick={() => handleRemoveKeyword(kw)}
                  className="hover:text-destructive"
                >
                  <X className="w-3 h-3" />
                </button>
              </span>
            ))}
          </div>
          <input
            type="text"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onKeyDown={handleKeywordKeyDown}
            onBlur={handleAddKeyword}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            placeholder={i18n('Add separator comma')}
          />
        </div>

        {/* Scenarios */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Scenarios')}</label>
          <div className="flex flex-wrap gap-1.5 mb-2">
            {scenarios.map((sc) => (
              <span
                key={sc}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs bg-primary/5 border border-primary/20 text-primary"
              >
                {sc}
                <button
                  onClick={() => handleRemoveScenario(sc)}
                  className="hover:text-destructive"
                >
                  <X className="w-3 h-3" />
                </button>
              </span>
            ))}
          </div>
          <input
            type="text"
            value={scenarioInput}
            onChange={(e) => setScenarioInput(e.target.value)}
            onKeyDown={handleScenarioKeyDown}
            onBlur={handleAddScenario}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            placeholder={i18n('Add separator comma')}
          />
        </div>

        {/* Content */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Content')}</label>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            rows={6}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary resize-y"
            placeholder="Memory content..."
          />
        </div>

        {/* Actions */}
        <div className="flex justify-end gap-2 pt-2">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-md text-sm border border-border hover:bg-muted transition-colors"
          >
            {i18n('Cancel')}
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !name.trim()}
            className="px-4 py-2 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? i18n('Saving...') : i18n('Save')}
          </button>
        </div>
      </div>
    </Dialog>
  );
};
