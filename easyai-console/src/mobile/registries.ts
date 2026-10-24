import type React from 'react';

/**
 * Mobile bottom-tab entry.
 *
 * Same shape as the desktop `NavItem` so a host can describe one section once and
 * register it in both places. `order` sorts the tab bar because push order is
 * accidental (a host splices its entries in whenever its module is imported).
 */
export interface MobileTabItem {
  id: string;
  label: string;
  labelKey: string;
  /** Icon name resolved against the built-in mobile map, then `ICON_REGISTRY`. */
  icon: string;
  path: string;
  /** Lower sorts first. Ties keep registration order. */
  order?: number;
  visible?: () => boolean;
}

/** Entry in the "More" page list — hosts register every section the tab bar cannot fit. */
export interface MobileMoreItem {
  id: string;
  label: string;
  labelKey: string;
  icon: string;
  path: string;
  order?: number;
  visible?: () => boolean;
}

/** Host section rendered at the foot of the tasks sheet. */
export type MobileSheetItem = React.ComponentType;

/**
 * Host entry in the composer "+" menu.
 *
 * `onSelect` gets the only two mutations a host needs from the composer: insert text
 * (a mention chip, a prompt fragment) and attach a file. Keeping the surface this small
 * means a host never has to reach into the editor's contenteditable internals.
 */
export interface MobileComposerPlusItem {
  id: string;
  label: string;
  labelKey: string;
  icon: string;
  onSelect: (ctx: MobileComposerContext) => void | Promise<void>;
  visible?: () => boolean;
}

export interface MobileComposerContext {
  addText: (text: string) => void;
  addAttachment: (file: File) => void;
}

/**
 * Mutable host registries — same idiom as `EXTRA_NAV_ITEMS` / `TOPBAR_EXTRA_ITEMS` /
 * `SETTINGS_MENU_ITEMS`: a consumer mutates in place before render, reassigning the
 * binding is a no-op for the framework.
 */
export const MOBILE_TAB_ITEMS: MobileTabItem[] = [];

export const MOBILE_MORE_ITEMS: MobileMoreItem[] = [];

export const MOBILE_SHEET_ITEMS: MobileSheetItem[] = [];

export const MOBILE_COMPOSER_PLUS_ITEMS: MobileComposerPlusItem[] = [];

/**
 * Host components rendered in a slim strip above the page content, at the
 * leading edge. Intended for persistent status that belongs to every page
 * (e.g. a connection/latency chip); hosts that register nothing get the exact
 * layout they had before.
 */
export const MOBILE_TOP_ITEMS: React.ComponentType[] = [];

/** Tab bar slots available before the fixed "More" slot takes one. */
export const MOBILE_TAB_MAX = 4;

/**
 * Framework-owned tabs, always first: the conversation is the product on a phone, and
 * history needs to stay reachable no matter what a host registers. Mutable like
 * `MOBILE_TAB_ITEMS` — a host that wants its own sections to own the four slots splices these out
 * before render.
 */
export const MOBILE_BUILT_IN_TAB_ITEMS: MobileTabItem[] = [
  { id: 'mobile-chat', label: 'Chat', labelKey: 'Chat', icon: 'MessageSquare', path: '/chat', order: -2 },
  { id: 'mobile-history', label: 'History', labelKey: 'History', icon: 'Clock', path: '/history', order: -1 },
];

/**
 * Tabs to render: built-ins + host entries, filtered by `visible`, sorted by `order`,
 * truncated to `MOBILE_TAB_MAX`. Truncation is deliberate — a fifth tab plus "More"
 * leaves ~65pt per label on a 390pt screen, which clips CJK labels.
 */
export function resolveMobileTabs(items: MobileTabItem[]): MobileTabItem[] {
  const all = [...MOBILE_BUILT_IN_TAB_ITEMS, ...items];
  const seen = new Set<string>();
  const unique = all.filter((item) => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
  return unique
    .filter((item) => item.visible?.() !== false)
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .slice(0, MOBILE_TAB_MAX);
}

/**
 * "More" page rows: host entries only. The framework's own sections (profile, display,
 * language, connection) live on the profile page instead, so they never compete with
 * host content for this list.
 */
export function resolveMoreItems(items: MobileMoreItem[]): MobileMoreItem[] {
  return items
    .filter((item) => item.visible?.() !== false)
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
}
