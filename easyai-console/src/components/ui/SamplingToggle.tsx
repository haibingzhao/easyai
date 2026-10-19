import { i18n } from '@/utils/i18n';
import type { SamplingSupport } from '@/types/settings';

interface SamplingToggleProps {
  value?: SamplingSupport;
  onChange: (value: SamplingSupport | undefined) => void;
}

/**
 * Whether the model's endpoint accepts sampling parameters at all.
 *
 * The empty option is the undeclared state and saves nothing: the backend reads a missing
 * capability as SETTABLE, so writing an explicit default would change the meaning of the row.
 */
export const SamplingToggle: React.FC<SamplingToggleProps> = ({ value, onChange }) => (
  <div>
    <label className="text-sm font-medium mb-1 block">{i18n('Sampling Parameters')}</label>
    <select
      value={value || ''}
      onChange={(e) => onChange((e.target.value || undefined) as SamplingSupport | undefined)}
      className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm"
    >
      <option value="">{i18n('Default')}</option>
      <option value="SETTABLE">{i18n('Settable')}</option>
      <option value="NOT_SETTABLE">{i18n('Rejected by the endpoint')}</option>
    </select>
  </div>
);
