import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import { useCapabilityStore, selectCanManage } from '@/services/stores/capability-store';
import { chatPreferenceService } from '@/services/chat-preference-service';
import { isChatSelectable } from '@/utils/chat-agents';
import { readErrorMessage, resolveWriteScope } from '@/services/api-client';
import type { AssetScope } from '@/services/api-client';
import { i18n } from '@/utils/i18n';
import type { AgentDto, ChatPreference, ChatPreferenceSource } from '@/types/agent';
import { Bot, Loader2, CheckCircle2, AlertCircle, RotateCcw } from 'lucide-react';

const SOURCE_BADGE: Record<ChatPreferenceSource, { label: string; tone: string }> = {
  user: { label: 'Personal setting', tone: 'text-green-500 bg-green-500/10' },
  group: { label: 'Shared by group', tone: 'text-blue-500 bg-blue-500/10' },
  default: { label: 'Built-in default', tone: 'text-muted-foreground bg-muted' },
};

function resultMessage(
  action: 'save' | 'reset',
  target: AssetScope,
  effectiveSource: ChatPreferenceSource,
): string {
  const overridden = effectiveSource === 'user';
  if (action === 'reset') {
    if (target === 'personal') return i18n('Personal Chat settings reset to built-in defaults');
    return overridden
      ? i18n('Group Chat settings reset to built-in defaults, but your personal setting still overrides it')
      : i18n('Group Chat settings reset to built-in defaults');
  }
  if (target === 'personal') return i18n('Saved as personal settings');
  return overridden
    ? i18n('Saved to group settings, but your personal setting still overrides it')
    : i18n('Saved to group settings');
}

/**
 * Settings → Chat Agents. Edits the agent a fresh chat starts with and the set of agents shown in
 * the composer's picker.
 *
 * The pane always reads and writes an explicit bucket (`personal` or `group`) instead of relying on
 * the host's default write scope. Reading the effective row while writing the default bucket made a
 * group owner with a personal row edit one layer and save into the other. The group target is only
 * offered to logins that may manage the group bucket; members fall back to their personal bucket,
 * which is a valid per-user override.
 */
