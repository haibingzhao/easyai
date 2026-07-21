import type { SessionListItem } from '@/services/session-service';

export function groupSessionsByTime(sessions: SessionListItem[]): Record<string, SessionListItem[]> {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const weekAgo = today - 7 * 24 * 60 * 60 * 1000;

  const groups: Record<string, SessionListItem[]> = {
    today: [],
    thisWeek: [],
    older: [],
  };

  for (const s of sessions) {
    if (s.createdAt >= today) groups.today.push(s);
    else if (s.createdAt >= weekAgo) groups.thisWeek.push(s);
    else groups.older.push(s);
  }

  return groups;
}
