import React, { useState, useEffect, useCallback } from 'react';
import { Braces, AlertCircle, Plus, Trash2, LayoutGrid, Code } from 'lucide-react';

type EditorMode = 'visual' | 'schema';

type FieldType = 'string' | 'number' | 'integer' | 'boolean';

interface KVEntry {
  key: string;
  type: FieldType;
  required: boolean;
  description: string;
}

interface SchemaEditorProps {
  enabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
  schema: string;
  onSchemaChange: (schema: string) => void;
  disabled?: boolean;
  title?: string;
  description?: string;
}

const FIELD_TYPES: FieldType[] = ['string', 'number', 'integer', 'boolean'];

const PRESET_TEMPLATES: { label: string; schema: string }[] = [
  {
    label: 'Key-Value Object',
    schema: JSON.stringify(
      {
        type: 'object',
        properties: {
          key: { type: 'string' },
          value: { type: 'string' },
        },
        required: ['key', 'value'],
      },
      null,
      2,
    ),
  },
];

/**
 * Check if a parsed JSON Schema is a flat one-level object
 * (all properties are primitive types, no nested objects/arrays with items).
 */
function isFlatSchema(parsed: Record<string, unknown>): boolean {
  if (parsed.type !== 'object' || typeof parsed.properties !== 'object' || parsed.properties === null) {
    return false;
  }
  const props = parsed.properties as Record<string, Record<string, unknown>>;
  for (const prop of Object.values(props)) {
    if (typeof prop !== 'object' || prop === null) return false;
    const t = prop.type;
    if (t === 'object' || t === 'array') return false;
    // Has nested properties or items → multi-level
    if ('properties' in prop || 'items' in prop) return false;
  }
  return true;
}

/**
 * Parse a flat JSON Schema into KVEntry[].
 * Returns null if not flat.
 */
function schemaToEntries(parsed: Record<string, unknown>): KVEntry[] | null {
  if (!isFlatSchema(parsed)) return null;
  const props = parsed.properties as Record<string, Record<string, unknown>>;
  const required = Array.isArray(parsed.required) ? (parsed.required as string[]) : [];
  const entries: KVEntry[] = [];
  for (const [key, prop] of Object.entries(props)) {
    const type = (prop.type as string) as FieldType;
    if (!FIELD_TYPES.includes(type)) return null;
    entries.push({
      key,
      type,
      required: required.includes(key),
      description: typeof prop.description === 'string' ? prop.description : '',
    });
  }
  return entries;
}

/**
 * Build a JSON Schema string from KVEntry[].
 */
function entriesToSchema(entries: KVEntry[]): string {
  const properties: Record<string, Record<string, string>> = {};
  const required: string[] = [];
  for (const entry of entries) {
    if (!entry.key.trim()) continue;
    const prop: Record<string, string> = { type: entry.type };
    if (entry.description.trim()) prop.description = entry.description.trim();
    properties[entry.key.trim()] = prop;
    if (entry.required) required.push(entry.key.trim());
  }
  const schema: Record<string, unknown> = { type: 'object', properties };
  if (required.length > 0) schema.required = required;
  return JSON.stringify(schema, null, 2);
}

