import { describe, expect, it } from 'vitest';
import { calendarDates, parseDateValue, toDateValue } from './DatePicker';

describe('DatePicker calendar dates', () => {
  it('builds a Monday-first six-week grid', () => {
    const days = calendarDates(new Date(2026, 9, 1, 12));
    expect(days).toHaveLength(42);
    expect(toDateValue(days[0])).toBe('2026-09-28');
    expect(toDateValue(days[41])).toBe('2026-11-08');
    expect(days[0].getDay()).toBe(1);
  });

  it('includes leap day and rejects impossible dates', () => {
    expect(toDateValue(parseDateValue('2024-02-29')!)).toBe('2024-02-29');
    expect(parseDateValue('2026-02-29')).toBeNull();
    expect(parseDateValue('2026-13-01')).toBeNull();
  });
});
