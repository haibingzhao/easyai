import React from 'react';
import { i18n } from '../../utils/i18n';
import { formatTokenCount } from '@/utils/format';
import type { ModelCatalogEntry } from '@/types/settings';
import type { CatalogPrefillState } from './useModelCatalogPrefill';

/**
 * Status line under the Model Name input: green when the catalog hit and prefilled the
 * form, gray when the id is not cataloged (manual fill), silent while idle or on lookup errors.
 */
export const ModelCatalogHint: React.FC<{ state: CatalogPrefillState; entry: ModelCatalogEntry | null }> = ({ state, entry }) => {
  if (state === 'hit' && entry) {
    const caps = entry.capabilities;
    const features: string[] = [];
    if (caps?.vision) features.push(i18n('vision'));
    if (caps?.supportsToolCalling) features.push(i18n('tool calling'));
    if (caps?.structuredOutput && caps.structuredOutput !== 'NONE') features.push(i18n('structured output'));
    if (caps?.thinking) features.push(i18n('thinking'));
    const context = entry.defaultOptions?.maxContextTokens
      ? `${formatTokenCount(entry.defaultOptions.maxContextTokens, 1024)} ${i18n('context')}`
      : null;
    return (
      <p className="mt-1 text-[11px] leading-tight text-green-600 dark:text-green-400">
        {i18n('Auto-filled from model catalog — adjust as needed')}
        {` · ${entry.displayName}`}
        {context ? ` · ${context}` : ''}
        {features.length > 0 ? ` · ${features.join(' / ')}` : ''}
      </p>
    );
  }
  if (state === 'miss') {
    return (
      <p className="mt-1 text-[11px] leading-tight text-muted-foreground">
        {i18n('Model not in the catalog — fill in capabilities and limits manually')}
      </p>
    );
  }
  return null;
};