export const SchemaEditor: React.FC<SchemaEditorProps> = ({
  enabled,
  onEnabledChange,
  schema,
  onSchemaChange,
  disabled = false,
  title = 'Output Schema',
  description = 'Define a JSON Schema to enforce structured output from the agent.',
}) => {
  const [mode, setMode] = useState<EditorMode>('visual');
  const [entries, setEntries] = useState<KVEntry[]>([]);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [switchWarning, setSwitchWarning] = useState<string | null>(null);

  // Sync entries from external schema prop when it changes (e.g. edit mode load)
  useEffect(() => {
    if (!schema.trim()) {
      setEntries([]);
      return;
    }
    try {
      const parsed = JSON.parse(schema);
      const flat = schemaToEntries(parsed);
      if (flat) {
        setEntries(flat);
        setMode('visual');
      } else {
        setMode('schema');
      }
    } catch {
      // Invalid JSON — stay in current mode
    }
  }, []); // Only run on mount

  // Validate schema text
  useEffect(() => {
    if (!schema.trim()) {
      setValidationError(null);
      return;
    }
    try {
      JSON.parse(schema);
      setValidationError(null);
    } catch (e) {
      setValidationError((e as Error).message);
    }
  }, [schema]);

  const updateEntries = useCallback(
    (next: KVEntry[]) => {
      setEntries(next);
      if (mode === 'visual') {
        onSchemaChange(entriesToSchema(next));
      }
    },
    [mode, onSchemaChange],
  );

  const handleAddEntry = () => {
    updateEntries([...entries, { key: '', type: 'string', required: true, description: '' }]);
  };

  const handleRemoveEntry = (index: number) => {
    updateEntries(entries.filter((_, i) => i !== index));
  };

  const handleEntryChange = (index: number, field: Partial<KVEntry>) => {
    updateEntries(entries.map((e, i) => (i === index ? { ...e, ...field } : e)));
  };

  const handleSwitchMode = (target: EditorMode) => {
    if (target === mode) return;
    setSwitchWarning(null);

    if (target === 'schema') {
      // Visual → Schema: auto-generate from entries
      onSchemaChange(entriesToSchema(entries));
      setMode('schema');
    } else {
      // Schema → Visual: check if flat
      if (!schema.trim()) {
        setEntries([]);
        setMode('visual');
        return;
      }
      try {
        const parsed = JSON.parse(schema);
        const flat = schemaToEntries(parsed);
        if (flat) {
          setEntries(flat);
          setMode('visual');
        } else {
          setSwitchWarning('Multi-level structures only support Schema mode.');
        }
      } catch {
        setSwitchWarning('Invalid JSON — fix errors before switching to Visual mode.');
      }
    }
  };

  return (
    <div className="space-y-4">
      {/* Toggle */}
      <div className="flex items-center justify-between">
        <div>
          <label className="text-sm font-medium">{title}</label>
          <p className="text-xs text-muted-foreground">{description}</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          onClick={() => onEnabledChange(!enabled)}
          disabled={disabled}
          className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 ${
            enabled ? '' : 'bg-muted'
          } ${disabled ? 'opacity-60 cursor-not-allowed' : ''}`}
          style={enabled ? { backgroundColor: '#22c55e' } : undefined}
        >
          <span
            className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-background shadow ring-0 transition duration-200 ease-in-out ${
              enabled ? 'translate-x-5' : 'translate-x-0'
            }`}
          />
        </button>
      </div>

      {/* Editor body */}
      {enabled && (
        <div className="space-y-3">
          {/* Mode tabs + presets */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1 p-0.5 rounded-md bg-muted">
              <button
                type="button"
                onClick={() => handleSwitchMode('visual')}
                disabled={disabled}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded transition-colors ${
                  mode === 'visual'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                } disabled:opacity-60 disabled:cursor-not-allowed`}
              >
                <LayoutGrid className="w-3.5 h-3.5" />
                Visual
              </button>
              <button
                type="button"
                onClick={() => handleSwitchMode('schema')}
                disabled={disabled}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded transition-colors ${
                  mode === 'schema'
                    ? 'bg-background text-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground'
                } disabled:opacity-60 disabled:cursor-not-allowed`}
              >
                <Code className="w-3.5 h-3.5" />
                Schema
              </button>
            </div>

            {mode === 'schema' && (
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs text-muted-foreground">Templates:</span>
                {PRESET_TEMPLATES.map((tpl) => (
                  <button
                    key={tpl.label}
                    type="button"
                    onClick={() => onSchemaChange(tpl.schema)}
                    disabled={disabled}
                    className="px-2.5 py-1 text-xs rounded-md bg-muted hover:bg-muted/80 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                  >
                    {tpl.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Switch warning */}
          {switchWarning && (
            <div className="flex items-start gap-2 p-3 rounded-md bg-yellow-500/10 text-yellow-600 dark:text-yellow-400 text-xs">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span>{switchWarning}</span>
            </div>
          )}

          {/* Visual mode */}
          {mode === 'visual' && (
            <div className="space-y-2">
              {entries.length > 0 && (
                <div className="space-y-1.5">
                  {entries.map((entry, i) => (
                    <div key={i} className="space-y-1">
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          value={entry.key}
                          onChange={(e) => handleEntryChange(i, { key: e.target.value })}
                          placeholder="Field name"
                          disabled={disabled}
                          className="flex-1 px-2.5 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                        />
                        <select
                          value={entry.type}
                          onChange={(e) => handleEntryChange(i, { type: e.target.value as FieldType })}
                          disabled={disabled}
                          className="w-24 px-2 py-1.5 rounded-md border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                        >
                          {FIELD_TYPES.map((t) => (
                            <option key={t} value={t}>
                              {t}
                            </option>
                          ))}
                        </select>
                        <label className="flex items-center gap-1 text-xs text-muted-foreground whitespace-nowrap select-none">
                          <input
                            type="checkbox"
                            checked={entry.required}
                            onChange={(e) => handleEntryChange(i, { required: e.target.checked })}
                            disabled={disabled}
                            className="rounded border-input"
                          />
                          Required
                        </label>
                        <button
                          type="button"
                          onClick={() => handleRemoveEntry(i)}
                          disabled={disabled}
                          className="p-1.5 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                      <input
                        type="text"
                        value={entry.description}
                        onChange={(e) => handleEntryChange(i, { description: e.target.value })}
                        placeholder="Description (optional)"
                        disabled={disabled}
                        className="w-full px-2.5 py-1.5 rounded-md border border-input bg-background text-xs text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
                      />
                    </div>
                  ))}
                </div>
              )}
              <button
                type="button"
                onClick={handleAddEntry}
                disabled={disabled}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-md bg-muted hover:bg-muted/80 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
              >
                <Plus className="w-3.5 h-3.5" />
                Add Field
              </button>
            </div>
          )}

          {/* Schema mode */}
          {mode === 'schema' && (
            <div className="relative">
              <textarea
                value={schema}
                onChange={(e) => onSchemaChange(e.target.value)}
                placeholder={'{\n  "type": "object",\n  "properties": { ... }\n}'}
                rows={14}
                disabled={disabled}
                spellCheck={false}
                className="w-full px-3 py-2 rounded-md border border-input bg-background text-sm font-mono resize-none focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60 disabled:cursor-not-allowed"
              />
              <div className="absolute top-2 right-2">
                <Braces className="w-4 h-4 text-muted-foreground/50" />
              </div>
            </div>
          )}

          {/* Validation error (schema mode only) */}
          {mode === 'schema' && validationError && (
            <div className="flex items-start gap-2 p-3 rounded-md bg-destructive/10 text-destructive text-xs">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span className="font-mono break-all">{validationError}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
