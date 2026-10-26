import React, { useState, useEffect, useLayoutEffect, useMemo, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { useSettingsStore } from '@/services/stores/settings-store';
import { modelConfigService } from '@/services/model-config-service';
import { storageService } from '@/services/storage-service';
import { effortLabel } from '@/components/models/reasoning-controls';
import { sanitizeOverrides } from '@/components/models/model-overrides';
import { ModelParamPanel } from './ModelParamPanel';
import type { ModelProviderConfig, ModelCapabilities, ModelOverrides } from '@/types/settings';
import { i18n } from '@/utils/i18n';
import { resolveInitialSelection } from '@/utils/model-selection';
import { ChevronDown } from 'lucide-react';

export interface ModelSelectorProps {
  onModelChange: (configId: string, capabilities?: ModelCapabilities) => void;
  /** Controlled mode: display this config id instead of the global selection. */
  selectedId?: string | null;
  /** Controlled mode: skip writing the selection to settings store / localStorage. */
  persistSelection?: boolean;
  /**
   * Hover (desktop) / long-press (touch) parameter panel for effort and the working context
   * window. Off by default: consumers whose request has no override channel must not show it.
   */
  enableParamOverrides?: boolean;
  /**
   * Offer "no selection" as a real choice (e.g. a host whose request leaves the model to the
   * server). Only honoured in controlled mode: `selectedId` must be supplied (null included) from
   * the first render. In uncontrolled mode the global selection always resolves a model, so the
   * entry is not rendered and `onSelectionCleared` never fires.
   *
   * Never respond to `onSelectionCleared` by setting `selectedId` to `undefined`: that reads as
   * "uncontrolled", so the entry disappears and the next mount picks the first model. Pass `null`.
   */
  allowEmptySelection?: boolean;
  /** Label for the empty entry; defaults to "Default model". */
  emptySelectionLabel?: string;
  /** Fired when the user picks the empty entry, or a selected id no longer exists (empty mode). */
  onSelectionCleared?: () => void;
}

const PANEL_GAP = 8;
const HOVER_OPEN_MS = 180;
const HOVER_CLOSE_MS = 180;
const LONG_PRESS_MS = 500;
const LONG_PRESS_SLOP = 12;

export const ModelSelector: React.FC<ModelSelectorProps> = ({
  onModelChange,
  selectedId,
  persistSelection = true,
  enableParamOverrides = false,
  allowEmptySelection = false,
  emptySelectionLabel,
  onSelectionCleared,
}) => {
  const [enabledConfigs, setEnabledConfigs] = useState<ModelProviderConfig[]>([]);
  const [groupNames, setGroupNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [dropdownPos, setDropdownPos] = useState<{ bottom: number; left: number } | null>(null);
  const [pendingAnchor, setPendingAnchor] = useState<{ top: number; right: number } | null>(null);
  const [overrides, setOverrides] = useState<Record<string, ModelOverrides>>({});
  const [hoveredConfig, setHoveredConfig] = useState<ModelProviderConfig | null>(null);
  const [hoverAnchor, setHoverAnchor] = useState<{ top: number; left: number; right: number } | null>(null);
  const [panelPos, setPanelPos] = useState<{ top: number; left: number } | null>(null);
  const [sheetConfig, setSheetConfig] = useState<ModelProviderConfig | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const hoverOpenTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hoverCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pressStart = useRef<{ x: number; y: number } | null>(null);
  const pressFired = useRef(false);

  const selectedModelConfigId = useSettingsStore((state) => state.selectedModelConfigId);
  const setSelectedModelConfig = useSettingsStore((state) => state.setSelectedModelConfig);
  const setModelConfigs = useSettingsStore((state) => state.setModelConfigs);

  // Empty mode only makes sense when the host owns the selection; in uncontrolled mode the
  // global store always resolves a model, so offering "server default" would be a dead choice.
  const supportsEmptySelection = allowEmptySelection && selectedId !== undefined;

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
        setModelConfigs(enabled);
        const nameMap: Record<string, string> = {};
        for (const g of groups) {
          nameMap[g.id] = g.name;
        }
        setGroupNames(nameMap);

        if (enableParamOverrides) {
          const loaded: Record<string, ModelOverrides> = {};
          for (const config of enabled) {
            const raw = storageService.getModelOverrides(config.id);
            const clean = sanitizeOverrides(config, raw);
            if (raw && (!clean || raw.effort !== clean.effort || raw.contextToken !== clean.contextToken)) {
              storageService.saveModelOverrides(config.id, clean);
            }
            if (clean) loaded[config.id] = clean;
          }
          setOverrides(loaded);
        }

        // Read directly from localStorage to avoid race condition:
        // React child effects run before parent effects, so loadSettings()
        // in App.tsx may not have populated the store yet.
        const storedId = persistSelection ? storageService.getSelectedModelConfigId() : null;
        const initial = resolveInitialSelection({
          enabledIds: enabled.map((c) => c.id),
          selectedId,
          storedId,
          supportsEmptySelection,
        });
        if (initial.kind === 'cleared') {
          onSelectionCleared?.();
          return;
        }
        if (initial.kind === 'unselected') {
          return;
        }
        const currentConfig = enabled.find((c) => c.id === initial.configId);
        if (currentConfig?.id) {
          // Sync store & localStorage so the UI reflects the resolved model
          // (handles stale IDs left over from a different user's session).
          if (persistSelection) setSelectedModelConfig(currentConfig.id);
          onModelChange(currentConfig.id, currentConfig.capabilities);
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
    if (persistSelection) setSelectedModelConfig(config.id);
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

  // Measure the hover panel once it exists, then place it beside the row.
  useLayoutEffect(() => {
    if (!hoveredConfig || !hoverAnchor || !panelRef.current) return;
    const width = panelRef.current.offsetWidth;
    const height = panelRef.current.offsetHeight;
    let left = hoverAnchor.left - width - PANEL_GAP;
    if (left < PANEL_GAP) left = hoverAnchor.right + PANEL_GAP;
    left = Math.min(left, window.innerWidth - width - PANEL_GAP);
    const top = Math.max(PANEL_GAP, Math.min(hoverAnchor.top, window.innerHeight - height - PANEL_GAP));
    setPanelPos({ top, left });
  }, [hoveredConfig, hoverAnchor]);

  const clearHoverTimers = () => {
    if (hoverOpenTimer.current !== null) clearTimeout(hoverOpenTimer.current);
    if (hoverCloseTimer.current !== null) clearTimeout(hoverCloseTimer.current);
    hoverOpenTimer.current = null;
    hoverCloseTimer.current = null;
  };

  const scheduleHoverOpen = (config: ModelProviderConfig, rect: DOMRect) => {
    if (!enableParamOverrides) return;
    if (hoverCloseTimer.current !== null) clearTimeout(hoverCloseTimer.current);
    if (hoverOpenTimer.current !== null) clearTimeout(hoverOpenTimer.current);
    hoverOpenTimer.current = setTimeout(() => {
      setHoverAnchor({ top: rect.top, left: rect.left, right: rect.right });
      setPanelPos(null);
      setHoveredConfig(config);
    }, HOVER_OPEN_MS);
  };

  const scheduleHoverClose = () => {
    if (!enableParamOverrides) return;
    if (hoverOpenTimer.current !== null) clearTimeout(hoverOpenTimer.current);
    if (hoverCloseTimer.current !== null) clearTimeout(hoverCloseTimer.current);
    hoverCloseTimer.current = setTimeout(() => {
      setHoveredConfig(null);
      setPanelPos(null);
    }, HOVER_CLOSE_MS);
  };

  const clearPress = () => {
    if (pressTimer.current !== null) clearTimeout(pressTimer.current);
    pressTimer.current = null;
    pressStart.current = null;
  };

  const touchHandlers = (config: ModelProviderConfig) => ({
    onTouchStart: (e: React.TouchEvent) => {
      if (!enableParamOverrides || e.touches.length !== 1) return;
      const touch = e.touches[0];
      pressStart.current = { x: touch.clientX, y: touch.clientY };
      pressFired.current = false;
      pressTimer.current = setTimeout(() => {
        pressFired.current = true;
        setSheetConfig(config);
      }, LONG_PRESS_MS);
    },
    onTouchMove: (e: React.TouchEvent) => {
      const start = pressStart.current;
      if (!start) return;
      const touch = e.touches[0];
      if (Math.abs(touch.clientX - start.x) > LONG_PRESS_SLOP || Math.abs(touch.clientY - start.y) > LONG_PRESS_SLOP) {
        clearPress();
      }
    },
    onTouchEnd: clearPress,
    onTouchCancel: clearPress,
    onContextMenu: (e: React.MouseEvent) => {
      if (enableParamOverrides) e.preventDefault();
    },
  });

  // Close dropdown on outside click or scroll. The hover panel is portaled separately and
  // must count as "inside" or moving the pointer into it would dismiss everything.
  useEffect(() => {
    if (!dropdownOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      // A missing ref means that surface is not rendered; treat it as "outside" so the
      // dropdown still closes when the hover panel is absent.
      const outsideDropdown = !dropdownRef.current || !dropdownRef.current.contains(target);
      const outsidePanel = !panelRef.current || !panelRef.current.contains(target);
      const outsideButton = !buttonRef.current || !buttonRef.current.contains(target);
      if (outsideDropdown && outsidePanel && outsideButton) {
        setDropdownOpen(false);
        setHoveredConfig(null);
      }
    };
    const handleScroll = (e: Event) => {
      const target = e.target as Node | null;
      const insideDropdown = !!target && !!dropdownRef.current?.contains(target);
      const insidePanel = !!target && !!panelRef.current?.contains(target);
      // Scrolling the hover panel's own content keeps its row in place.
      if (insidePanel) return;
      if (!insideDropdown) {
        // Only a scroll that can move the button invalidates the measured anchor. The transcript
        // auto-scrolls on every streaming delta and lives outside the composer, so closing on it
        // made the dropdown flash away one frame after opening.
        const movesButton = !!target && target.contains(buttonRef.current);
        if (!movesButton) return;
      }
      // The hover panel is anchored to a one-shot rect; scrolling the list would leave it stale.
      setHoveredConfig(null);
      setPanelPos(null);
      if (!insideDropdown) setDropdownOpen(false);
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('scroll', handleScroll, true);
    };
  }, [dropdownOpen]);

  useEffect(() => {
    if (!dropdownOpen) {
      clearHoverTimers();
      setHoveredConfig(null);
      setPanelPos(null);
    }
  }, [dropdownOpen]);

  // Long-press/hover timers must not fire after the selector unmounts.
  useEffect(() => () => {
    if (hoverOpenTimer.current !== null) clearTimeout(hoverOpenTimer.current);
    if (hoverCloseTimer.current !== null) clearTimeout(hoverCloseTimer.current);
    if (pressTimer.current !== null) clearTimeout(pressTimer.current);
  }, []);

  const applyOverride = (configId: string, next: ModelOverrides | null) => {
    storageService.saveModelOverrides(configId, next);
    setOverrides(prev => {
      const copy = { ...prev };
      if (next) copy[configId] = next;
      else delete copy[configId];
      return copy;
    });
  };

  const effectiveSelectedId = selectedId !== undefined ? selectedId : selectedModelConfigId;
  const selectedConfig = enabledConfigs.find(c => c.id === effectiveSelectedId);

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

  const renderRow = (config: ModelProviderConfig) => {
    const override = overrides[config.id];
    const effort = override?.effort ?? config.options?.effort;
    return (
      <button
        key={config.id}
        onClick={() => {
          if (pressFired.current) {
            pressFired.current = false;
            return;
          }
          handleSelect(config);
        }}
        onMouseEnter={(e) => scheduleHoverOpen(config, e.currentTarget.getBoundingClientRect())}
        onMouseLeave={scheduleHoverClose}
        {...touchHandlers(config)}
        className={`w-full text-left px-3 py-2 hover:bg-muted transition-colors select-none ${
          effectiveSelectedId === config.id ? 'bg-muted' : ''
        }`}
      >
        <div className="text-sm font-medium truncate">{config.name}</div>
        <div className="text-xs text-muted-foreground mt-0.5 truncate flex items-center gap-1">
          <span className="truncate">{config.modelName || config.modelId}</span>
          {effort && (
            <span className="shrink-0 px-1 rounded bg-muted text-[10px]">{effortLabel(effort)}</span>
          )}
        </div>
      </button>
    );
  };

  const selectedEffort = selectedConfig
    ? overrides[selectedConfig.id]?.effort ?? selectedConfig.options?.effort
    : undefined;
  const renderPanel = (config: ModelProviderConfig) => (
    <ModelParamPanel
      config={config}
      value={overrides[config.id]}
      onChange={(next) => applyOverride(config.id, sanitizeOverrides(config, next))}
      onClose={() => {
        pressFired.current = false;
        setHoveredConfig(null);
        setPanelPos(null);
        setSheetConfig(null);
      }}
      sheet={!!sheetConfig}
    />
  );

  return (
    <div>
      <button
        ref={buttonRef}
        onClick={toggleDropdown}
        className="flex items-center gap-1 px-2 py-1 text-xs text-muted-foreground hover:text-foreground rounded-md hover:bg-muted transition-colors"
      >
        {selectedConfig
          ? (selectedConfig.name || selectedConfig.modelName || selectedConfig.modelId)
          : supportsEmptySelection
            ? (emptySelectionLabel ?? i18n('Default model'))
            : i18n('Select Model')}
        {selectedEffort && <span className="text-[10px] opacity-80">· {effortLabel(selectedEffort)}</span>}
        <ChevronDown className={`w-3 h-3 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
      </button>

      {dropdownOpen && pendingAnchor && createPortal(
        <>
          <div
            ref={dropdownRef}
            className="fixed w-[240px] max-h-[320px] overflow-y-auto bg-popover border border-border rounded-md shadow-lg z-[9999]"
            style={dropdownPos
              ? { bottom: `${dropdownPos.bottom}px`, left: `${dropdownPos.left}px` }
              : { bottom: `${window.innerHeight - pendingAnchor.top}px`, left: 0, visibility: 'hidden' }
            }
          >
            {supportsEmptySelection && (
              <button
                onClick={() => {
                  onSelectionCleared?.();
                  setDropdownOpen(false);
                }}
                className={`w-full text-left px-3 py-2 hover:bg-muted transition-colors select-none border-b border-border ${
                  effectiveSelectedId === null ? 'bg-muted' : ''
                }`}
              >
                <div className="text-sm font-medium truncate">{emptySelectionLabel ?? i18n('Default model')}</div>
              </button>
            )}
            {[...grouped.byGroup.entries()].map(([groupId, configs]) => (
              <div key={groupId}>
                <div className="px-3 pt-2 pb-1 text-[11px] font-medium text-muted-foreground/70 uppercase tracking-wide truncate">
                  {groupNames[groupId]}
                </div>
                {configs.map(config => renderRow(config))}
              </div>
            ))}
            {grouped.ungrouped.map(config => renderRow(config))}
          </div>

          {enableParamOverrides && hoveredConfig && (
            <div
              ref={panelRef}
              onMouseEnter={() => {
                if (hoverCloseTimer.current !== null) clearTimeout(hoverCloseTimer.current);
              }}
              onMouseLeave={scheduleHoverClose}
              className="fixed w-[280px] bg-popover border border-border rounded-md shadow-lg z-[10000]"
              style={panelPos ? { top: `${panelPos.top}px`, left: `${panelPos.left}px` } : { top: 0, left: 0, visibility: 'hidden' }}
            >
              {renderPanel(hoveredConfig)}
            </div>
          )}
        </>,
        document.body
      )}

      {sheetConfig && createPortal(
        <div
          className="fixed inset-0 z-[10000] bg-black/40 flex items-end"
          onClick={() => {
            // The click that follows a consumed long press may never fire on some mobile
            // browsers; clearing here keeps the flag from swallowing the next real tap.
            pressFired.current = false;
            setSheetConfig(null);
          }}
        >
          <div
            className="w-full bg-card border-t border-border rounded-t-xl p-4 space-y-3"
            onClick={(e) => e.stopPropagation()}
          >
            {renderPanel(sheetConfig)}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
};
