/**
 * Spring `CronExpression` compatible parser and next-run calculator, mirroring the backend
 * validator (`MemoryController.validateCron`). Six fields: second minute hour day-of-month
 * month day-of-week. Supports `*`, lists, ranges, increments, month/day names and the
 * `@daily`-style macros; `?` is rejected exactly as Spring rejects it.
 */

export interface CronSpec {
  seconds: number[];
  minutes: number[];
  hours: number[];
  daysOfMonth: number[];
  months: number[];
  /** 0 = Sunday … 6 = Saturday; a literal 7 is normalized to 0. */
  daysOfWeek: number[];
  domRestricted: boolean;
  dowRestricted: boolean;
}

const MONTH_NAMES: Record<string, number> = {
  JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6,
  JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12,
};

const DOW_NAMES: Record<string, number> = {
  SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6,
};

const MACROS: Record<string, string> = {
  '@yearly': '0 0 0 1 1 *',
  '@annually': '0 0 0 1 1 *',
  '@monthly': '0 0 0 1 * *',
  '@weekly': '0 0 0 * * 0',
  '@daily': '0 0 0 * * *',
  '@midnight': '0 0 0 * * *',
  '@hourly': '0 0 * * * *',
};

function resolveToken(token: string, min: number, max: number, names: Record<string, number>): number | null {
  const upper = token.toUpperCase();
  if (names[upper] !== undefined) return names[upper];
  if (!/^\d+$/.test(token)) return null;
  const value = parseInt(token, 10);
  return value >= min && value <= max ? value : null;
}

function parseField(raw: string, min: number, max: number, names: Record<string, number>): number[] | null {
  const values = new Set<number>();
  for (const part of raw.split(',')) {
    const token = part.trim();
    if (!token) return null;

    let base = token;
    let step = 1;
    const slash = token.indexOf('/');
    if (slash >= 0) {
      base = token.slice(0, slash);
      const stepText = token.slice(slash + 1);
      if (!/^\d+$/.test(stepText)) return null;
      step = parseInt(stepText, 10);
      if (step < 1) return null;
    }

    let lo: number;
    let hi: number;
    if (base === '*') {
      lo = min;
      hi = max;
    } else {
      const dash = base.indexOf('-');
      if (dash > 0) {
        const a = resolveToken(base.slice(0, dash), min, max, names);
        const b = resolveToken(base.slice(dash + 1), min, max, names);
        if (a === null || b === null || a > b) return null;
        lo = a;
        hi = b;
      } else if (dash === 0) {
        return null;
      } else {
        const single = resolveToken(base, min, max, names);
        if (single === null) return null;
        lo = single;
        hi = single;
      }
    }

    for (let v = lo; v <= hi; v += step) values.add(v);
  }
  return values.size > 0 ? [...values].sort((a, b) => a - b) : null;
}

/** Parse a cron expression; null when the backend would reject it. */
export function parseCron(expression: string): CronSpec | null {
  const trimmed = expression.trim();
  const expanded = MACROS[trimmed.toLowerCase()] ?? trimmed;
  const fields = expanded.split(/\s+/);
  if (fields.length !== 6) return null;

  const seconds = parseField(fields[0], 0, 59, {});
  const minutes = parseField(fields[1], 0, 59, {});
  const hours = parseField(fields[2], 0, 23, {});
  const daysOfMonth = parseField(fields[3], 1, 31, {});
  const months = parseField(fields[4], 1, 12, MONTH_NAMES);
  const rawDow = parseField(fields[5], 0, 7, DOW_NAMES);
  if (!seconds || !minutes || !hours || !daysOfMonth || !months || !rawDow) return null;

  const daysOfWeek = [...new Set(rawDow.map((d) => (d === 7 ? 0 : d)))].sort((a, b) => a - b);

  return {
    seconds,
    minutes,
    hours,
    daysOfMonth,
    months,
    daysOfWeek,
    domRestricted: fields[3] !== '*',
    dowRestricted: fields[5] !== '*',
  };
}

function dayMatches(spec: CronSpec, day: Date): boolean {
  const dom = day.getDate();
  const dow = day.getDay();
  if (spec.domRestricted && spec.dowRestricted) {
    return spec.daysOfMonth.includes(dom) || spec.daysOfWeek.includes(dow);
  }
  if (spec.domRestricted) return spec.daysOfMonth.includes(dom);
  if (spec.dowRestricted) return spec.daysOfWeek.includes(dow);
  return true;
}

function nextTimeOfDay(
  spec: CronSpec,
  fromHour: number,
  fromMinute: number,
  fromSecond: number,
  exclusive: boolean
): [number, number, number] | null {
  for (const hour of spec.hours) {
    if (hour < fromHour) continue;
    const minuteFloor = hour === fromHour ? fromMinute : spec.minutes[0];
    for (const minute of spec.minutes) {
      if (minute < minuteFloor) continue;
      const secondFloor = hour === fromHour && minute === fromMinute ? fromSecond : spec.seconds[0];
      for (const second of spec.seconds) {
        if (second < secondFloor) continue;
        if (exclusive && hour === fromHour && minute === fromMinute && second === fromSecond) continue;
        return [hour, minute, second];
      }
    }
  }
  return null;
}

/** The first run strictly after `after`, or null when none falls within ~4 years. */
function nextAfter(spec: CronSpec, after: Date): Date | null {
  const baseDay = new Date(after);
  baseDay.setHours(0, 0, 0, 0);

  for (let offset = 0; offset < 4 * 366; offset++) {
    const day = new Date(baseDay);
    day.setDate(baseDay.getDate() + offset);
    if (!spec.months.includes(day.getMonth() + 1)) continue;
    if (!dayMatches(spec, day)) continue;

    const sameDay = offset === 0;
    const tod = nextTimeOfDay(
      spec,
      sameDay ? after.getHours() : 0,
      sameDay ? after.getMinutes() : 0,
      sameDay ? after.getSeconds() : 0,
      sameDay
    );
    if (!tod) continue;

    const candidate = new Date(day);
    candidate.setHours(tod[0], tod[1], tod[2], 0);
    return candidate;
  }
  return null;
}

/** Up to `count` upcoming run times for a valid expression; empty when invalid or unreachable. */
export function nextOccurrences(expression: string, count: number, from: Date = new Date()): Date[] {
  const spec = parseCron(expression);
  if (!spec) return [];

  const runs: Date[] = [];
  let cursor = new Date(from);
  cursor.setMilliseconds(0);
  for (let i = 0; i < count; i++) {
    const next = nextAfter(spec, cursor);
    if (!next) break;
    runs.push(next);
    cursor = next;
  }
  return runs;
}