export const ChatAgentsTab: React.FC = () => {
  const agents = useAgentStore((state) => state.agents);
  const loadAgents = useAgentStore((state) => state.loadAgents);
  const setChatPreference = useAgentStore((state) => state.setChatPreference);

  const capabilities = useCapabilityStore((state) => state.capabilities);
  const capabilitiesLoaded = useCapabilityStore((state) => state.loaded);
  const canManageGroup = useCapabilityStore(selectCanManage('agent'));
  const groupUserId = capabilities?.groupUserId ?? null;
  const groupAvailable = groupUserId != null && canManageGroup;
  // Follow the host's routing when it points at a bucket this login can write; otherwise personal.
  const defaultTarget: AssetScope =
    groupAvailable && resolveWriteScope() === 'group' ? 'group' : 'personal';

  const [target, setTarget] = useState<AssetScope | null>(null);
  const resolvedTarget: AssetScope = target === 'group' && !groupAvailable ? 'personal' : target ?? defaultTarget;

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [preference, setPreference] = useState<ChatPreference | null>(null);
  const [defaultAgentId, setDefaultAgentId] = useState('');
  const [hiddenIds, setHiddenIds] = useState<Set<string>>(new Set());
  const [initial, setInitial] = useState<{ defaultAgentId: string; hiddenIds: string[] }>({
    defaultAgentId: '',
    hiddenIds: [],
  });

  // A group row may only reference agents that exist in the group and shared layers; the backend
  // rejects a personal-only id there, so do not offer one in the first place.
  const allowedForTarget = useCallback(
    (agent: AgentDto) => {
      if (resolvedTarget === 'personal') return true;
      // Read the bucket id at call time so a late capability probe does not recreate this callback
      // (and therefore reload/reset the form) while the target itself has not changed.
      const groupBucket = useCapabilityStore.getState().capabilities?.groupUserId ?? null;
      return agent.ownerId === groupBucket || agent.ownerId === 'system';
    },
    [resolvedTarget],
  );

  const selectableAgents = useMemo(
    () => agents.filter(isChatSelectable).filter(allowedForTarget),
    [agents, allowedForTarget],
  );
  const visibleAgents = useMemo(
    () => selectableAgents.filter((agent) => !hiddenIds.has(agent.id)),
    [selectableAgents, hiddenIds],
  );

  /**
   * Adopt a server response. Ids the current agent list cannot resolve (deleted agent, no longer a
   * chat agent) are dropped so the draft stays savable — the backend would reject them outright.
   */
  const applyPreference = useCallback((pref: ChatPreference) => {
    const selectableIds = new Set(
      useAgentStore.getState().agents.filter((agent) => isChatSelectable(agent) && allowedForTarget(agent)).map((a) => a.id),
    );
    const hidden = pref.hiddenAgentIds.filter((id) => selectableIds.has(id));
    const defaultId = pref.defaultAgentId && selectableIds.has(pref.defaultAgentId) ? pref.defaultAgentId : '';
    setPreference(pref);
    setDefaultAgentId(defaultId);
    setHiddenIds(new Set(hidden));
    setInitial({ defaultAgentId: defaultId, hiddenIds: hidden });
  }, [allowedForTarget]);

  /** Drop a response whose target is no longer the one on screen (fast target switching). */
  const loadSeqRef = useRef(0);

  const load = useCallback(async () => {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    try {
      if (useAgentStore.getState().agents.length === 0) {
        await loadAgents();
        // loadAgents swallows the request error into the store; surface it instead of pretending
        // the deployment has no chat agents.
        const storeError = useAgentStore.getState().error;
        if (storeError) throw new Error(storeError);
      }
      if (seq !== loadSeqRef.current) return;
      const loadedAgents = useAgentStore.getState().agents;
      if (resolvedTarget === 'group' && loadedAgents.length > 0 && loadedAgents.every((agent) => !agent.ownerId)) {
        // New console, old backend: without ownerId every agent looks personal-only, so the group
        // target cannot be filtered correctly. Say so instead of claiming there are no agents.
        throw new Error(i18n('This server does not expose agent ownership; upgrade the backend to edit group Chat settings.'));
      }
      if (loadedAgents.filter((agent) => isChatSelectable(agent) && allowedForTarget(agent)).length === 0) {
        throw new Error(i18n('No selectable agents available to configure.'));
      }
      const pref = await chatPreferenceService.get(resolvedTarget);
      if (seq !== loadSeqRef.current) return;
      applyPreference(pref);
      setLoadError('');
    } catch (e) {
      if (seq !== loadSeqRef.current) return;
      setLoadError(readErrorMessage(e, i18n('Failed to load Chat agent settings')));
    } finally {
      if (seq === loadSeqRef.current) setLoading(false);
    }
  }, [allowedForTarget, applyPreference, loadAgents, resolvedTarget]);

  useEffect(() => {
    // The capability probe decides the default target. Loading the preference before it resolves
    // would fetch the personal row, then reload and reset the form if the target flips to group.
    if (!capabilitiesLoaded) return;
    load();
  }, [capabilitiesLoaded, load]);

  const dirty =
    defaultAgentId !== initial.defaultAgentId ||
    hiddenIds.size !== initial.hiddenIds.length ||
    [...hiddenIds].some((id) => !initial.hiddenIds.includes(id));

  const handleTargetChange = (next: AssetScope) => {
    if (next === resolvedTarget) return;
    if (dirty && !window.confirm(i18n('Discard unsaved changes and switch the settings target?'))) return;
    setMessage(null);
    setTarget(next);
  };

  const toggleVisibility = (agentId: string) => {
    const hiding = !hiddenIds.has(agentId);
    if (hiding && visibleAgents.length <= 1) return;
    setHiddenIds((prev) => {
      const next = new Set(prev);
      if (hiding) next.add(agentId);
      else next.delete(agentId);
      return next;
    });
    if (hiding && defaultAgentId === agentId) {
      setDefaultAgentId('');
    }
  };

  const persist = async (
    request: { defaultAgentId: string | null; hiddenAgentIds: string[] },
    action: 'save' | 'reset' = 'save',
  ) => {
    setSaving(true);
    setMessage(null);
    try {
      const updated = await chatPreferenceService.save(request, resolvedTarget);
      // The form keeps the bucket that was just written. The chat surface needs the effective row,
      // which differs when a personal row shadows a group one — fetch it only in that case. A failed
      // re-read must not turn a successful save into an error; the next bootstrap reloads it.
      if (updated.source === updated.effectiveSource) {
        setChatPreference(updated);
      } else {
        try {
          setChatPreference(await chatPreferenceService.get());
        } catch {
          // Keep the store's previous effective preference until the next load.
        }
      }
      applyPreference(updated);
      setMessage({
        type: 'success',
        text: resultMessage(action, resolvedTarget, updated.effectiveSource),
      });
    } catch (e) {
      setMessage({ type: 'error', text: readErrorMessage(e, i18n('Failed to save')) });
    } finally {
      setSaving(false);
    }
  };

  const handleSave = () =>
    persist({ defaultAgentId: defaultAgentId || null, hiddenAgentIds: [...hiddenIds] });

  const handleReset = () => {
    const confirmText =
      resolvedTarget === 'group'
        ? i18n('Reset group Chat agent settings to built-in defaults?')
        : i18n('Reset personal Chat agent settings to built-in defaults?');
    if (!window.confirm(confirmText)) return;
    void persist({ defaultAgentId: null, hiddenAgentIds: [] }, 'reset');
  };

  if (loading || !capabilitiesLoaded) {
    return (
      <div className="flex items-center justify-center h-48">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="flex items-start gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm max-w-lg">
        <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
        <span>{loadError}</span>
      </div>
    );
  }

  // Older backends do not send `source`; fall back to the effective layer rather than crashing.
  const source: ChatPreferenceSource = preference?.source ?? preference?.effectiveSource ?? 'default';
  const badge = SOURCE_BADGE[source];
  const shadowed =
    preference != null && preference.source !== preference.effectiveSource;

  return (
    <div className="space-y-6 max-w-lg">
      <div>
        <div className="flex items-center gap-2 mb-4">
          <Bot className="w-5 h-5 text-muted-foreground" />
          <h2 className="text-lg font-medium">{i18n('Chat Agents')}</h2>
          <span className={`text-xs px-2 py-0.5 rounded ${badge.tone}`}>{i18n(badge.label)}</span>
        </div>

        <p className="text-sm text-muted-foreground mb-4">
          {i18n('Choose the agent a new chat starts with, and hide agents that do not belong in the Chat picker.')}
        </p>

        {groupAvailable ? (
          <div className="mb-5 space-y-2">
            <div className="text-sm font-medium">{i18n('Settings target')}</div>
            <div className="flex items-center gap-1 p-0.5 bg-muted rounded-lg w-fit">
              {(['personal', 'group'] as const).map((scope) => (
                <button
                  key={scope}
                  onClick={() => handleTargetChange(scope)}
                  disabled={saving}
                  className={`px-3 py-1 text-xs rounded-md transition-colors disabled:opacity-50 ${
                    resolvedTarget === scope
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {scope === 'personal' ? i18n('Personal') : i18n('Group (shared)')}
                </button>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">
              {resolvedTarget === 'group'
                ? i18n('Group settings apply to every member; your personal settings take precedence for you.')
                : i18n('Personal settings apply only to you.')}
            </p>
          </div>
        ) : null}

        <div className="space-y-5">
          <div className="p-4 rounded-lg border border-border space-y-3">
            <label className="text-sm font-medium block">{i18n('Default Agent')}</label>
            <select
              value={defaultAgentId}
              onChange={(e) => setDefaultAgentId(e.target.value)}
              className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
            >
              <option value="">{i18n('Follow built-in default')}</option>
              {visibleAgents.map((agent) => (
                <option key={agent.id} value={agent.id}>{agent.name}</option>
              ))}
            </select>
            <p className="text-xs text-muted-foreground">
              {i18n('Used when a new chat starts. Agents hidden below are not offered.')}
            </p>
          </div>

          <div className="p-4 rounded-lg border border-border space-y-2">
            <div className="text-sm font-medium">{i18n('Visible in Chat')}</div>
            <p className="text-xs text-muted-foreground">
              {i18n('Uncheck an agent to remove it from the Chat agent picker; at least one must stay visible.')}
            </p>
            <div className="divide-y divide-border">
              {selectableAgents.map((agent) => {
                const visible = !hiddenIds.has(agent.id);
                const isLastVisible = visible && visibleAgents.length <= 1;
                return (
                  <label
                    key={agent.id}
                    className="flex items-center justify-between gap-3 py-2 text-sm cursor-pointer"
                  >
                    <span className="min-w-0">
                      <span className="font-medium block truncate">{agent.name}</span>
                      {agent.description && (
                        <span className="text-xs text-muted-foreground block truncate">{agent.description}</span>
                      )}
                    </span>
                    <input
                      type="checkbox"
                      checked={visible}
                      disabled={isLastVisible}
                      onChange={() => toggleVisibility(agent.id)}
                      className="h-4 w-4 shrink-0 accent-primary"
                    />
                  </label>
                );
              })}
            </div>
          </div>

          {shadowed && (
            <div className="flex items-start gap-2 p-3 rounded-md text-sm bg-muted text-muted-foreground">
              <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
              <span>
                {preference.effectiveSource === 'user'
                  ? i18n('Your personal settings currently override this shared group setting.')
                  : i18n('This bucket is empty, so the group settings apply to you right now.')}
              </span>
            </div>
          )}

          {message && (
            <div className={`flex items-center gap-2 p-3 rounded-md text-sm ${
              message.type === 'success'
                ? 'bg-green-500/10 text-green-500'
                : 'bg-destructive/10 text-destructive'
            }`}>
              {message.type === 'success' ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
              {message.text}
            </div>
          )}

          <div className="flex items-center justify-between">
            <button
              onClick={handleReset}
              disabled={saving}
              className="flex items-center gap-2 px-4 py-2 rounded-md border border-border text-sm hover:bg-muted disabled:opacity-50 transition-colors"
            >
              <RotateCcw className="w-4 h-4" />
              {resolvedTarget === 'group'
                ? i18n('Reset group settings to defaults')
                : i18n('Reset personal settings to defaults')}
            </button>
            <button
              onClick={handleSave}
              disabled={!dirty || saving || visibleAgents.length === 0}
              className="flex items-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors"
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : null}
              {i18n('Save')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
