import React, { useState, useEffect, useLayoutEffect, useMemo, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useSettingsStore } from '@/services/stores/settings-store';
import { modelConfigService } from '@/services/model-config-service';
import { storageService } from '@/services/storage-service';
import type { ModelProviderConfig, ModelCapabilities } from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { ChevronDown } from 'lucide-react';

interface ModelSelectorProps {
  onModelChange: (configId: string, capabilities?: ModelCapabilities) => void;
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({ onModelChange }) => {
  const [enabledConfigs, setEnabledConfigs] = useState<ModelProviderConfig[]>([]);
  const [groupNames, setGroupNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [dropdownPos, setDropdownPos] = useState<{ bottom: number; left: number } | null>(null);
  const [pendingAnchor, setPendingAnchor] = useState<{ top: number; right: number } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const selectedModelConfigId = useSettingsStore((state) => state.selectedModelConfigId);
  const setSelectedModelConfig = useSettingsStore((state) => state.setSelectedModelConfig);

  useEffect(() => {
    const loadConfigs = async () => {
      try {
        setLoading(true);
        const [configs, groups] = await Promise.all([
          modelConfigService.getUserConfigurations(),
          modelConfigService.getGroups(),
        ]);
        const enabled = configs.filter(c => c.enabled !== false);
        setEnabledConfigs(enabled);
        const nameMap: Record<string, string> = {};
        for (const g of groups) {
          nameMap[g.id] = g.name;
        }
        setGroupNames(nameMap);

        if (enabled.length > 0) {
          // Read directly from localStorage to avoid race condition:
          // React child effects run before parent effects, so loadSettings()
          // in App.tsx may not have populated the store yet.
          const storedId = storageService.getSelectedModelConfigId();
          const currentId = storedId || enabled[0].id;
          const currentConfig = enabled.find(c => c.id === currentId) || enabled[0];
          if (currentConfig?.id) {
            // Sync store & localStorage so the UI reflects the resolved model
            // (handles stale IDs left over from a different user's session).
            setSelectedModelConfig(currentConfig.id);
            onModelChange(currentConfig.id, currentConfig.capabilities);
          }
        }
      } catch (e) {
        console.error('Failed to load model configs:', e);
      } finally {
        setLoading(false);
      }
    };
    loadConfigs();
  }, []);

  const handleSelect = (config: ModelProviderConfig) => {
    setSelectedModelConfig(config.id);
    onModelChange(config.id, config.capabilities);
    setDropdownOpen(false);
  };

  const toggleDropdown = useCallback(() => {
    if (!dropdownOpen && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      setPendingAnchor({ top: rect.top - 8, right: window.innerWidth - rect.right });
      setDropdownPos(null);
    }
    setDropdownOpen(prev => !prev);
  }, [dropdownOpen]);

  // After dropdown renders, measure it and clamp position within viewport
  useLayoutEffect(() => {
    if (!dropdownOpen || !pendingAnchor || !dropdownRef.current) return;
    const ddWidth = dropdownRef.current.offsetWidth;
    const ddHeight = dropdownRef.current.offsetHeight;
    const margin = 8;
    // Preferred: right-aligned to button's right edge
    let left = window.innerWidth - pendingAnchor.right - ddWidth;
    // Clamp horizontally within viewport
    left = Math.max(margin, Math.min(left, window.innerWidth - ddWidth - margin));
    // Position above the button; if not enough space, position below viewport bottom margin
    let bottom = window.innerHeight - pendingAnchor.top;
    if (bottom + ddHeight > window.innerHeight - margin) {
      bottom = Math.max(margin, window.innerHeight - ddHeight - margin);
    }
    setDropdownPos({ bottom, left });
  }, [dropdownOpen, pendingAnchor]);

  // Close dropdown on outside click or scroll
  useEffect(() => {
    if (!dropdownOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current && !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current && !buttonRef.current.contains(e.target as Node)
      ) {
        setDropdownOpen(false);
      }
    };
    const handleScroll = (e: Event) => {
      if (dropdownRef.current && dropdownRef.current.contains(e.target as Node)) return;
      setDropdownOpen(false);
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('scroll', handleScroll, true);
    };
  }, [dropdownOpen]);

  const selectedConfig = enabledConfigs.find(c => c.id === selectedModelConfigId);

  // Group configs by groupId for display; ungrouped configs go last
  const grouped = useMemo(() => {
    const byGroup = new Map<string, ModelProviderConfig[]>();
    const ungrouped: ModelProviderConfig[] = [];
    for (const config of enabledConfigs) {
      if (config.groupId && groupNames[config.groupId]) {
        const list = byGroup.get(config.groupId) || [];
        list.push(config);
        byGroup.set(config.groupId, list);
      } else {
        ungrouped.push(config);
      }
    }
    return { byGroup, ungrouped };
  }, [enabledConfigs, groupNames]);

  if (loading) {
    return (
      <div className="text-xs text-muted-foreground flex items-center gap-1">
        <span className="w-3 h-3 rounded-full bg-muted-foreground/50 animate-pulse"></span>
        {i18n('Loading models...')}
      </div>
    );
  }

  if (enabledConfigs.length === 0) {
    return (
      <div className="text-xs text-muted-foreground">
        {i18n('No models configured. Please add a model first.')}
      </div>
    );
  }

  return (
    <div>
      <button
        ref={buttonRef}
        onClick={toggleDropdown}
        className="flex items-center gap-1 px-2 py-1 text-xs text-muted-foreground hover:text-foreground rounded-md hover:bg-muted transition-colors"
      >
        {selectedConfig ? (selectedConfig.modelName || selectedConfig.modelId) : i18n('Select Model')}
        <ChevronDown className={`w-3 h-3 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
      </button>

      {dropdownOpen && pendingAnchor && createPortal(
        <div
          ref={dropdownRef}
          className="fixed w-[240px] max-h-[320px] overflow-y-auto bg-popover border border-border rounded-md shadow-lg z-[9999]"
          style={dropdownPos
            ? { bottom: `${dropdownPos.bottom}px`, left: `${dropdownPos.left}px` }
            : { bottom: `${window.innerHeight - pendingAnchor.top}px`, left: 0, visibility: 'hidden' }
          }
        >
          {[...grouped.byGroup.entries()].map(([groupId, configs]) => (
            <div key={groupId}>
              <div className="px-3 pt-2 pb-1 text-[11px] font-medium text-muted-foreground/70 uppercase tracking-wide truncate">
                {groupNames[groupId]}
              </div>
              {configs.map(config => (
                <button
                  key={config.id}
                  onClick={() => handleSelect(config)}
                  className={`w-full text-left px-3 py-2 hover:bg-muted transition-colors ${
                    selectedModelConfigId === config.id ? 'bg-muted' : ''
                  }`}
                >
                  <div className="text-sm font-medium truncate">{config.name}</div>
                  <div className="text-xs text-muted-foreground mt-0.5 truncate">{config.modelName || config.modelId}</div>
                </button>
              ))}
            </div>
          ))}
          {grouped.ungrouped.map(config => (
            <button
              key={config.id}
              onClick={() => handleSelect(config)}
              className={`w-full text-left px-3 py-2 hover:bg-muted transition-colors ${
                selectedModelConfigId === config.id ? 'bg-muted' : ''
              }`}
            >
              <div className="text-sm font-medium truncate">{config.name}</div>
              <div className="text-xs text-muted-foreground mt-0.5 truncate">{config.modelName || config.modelId}</div>
            </button>
          ))}
        </div>,
        document.body
      )}
    </div>
  );
};