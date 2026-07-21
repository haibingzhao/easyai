import React, { useState } from 'react';
import { Plus, Pencil, Trash2, X, Check } from 'lucide-react';
import type { SwarmVariableDto } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';
import { escapeRegex } from '@/utils/format';

interface SwarmVariableEditorProps {
  variables: SwarmVariableDto[];
  onChange: (variables: SwarmVariableDto[]) => void;
  /** Task prompt templates — used to check references before delete */
  taskPrompts: string[];
}

const DEFAULT_VARIABLE: SwarmVariableDto = {
  name: '',
  description: '',
  required: false,
  defaultValue: null,
  updatable: false,
};

export const SwarmVariableEditor: React.FC<SwarmVariableEditorProps> = ({
  variables,
  onChange,
  taskPrompts,
}) => {
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<SwarmVariableDto>({ ...DEFAULT_VARIABLE });
  const [deleteConfirmIndex, setDeleteConfirmIndex] = useState<number | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  const startAdd = () => {
    setEditForm({ ...DEFAULT_VARIABLE });
    setEditingIndex(variables.length);
    setFieldError(null);
  };

  const startEdit = (index: number) => {
    setEditForm({ ...variables[index] });
    setEditingIndex(index);
    setFieldError(null);
  };

  const validateForm = (): boolean => {
    if (!editForm.name.trim()) {
      setFieldError('Name is required');
      return false;
    }
    const existingNames = new Set(variables.map((v, i) => (i !== editingIndex ? v.name : null)));
    if (existingNames.has(editForm.name.trim())) {
      setFieldError(`Variable name '${editForm.name}' already exists`);
      return false;
    }
    return true;
  };

  const confirmEdit = () => {
    if (!validateForm()) return;
    const updated = [...variables];
    const cleaned: SwarmVariableDto = {
      name: editForm.name.trim(),
      description: editForm.description?.trim() || undefined,
      required: editForm.required,
      defaultValue: editForm.defaultValue || null,
      updatable: editForm.updatable,
    };
    if (editingIndex === variables.length) {
      updated.push(cleaned);
    } else if (editingIndex !== null) {
      updated[editingIndex] = cleaned;
    }
    onChange(updated);
    setEditingIndex(null);
    setFieldError(null);
  };

  const cancelEdit = () => {
    setEditingIndex(null);
    setFieldError(null);
  };

  const isVariableReferenced = (name: string): boolean => {
    const pattern = new RegExp(`\\{\\{\\s*${escapeRegex(name)}\\s*\\}\\}`, 'g');
    return taskPrompts.some(p => pattern.test(p));
  };

  const deleteVariable = (index: number) => {
    const variable = variables[index];
    if (deleteConfirmIndex === index) {
      onChange(variables.filter((_, i) => i !== index));
      setDeleteConfirmIndex(null);
      // Adjust editingIndex after removal
      if (editingIndex !== null) {
        if (editingIndex === index) {
          setEditingIndex(null); // deleted the one being edited
        } else if (editingIndex > index) {
          setEditingIndex(editingIndex - 1); // shift down for items above deleted index
        }
      }
    } else if (isVariableReferenced(variable.name)) {
      setDeleteConfirmIndex(index);
    } else {
      onChange(variables.filter((_, i) => i !== index));
      if (editingIndex !== null) {
        if (editingIndex === index) {
          setEditingIndex(null);
        } else if (editingIndex > index) {
          setEditingIndex(editingIndex - 1);
        }
      }
    }
  };

  const renderForm = () => (
    <div className="space-y-3">
      {fieldError && (
        <div className="p-2 rounded bg-destructive/10 text-destructive text-sm">{fieldError}</div>
      )}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Name')} *</label>
          <input
            className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={editForm.name}
            onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
            placeholder="code_diff"
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Description')}</label>
          <input
            className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={editForm.description ?? ''}
            onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
            placeholder="Code diff to review"
          />
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium mb-1">{i18n('Default Value')}</label>
          <input
            className="w-full px-3 py-2 text-sm rounded-md border border-border bg-background focus:outline-none focus:ring-2 focus:ring-primary"
            value={editForm.defaultValue ?? ''}
            onChange={(e) => setEditForm({ ...editForm, defaultValue: e.target.value || null })}
            placeholder="optional"
          />
        </div>
        <div className="flex items-center gap-3 pt-6">
          <label className="text-sm font-medium">{i18n('Required')}</label>
          <button
            type="button"
            role="switch"
            aria-checked={editForm.required}
            onClick={() => setEditForm({ ...editForm, required: !editForm.required })}
            className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full transition-colors ${editForm.required ? 'bg-green-500' : 'bg-muted'}`}
          >
            <span className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow-sm transform transition-transform mt-0.5 ${editForm.required ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
          <label className="text-sm font-medium ml-3">{i18n('Updatable')}</label>
          <button
            type="button"
            role="switch"
            aria-checked={editForm.updatable}
            onClick={() => setEditForm({ ...editForm, updatable: !editForm.updatable })}
            className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full transition-colors ${editForm.updatable ? 'bg-amber-500' : 'bg-muted'}`}
          >
            <span className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow-sm transform transition-transform mt-0.5 ${editForm.updatable ? 'translate-x-4' : 'translate-x-0.5'}`} />
          </button>
        </div>
      </div>
      <div className="flex gap-2">
        <button
          type="button"
          onClick={confirmEdit}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-primary text-primary-foreground hover:bg-primary/90"
        >
          <Check className="w-3.5 h-3.5" />
          {editingIndex === variables.length ? i18n('Add') : i18n('Save')}
        </button>
        <button
          type="button"
          onClick={cancelEdit}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-md bg-muted hover:bg-muted/80"
        >
          <X className="w-3.5 h-3.5" />
          {i18n('Cancel')}
        </button>
      </div>
    </div>
  );

  return (
    <div className="space-y-2">
      {variables.map((v, index) => {
        const isEditing = editingIndex === index;
        const isReferenced = isVariableReferenced(v.name);

        if (isEditing) {
          return (
            <div key={index} className="p-4 rounded-lg border-2 border-primary bg-card">
              {renderForm()}
            </div>
          );
        }

        return (
          <div key={index} className="flex items-center gap-3 p-3 rounded-lg border border-border bg-card">
            <code className="text-sm font-medium text-primary shrink-0">{`{{ ${v.name} }}`}</code>
            {v.description && (
              <span className="text-xs text-muted-foreground truncate">{v.description}</span>
            )}
            {v.required && (
              <span className="text-xs px-1.5 py-0.5 rounded bg-destructive/10 text-destructive shrink-0">{i18n('required')}</span>
            )}
            {v.updatable && (
              <span className="text-xs px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-600 dark:text-amber-400 shrink-0">{i18n('updatable')}</span>
            )}
            {v.defaultValue && (
              <span className="text-xs text-muted-foreground shrink-0">
                {i18n('default')}: <code className="text-foreground/80">{v.defaultValue}</code>
              </span>
            )}
            <div className="flex-1" />
            <button
              type="button"
              onClick={() => startEdit(index)}
              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground shrink-0"
            >
              <Pencil className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              onClick={() => deleteVariable(index)}
              className={`p-1.5 rounded hover:bg-destructive/10 shrink-0 ${deleteConfirmIndex === index ? 'text-destructive' : 'text-muted-foreground hover:text-destructive'}`}
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
            {deleteConfirmIndex === index && isReferenced && (
              <span className="text-xs text-destructive shrink-0">
                {i18n('Referenced in tasks. Click again.')}
              </span>
            )}
          </div>
        );
      })}

      {/* Adding new variable */}
      {editingIndex === variables.length && (
        <div className="p-4 rounded-lg border-2 border-primary bg-card">
          {renderForm()}
        </div>
      )}

      {/* Add Variable button */}
      {editingIndex === null && (
        <button
          type="button"
          onClick={startAdd}
          className="flex items-center gap-2 w-full py-2.5 px-4 rounded-lg border border-dashed border-border text-sm text-muted-foreground hover:text-foreground hover:border-primary/50 transition-colors"
        >
          <Plus className="w-4 h-4" />
          {i18n('Add Variable')}
        </button>
      )}
    </div>
  );
};
