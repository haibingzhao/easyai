import React, { useCallback, useEffect, useState } from 'react';
import { i18n } from '../../utils/i18n';
import { modelConfigService } from '@/services/model-config-service';
import { formatTokenCount } from '@/utils/format';
import { Loader2, RefreshCw } from 'lucide-react';
import type { CatalogRefreshResult, ModelCatalogEntry } from '@/types/settings';

const CURRENCY_SYMBOL: Record<string, string> = { CNY: '¥', USD: '$' };

/** Compact "12 / 36" per-million price cell; dash when the vendor published no price. */
function priceCell(entry: ModelCatalogEntry): string {
  const p = entry.pricing;
  if (!p || (p.inputPerMTok == null && p.outputPerMTok == null)) return '-';
  const symbol = CURRENCY_SYMBOL[p.currency] ?? `${p.currency} `;
  const fmt = (v?: number) => (v == null ? '-' : `${symbol}${v}`);
  return `${fmt(p.inputPerMTok)} / ${fmt(p.outputPerMTok)}`;
}

function capabilityBadges(entry: ModelCatalogEntry): string[] {
  const caps = entry.capabilities;
  if (!caps) return [];
  const out: string[] = [];
  if (caps.vision) out.push(i18n('vision'));
  if (caps.supportsToolCalling) out.push(i18n('tool calling'));
  if (caps.structuredOutput && caps.structuredOutput !== 'NONE') out.push(i18n('structured output'));
  if (caps.thinking) out.push(i18n('thinking'));
  return out;
}

/**
 * Models page "Model Catalog" tab: the vendor-published facts backing add-model prefills.
 * The catalog starts empty and is initialized/updated here via Refresh (pulls the full
 * Bailian model marketplace).
 */
export const ModelCatalogSection: React.FC = () => {
  const [entries, setEntries] = useState<ModelCatalogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null);
  const [filter, setFilter] = useState('');

  const load = useCallback(async () => {
    try {
      setEntries(await modelConfigService.listModelCatalog());
    } catch (e) {
      setNotice({ tone: 'error', text: e instanceof Error ? e.message : i18n('Failed to load model catalog') });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleRefresh = async () => {
    setRefreshing(true);
    setNotice(null);
    try {
      const result: CatalogRefreshResult = await modelConfigService.refreshModelCatalog();
      if (result.success) {
        setNotice({
          tone: 'success',
          text: i18n('Added {n} · Updated {m}').replace('{n}', String(result.inserted)).replace('{m}', String(result.updated)),
        });
      } else {
        setNotice({ tone: 'error', text: `${i18n('Catalog refresh failed')}: ${result.message}` });
      }
      await load();
    } catch (e) {
      setNotice({
        tone: 'error',
        text: `${i18n('Catalog refresh failed')}${e instanceof Error && e.message ? `: ${e.message}` : ''}`,
      });
    } finally {
      setRefreshing(false);
    }
  };

  const needle = filter.trim().toLowerCase();
  const visible = needle
    ? entries.filter(m => m.modelId.toLowerCase().includes(needle) || m.displayName.toLowerCase().includes(needle))
    : entries;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3">
          <input
            type="text"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={i18n('Filter by model id or name')}
            className="flex h-9 w-64 rounded-md border border-input bg-transparent px-3 py-1 text-sm"
          />
          <span className="text-xs text-muted-foreground">{visible.length} / {entries.length}</span>
        </div>
        <button
          onClick={handleRefresh}
          disabled={refreshing}
          className="flex items-center gap-2 px-3 py-1.5 text-sm rounded-md border border-input hover:bg-muted transition-colors disabled:opacity-50"
        >
          {refreshing ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
          {refreshing ? i18n('Refreshing...') : i18n('Refresh')}
        </button>
      </div>

      {notice && (
        <p className={`text-sm ${notice.tone === 'success' ? 'text-green-600 dark:text-green-400' : 'text-red-500'}`}>
          {notice.text}
        </p>
      )}

      {loading ? (
        <p className="text-sm text-muted-foreground py-8">{i18n('Loading...')}</p>
      ) : entries.length === 0 ? (
        <div className="border border-dashed border-border rounded-lg p-10 text-center space-y-2">
          <p className="text-sm text-muted-foreground">{i18n('Catalog is empty — click Refresh to pull model info from Bailian')}</p>
        </div>
      ) : (
        <div className="border border-border rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-muted-foreground border-b border-border bg-muted/30">
                <th className="px-3 py-2 font-medium">{i18n('Model ID')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Name')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Capabilities')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Context')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Max Output')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Price in / out (per 1M)')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Source')}</th>
                <th className="px-3 py-2 font-medium">{i18n('Updated')}</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(entry => (
                <tr key={entry.modelId} className="border-b border-border last:border-b-0 hover:bg-muted/20">
                  <td className="px-3 py-2 font-mono text-xs">{entry.modelId}</td>
                  <td className="px-3 py-2" title={entry.description}>{entry.displayName}</td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-1">
                      {capabilityBadges(entry).map(label => (
                        <span key={label} className="px-1.5 py-0.5 rounded bg-muted border border-border text-[11px] text-muted-foreground">
                          {label}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-3 py-2 text-xs">{entry.defaultOptions?.maxContextTokens != null ? formatTokenCount(entry.defaultOptions.maxContextTokens, 1024) : '-'}</td>
                  <td className="px-3 py-2 text-xs">{entry.defaultOptions?.maxTokens != null ? formatTokenCount(entry.defaultOptions.maxTokens, 1024) : '-'}</td>
                  <td className="px-3 py-2 text-xs whitespace-nowrap">{priceCell(entry)}</td>
                  <td className="px-3 py-2 text-xs text-muted-foreground">{entry.source}</td>
                  <td className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">{new Date(entry.updatedAt).toLocaleDateString()}</td>
                </tr>
              ))}
              {visible.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-sm text-muted-foreground">{i18n('No models match the filter')}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
