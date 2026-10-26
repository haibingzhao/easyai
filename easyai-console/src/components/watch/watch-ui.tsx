import type React from 'react';
import {
  BellRing,
  CheckCircle2,
  CircleSlash,
  Clock,
  HelpCircle,
  PauseCircle,
  XCircle,
} from 'lucide-react';
import type {
  Watch,
  WatchSchedule,
  WatchStatus,
  WatchVerdict,
} from '@/types/watch';
import { getLanguage, i18n } from '@/utils/i18n';

const STATUS_STYLE: Record<WatchStatus, string> = {
  active: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
  paused: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
  completed: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
  expired: 'bg-muted text-muted-foreground',
  cancelled: 'bg-muted text-muted-foreground',
};

const STATUS_KEY: Record<WatchStatus, string> = {
  active: 'Active',
  paused: 'Paused',
  completed: 'Completed',
  expired: 'Expired',
  cancelled: 'Cancelled',
};

const STATUS_ICON: Record<WatchStatus, React.ComponentType<{ className?: string }>> = {
  active: Clock,
  paused: PauseCircle,
  completed: CheckCircle2,
  expired: CircleSlash,
  cancelled: XCircle,
};

const VERDICT_STYLE: Record<WatchVerdict, string> = {
  met: 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400',
  not_met: 'bg-muted text-muted-foreground',
  unknown: 'bg-amber-500/15 text-amber-600 dark:text-amber-400',
  note: 'bg-blue-500/15 text-blue-600 dark:text-blue-400',
};

const VERDICT_KEY: Record<WatchVerdict, string> = {
  met: 'Met',
  not_met: 'Not met',
  unknown: 'Unknown',
  note: 'Note',
};

/** Localized label for a stored verdict value; a value this build does not know passes through. */
export function verdictLabel(verdict: string): string {
  return i18n(VERDICT_KEY[verdict as WatchVerdict] ?? verdict);
}

export const WatchStatusBadge: React.FC<{ status: WatchStatus }> = ({ status }) => {
  const Icon = STATUS_ICON[status] ?? BellRing;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
        STATUS_STYLE[status] ?? 'bg-muted text-muted-foreground'
      }`}
    >
      <Icon className="size-3" />
      {i18n(STATUS_KEY[status] ?? status)}
    </span>
  );
};

export const VerdictBadge: React.FC<{ verdict: WatchVerdict }> = ({ verdict }) => (
  <span
    className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
      VERDICT_STYLE[verdict] ?? 'bg-muted text-muted-foreground'
    }`}
  >
    {verdictLabel(verdict)}
  </span>
);

/** A verdict this build does not know still renders with a neutral mark. */
export const UnknownVerdictBadge: React.FC = () => (
  <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
    <HelpCircle className="size-3" />
    {i18n('Unknown')}
  </span>
);

/** Format an instant in the watch's own zone, not the browser's. */
export function formatInZone(epochMs: number | null | undefined, timeZone: string): string {
  if (epochMs == null) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone,
    }).format(new Date(epochMs));
  } catch {
    return new Date(epochMs).toLocaleString();
  }
}

const WEEKDAY_LABEL: Record<string, string> = {
  MONDAY: 'Monday',
  TUESDAY: 'Tuesday',
  WEDNESDAY: 'Wednesday',
  THURSDAY: 'Thursday',
  FRIDAY: 'Friday',
  SATURDAY: 'Saturday',
  SUNDAY: 'Sunday',
};

/** Localized weekday label for a stored DayOfWeek name; a value this build does not know passes through. */
export function weekdayLabel(weekday?: string | null): string {
  const key = WEEKDAY_LABEL[weekday ?? ''];
  return key ? i18n(key) : (weekday ?? '');
}

export function scheduleText(schedule: WatchSchedule): string {
  const time = schedule.localTime ?? '';
  switch (schedule.kind) {
    case 'at':
      return `${i18n('Once at')} ${formatInZone(schedule.anchorAt, schedule.timezone)}`;
    case 'daily':
      return `${i18n('Daily at')} ${time}`;
    case 'weekly':
      // "每周一 09:00" reads correctly only without the glue word English needs.
      return getLanguage() === 'zh'
        ? `${i18n('Weekly on')}${weekdayLabel(schedule.weekday)} ${time}`
        : `${i18n('Weekly on')} ${weekdayLabel(schedule.weekday)} ${i18n('at')} ${time}`;
    case 'monthly':
      return getLanguage() === 'zh'
        ? `${i18n('Monthly on day')}${schedule.dayOfMonth ?? ''}${i18n('Monthly day suffix')} ${time}`
        : `${i18n('Monthly on day')} ${schedule.dayOfMonth ?? ''} ${i18n('at')} ${time}`;
    default:
      return schedule.kind;
  }
}

/** One-line schedule + zone, used in list cards and metadata rows. */
export function scheduleSummary(watch: Watch): string {
  return `${scheduleText(watch.schedule)} · ${watch.schedule.timezone}`;
}
