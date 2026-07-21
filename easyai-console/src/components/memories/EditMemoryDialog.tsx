import React, { useState, useEffect } from 'react';
import { Dialog } from '@/components/ui/Dialog';
import { X } from 'lucide-react';
import type { MemoryEntry, CreateMemoryRequest } from '@/types/memory';
import { i18n } from '@/utils/i18n';

const MEMORY_TYPES = ['user', 'feedback', 'project', 'reference'] as const;
const SCOPES = ['global', 'project'] as const;

interface EditMemoryDialogProps {
  open: boolean;
  onClose: () => void;
  onSave: (request: CreateMemoryRequest) => Promise<void>;
  memory?: MemoryEntry | null;
}

export const EditMemoryDialog: React.FC<EditMemoryDialogProps> = ({
  open,
  onClose,
  onSave,
  memory,
}) => {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState<string>('user');
  const [scope, setScope] = useState<string>('global');
  const [content, setContent] = useState('');
  const [keywords, setKeywords] = useState<string[]>([]);
  const [keywordInput, setKeywordInput] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (memory) {
      setName(memory.name);
      setDescription(memory.description);
      setType(memory.type);
      setScope(memory.scope);
      setContent(memory.content);
      setKeywords([...memory.keywords]);
    } else {
      setName('');
      setDescription('');
      setType('user');
      setScope('global');
      setContent('');
      setKeywords([]);
    }
    setKeywordInput('');
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

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      await onSave({
        name: name.trim(),
        description: description.trim(),
        type,
        scope,
        content,
        keywords,
      });
      onClose();
    } catch (e) {
      console.error('Failed to save memory:', e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} title={memory ? i18n('Edit Memory') : i18n('Create Memory')}>
      <div className="space-y-4">
        {/* Name */}
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Title')}</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
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

        {/* Type + Scope row */}
        <div className="flex gap-4">
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Type')}</label>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              {MEMORY_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t.charAt(0).toUpperCase() + t.slice(1)}
                </option>
              ))}
            </select>
          </div>
          <div className="flex-1">
            <label className="block text-sm font-medium mb-1">{i18n('Scope')}</label>
            <select
              value={scope}
              onChange={(e) => setScope(e.target.value)}
              className="w-full px-3 py-2 rounded-md border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-primary"
            >
              {SCOPES.map((s) => (
                <option key={s} value={s}>
                  {s === 'global' ? i18n('Global') : i18n('Current Project')}
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
