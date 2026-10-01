import { i18n } from '@/utils/i18n';

interface ToolCallingToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: string;
}

export const ToolCallingToggle: React.FC<ToolCallingToggleProps> = ({
  checked,
  onChange,
  label,
}) => (
  <div className="flex items-center justify-between">
    <label className="text-sm font-medium">{i18n(label ?? 'Supports Tool Calling')}</label>
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className={`relative w-9 h-5 rounded-full transition-colors ${
        checked ? 'bg-green-500' : 'bg-muted-foreground/30'
      }`}
    >
      <div
        className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
          checked ? 'translate-x-4' : 'translate-x-0.5'
        }`}
      />
    </button>
  </div>
);
