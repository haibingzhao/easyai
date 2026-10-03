/**
 * Theme contract for render_visual fragments — the single source of truth.
 *
 * Fragments reference these variable names only (never hex values) and stay
 * theme-agnostic: the host injects the table matching the active mode into the
 * sandbox `:root` and swaps values on theme change. Base values mirror the
 * console tokens in src/index.css; the palette encodes the doc rule
 * (light: 50 fill / 600 stroke / 800 title / 600 subtitle,
 *  dark: 800 fill / 200 stroke / 100 title / 200 subtitle).
 */

export type VisualThemeMode = 'light' | 'dark';

export type VisualTokens = Record<string, string>;

type PaletteStep = '50' | '100' | '200' | '600' | '800';

export const VISUAL_FAMILIES = [
  'neutral',
  'blue',
  'green',
  'red',
  'amber',
  'purple',
  'cyan',
  'pink',
  'teal',
] as const;

export type VisualFamily = (typeof VISUAL_FAMILIES)[number];

const PALETTE: Record<VisualFamily, Record<PaletteStep, string>> = {
  neutral: { 50: '#FAFAF9', 100: '#F5F5F4', 200: '#E7E5E4', 600: '#57534E', 800: '#292524' },
  blue: { 50: '#EFF6FF', 100: '#DBEAFE', 200: '#BFDBFE', 600: '#2563EB', 800: '#1E40AF' },
  green: { 50: '#F0FDF4', 100: '#DCFCE7', 200: '#BBF7D0', 600: '#16A34A', 800: '#166534' },
  red: { 50: '#FEF2F2', 100: '#FEE2E2', 200: '#FECACA', 600: '#DC2626', 800: '#991B1B' },
  amber: { 50: '#FFFBEB', 100: '#FEF3C7', 200: '#FDE68A', 600: '#D97706', 800: '#92400E' },
  purple: { 50: '#FAF5FF', 100: '#F3E8FF', 200: '#E9D5FF', 600: '#9333EA', 800: '#6B21A8' },
  cyan: { 50: '#ECFEFF', 100: '#CFFAFE', 200: '#A5F3FC', 600: '#0891B2', 800: '#155E75' },
  pink: { 50: '#FDF2F8', 100: '#FCE7F3', 200: '#FBCFE8', 600: '#DB2777', 800: '#9D174D' },
  teal: { 50: '#F0FDFA', 100: '#CCFBF1', 200: '#99F6E4', 600: '#0D9488', 800: '#115E59' },
};

function paletteTokens(mode: VisualThemeMode): VisualTokens {
  const tokens: VisualTokens = {};
  for (const family of VISUAL_FAMILIES) {
    const steps = PALETTE[family];
    if (mode === 'light') {
      tokens[`--viz-${family}-fill`] = steps['50'];
      tokens[`--viz-${family}-stroke`] = steps['600'];
      tokens[`--viz-${family}-title`] = steps['800'];
      tokens[`--viz-${family}-subtitle`] = steps['600'];
    } else {
      tokens[`--viz-${family}-fill`] = steps['800'];
      tokens[`--viz-${family}-stroke`] = steps['200'];
      tokens[`--viz-${family}-title`] = steps['100'];
      tokens[`--viz-${family}-subtitle`] = steps['200'];
    }
  }
  return tokens;
}

const FONT_SANS =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";
const FONT_MONO = 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace';
const FONT_SERIF = 'Georgia, Cambria, "Times New Roman", Times, serif';

const LIGHT_TOKENS: VisualTokens = {
  '--color-background-primary': 'oklch(1 0 0)',
  '--color-background-secondary': 'oklch(0.967 0.001 286.375)',
  '--color-background-tertiary': 'oklch(1 0 0)',
  '--color-text-primary': 'oklch(0.145 0 0)',
  '--color-text-secondary': 'oklch(0.551 0.016 285.938)',
  '--color-text-tertiary': 'oklch(0.62 0.016 285.938)',
  '--color-border-tertiary': 'oklch(0.922 0 0)',
  '--color-border-secondary': 'color-mix(in oklab, oklch(0.922 0 0) 65%, oklch(0.145 0 0))',
  '--color-border-primary': 'color-mix(in oklab, oklch(0.922 0 0) 35%, oklch(0.145 0 0))',
  '--color-background-info': 'oklch(0.95 0.03 250)',
  '--color-text-info': 'oklch(0.5 0.15 250)',
  '--color-background-danger': 'oklch(0.95 0.03 27)',
  '--color-text-danger': 'oklch(0.577 0.245 27.325)',
  '--color-background-success': 'oklch(0.95 0.04 150)',
  '--color-text-success': 'oklch(0.45 0.12 150)',
  '--color-background-warning': 'oklch(0.95 0.04 85)',
  '--color-text-warning': 'oklch(0.5 0.12 85)',
  '--font-sans': FONT_SANS,
  '--font-mono': FONT_MONO,
  '--font-serif': FONT_SERIF,
  '--border-radius-md': '0.375rem',
  '--border-radius-lg': '0.5rem',
  '--border-radius-xl': '0.75rem',
  ...paletteTokens('light'),
};

const DARK_TOKENS: VisualTokens = {
  '--color-background-primary': 'oklch(0.145 0 0)',
  '--color-background-secondary': 'oklch(0.269 0 0)',
  '--color-background-tertiary': 'oklch(0.145 0 0)',
  '--color-text-primary': 'oklch(0.985 0 0)',
  '--color-text-secondary': 'oklch(0.708 0 0)',
  '--color-text-tertiary': 'oklch(0.65 0 0)',
  '--color-border-tertiary': 'oklch(0.269 0 0)',
  '--color-border-secondary': 'color-mix(in oklab, oklch(0.269 0 0) 65%, oklch(0.985 0 0))',
  '--color-border-primary': 'color-mix(in oklab, oklch(0.269 0 0) 35%, oklch(0.985 0 0))',
  '--color-background-info': 'oklch(0.3 0.06 250)',
  '--color-text-info': 'oklch(0.8 0.08 250)',
  '--color-background-danger': 'oklch(0.3 0.08 25)',
  '--color-text-danger': 'oklch(0.8 0.08 25)',
  '--color-background-success': 'oklch(0.3 0.06 150)',
  '--color-text-success': 'oklch(0.8 0.08 150)',
  '--color-background-warning': 'oklch(0.3 0.06 85)',
  '--color-text-warning': 'oklch(0.85 0.08 85)',
  '--font-sans': FONT_SANS,
  '--font-mono': FONT_MONO,
  '--font-serif': FONT_SERIF,
  '--border-radius-md': '0.375rem',
  '--border-radius-lg': '0.5rem',
  '--border-radius-xl': '0.75rem',
  ...paletteTokens('dark'),
};

export function tokensForMode(mode: VisualThemeMode): VisualTokens {
  return mode === 'dark' ? DARK_TOKENS : LIGHT_TOKENS;
}

export function serializeTokens(tokens: VisualTokens): string {
  return Object.entries(tokens)
    .map(([key, value]) => `${key}: ${value};`)
    .join('\n  ');
}
