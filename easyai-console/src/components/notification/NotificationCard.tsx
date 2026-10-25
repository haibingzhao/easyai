import React from 'react';
import { Check, Clock, ExternalLink, X } from 'lucide-react';
import type { NotificationDecision, NotificationItem } from '@/services/notification-service';
import { useSettingsStore } from '@/services/stores/settings-store';
import { useTranslation } from '@/utils/use-translation';
import { i18n } from '@/utils/i18n';

/** Terminal states, as the badge the recipient sees after the fact. */
const OUTCOME_KEY: Record<string, string> = {
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
  CANCELLED: 'Withdrawn',
  EXPIRED: 'Expired',
};

const OUTCOME_STYLE: Record<string, string> = {
  ACCEPTED: 'bg-emerald-500/15 text-emerald-500',
  REJECTED: 'bg-destructive/15 text-destructive',
  CANCELLED: 'bg-muted text-muted-foreground',
  EXPIRED: 'bg-amber-500/15 text-amber-500',
};

interface NotificationCardProps {
  item: NotificationItem;
  /** Current clock, passed in so a whole list agrees on one deadline computation. */
  now: number;
  busy: boolean;
  onOpen: (item: NotificationItem) => void;
  onRespond: (item: NotificationItem, decision: NotificationDecision) => void;
  /**
   * Whether the card advertises (and the page follows) its `url`.
   *
   * False for a host whose url is only a push deep link: the inbox itself is the destination,
   * and an external-link icon would promise a detail page that does not exist for this document.
   */
  showUrl?: boolean;
}

/**
 * One inbox row, shared by the desktop and the mobile page.
 *
 * `canRespond` / `canWithdraw` come from the server: which side of the event the caller is on is a
 * fact about the account, and re-deriving it here would put the authorization rule in two places.
 */
export const NotificationCard: React.FC<NotificationCardProps> = ({
  item,
  now,
  busy,
  onOpen,
  onRespond,
  showUrl = true,
}) => {
  const t = useTranslation();
  const language = useSettingsStore((state) => state.language);
  const meta = [item.actorName, item.scopeName].filter(Boolean).join(' · ');
  const outcome = OUTCOME_KEY[item.actionStatus];

  return (
    <article className="rounded-lg border border-border">
      <button
        type="button"
        onClick={() => onOpen(item)}
        className="w-full text-left px-3 py-2.5"
      >
        <div className="flex items-start gap-2">
          {/* The unread mark is a dot, not a weight change: bold text would reflow the line and
              make the list jump when an item is opened. */}
          {item.unread && <span className="mt-1.5 w-1.5 h-1.5 shrink-0 rounded-full bg-destructive" />}
          <span className="flex-1 min-w-0">
            <span className={`block text-sm break-words ${item.unread ? 'font-medium' : 'text-muted-foreground'}`}>
              {item.title}
            </span>
            {item.body && <span className="block mt-0.5 text-xs text-muted-foreground break-words">{item.body}</span>}
            <span className="block mt-1 flex items-center gap-2 text-[11px] text-muted-foreground">
              {meta && <span className="truncate">{meta}</span>}
              <span className="tabular-nums">{new Date(item.createdAt).toLocaleString()}</span>
              {item.actionStatus === 'PENDING' && item.expiresAt != null && (
                <span className="inline-flex items-center gap-1 tabular-nums" title={`${t('Deadline')}: ${new Date(item.expiresAt).toLocaleString()}`}>
                  <Clock className="w-3 h-3" />
                  {deadline(item.expiresAt, now, language)}
                </span>
              )}
              {showUrl && item.url && <ExternalLink className="w-3 h-3 shrink-0" />}
            </span>
          </span>
          {outcome && (
            <span className={`shrink-0 text-[11px] px-1.5 py-0.5 rounded ${OUTCOME_STYLE[item.actionStatus]}`}>
              {t(outcome)}
            </span>
          )}
        </div>
      </button>

      {(item.canRespond || item.canWithdraw) && (
        <div className="flex gap-2 px-3 pb-2.5">
          {item.canRespond && (
            <>
              <button
                type="button"
                disabled={busy}
                onClick={() => onRespond(item, 'ACCEPT')}
                className="inline-flex items-center gap-1 min-h-[36px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground text-xs disabled:opacity-50"
              >
                <Check className="w-3.5 h-3.5" />
                {t('Accept')}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => onRespond(item, 'REJECT')}
                className="inline-flex items-center gap-1 min-h-[36px] px-3 py-1.5 rounded-md border border-border text-xs hover:bg-muted disabled:opacity-50"
              >
                <X className="w-3.5 h-3.5" />
                {t('Reject')}
              </button>
            </>
          )}
          {item.canWithdraw && (
            <button
              type="button"
              disabled={busy}
              onClick={() => onRespond(item, 'WITHDRAW')}
              className="inline-flex items-center gap-1 min-h-[36px] px-3 py-1.5 rounded-md border border-border text-xs text-muted-foreground hover:bg-muted disabled:opacity-50"
            >
              {t('Withdraw')}
            </button>
          )}
        </div>
      )}
    </article>
  );
};

/**
 * "还剩 5 分钟" for a decision still in time, and the past-tense label once it is not.
 *
 * `Intl.RelativeTimeFormat` is used rather than a hand-built unit table: it localises correctly for
 * both shipped languages and needs no new copy keys per unit.
 */
function deadline(expiresAt: number, now: number, language: string): string {
  const remainingMs = expiresAt - now;
  if (remainingMs <= 0) return i18n('Expired');
  const locale = language === 'zh' ? 'zh-CN' : 'en';
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  const seconds = Math.ceil(remainingMs / 1_000);
  // Under a minute the minute bucket would read "this minute" for any remainder; seconds are exact.
  if (seconds < 60) return format.format(seconds, 'second');
  const minutes = Math.ceil(seconds / 60);
  // Keep minute precision up to two hours: 90 minutes must not become "in 2 hours".
  if (minutes < 120) return format.format(minutes, 'minute');
  return format.format(Math.round(minutes / 60), 'hour');
}
