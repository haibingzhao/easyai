import type { TeamFilter } from '@/types/team';
import { i18n } from '@/utils/i18n';

const FILTERS: { id: TeamFilter; labelKey: string }[] = [
  { id: 'ALL', labelKey: 'All' },
  { id: 'RUNNING', labelKey: 'Running' },
  { id: 'DONE', labelKey: 'Done' },
  { id: 'BLOCKED', labelKey: 'Blocked' },
  { id: 'ERROR', labelKey: 'Error' },
];

interface TeamFilterTabsProps {
  active: TeamFilter;
  counts: Record<TeamFilter, number>;
  onChange: (filter: TeamFilter) => void;
}

/** Filter tab bar for the Team Member Panel. */
export const TeamFilterTabs: React.FC<TeamFilterTabsProps> = ({ active, counts, onChange }) => (
  <div className="flex items-center gap-1 px-2 py-1.5 border-b border-border shrink-0 flex-wrap">
    {FILTERS.map((f) => {
      const isActive = active === f.id;
      const count = counts[f.id];
      return (
        <button
          key={f.id}
          onClick={() => onChange(f.id)}
          className={`px-2 py-0.5 rounded-full text-[11px] transition-colors ${
            isActive
              ? 'bg-primary text-primary-foreground font-medium'
              : 'text-muted-foreground hover:bg-muted hover:text-foreground'
          }`}
        >
          {i18n(f.labelKey)}
          {count > 0 && <span className="ml-1 opacity-70 tabular-nums">{count}</span>}
        </button>
      );
    })}
  </div>
);
