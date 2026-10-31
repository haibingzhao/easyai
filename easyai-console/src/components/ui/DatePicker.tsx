import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CalendarDays, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { i18n } from '@/utils/i18n';

const PANEL_WIDTH = 288;
const PANEL_HEIGHT = 330;
const VIEWPORT_GAP = 8;

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

export function toDateValue(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function parseDateValue(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]) - 1;
  const day = Number(match[3]);
  const date = new Date(year, month, day, 12);
  return date.getFullYear() === year && date.getMonth() === month && date.getDate() === day
    ? date
    : null;
}

/** A Monday-first, six-week grid. Noon avoids daylight-saving date rollovers. */
export function calendarDates(month: Date): Date[] {
  const first = new Date(month.getFullYear(), month.getMonth(), 1, 12);
  const mondayOffset = (first.getDay() + 6) % 7;
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(first);
    date.setDate(index - mondayOffset + 1);
    return date;
  });
}

interface DatePickerProps {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  'aria-label'?: string;
}

export const DatePicker: React.FC<DatePickerProps> = ({ id, value, onChange, 'aria-label': ariaLabel }) => {
  const [open, setOpen] = useState(false);
  const [visibleMonth, setVisibleMonth] = useState(() => {
    const date = parseDateValue(value) ?? new Date();
    return new Date(date.getFullYear(), date.getMonth(), 1, 12);
  });
  const [position, setPosition] = useState({ top: 0, left: 0 });
  const anchorRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const updatePosition = useCallback(() => {
    const rect = anchorRef.current?.getBoundingClientRect();
    if (!rect) return;
    const left = Math.max(
      VIEWPORT_GAP,
      Math.min(rect.left, window.innerWidth - PANEL_WIDTH - VIEWPORT_GAP),
    );
    const below = rect.bottom + 6;
    const top = below + PANEL_HEIGHT <= window.innerHeight
      ? below
      : Math.max(VIEWPORT_GAP, rect.top - PANEL_HEIGHT - 6);
    setPosition({ top, left });
  }, []);

  const openCalendar = useCallback(() => {
    const date = parseDateValue(value) ?? new Date();
    setVisibleMonth(new Date(date.getFullYear(), date.getMonth(), 1, 12));
    updatePosition();
    setOpen(true);
  }, [updatePosition, value]);

  useEffect(() => {
    if (!open) return;
    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [open, updatePosition]);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (anchorRef.current?.contains(target) || panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  const selectDate = (date: Date) => {
    onChange(toDateValue(date));
    setOpen(false);
  };

  const selected = parseDateValue(value);
  const selectedValue = selected ? toDateValue(selected) : '';
  const todayValue = toDateValue(new Date());
  const weekdays = Array.from({ length: 7 }, (_, index) =>
    new Intl.DateTimeFormat(undefined, { weekday: 'short' }).format(new Date(2024, 0, index + 1, 12)),
  );

  return (
    <div ref={anchorRef} className="relative">
      <input
        id={id}
        type="date"
        aria-label={ariaLabel}
        autoComplete="off"
        className="w-full rounded-md border border-border bg-background px-3 py-2 pr-16 text-sm focus:outline-none focus:ring-1 focus:ring-ring [&::-webkit-calendar-picker-indicator]:hidden"
        value={value}
        onClick={openCalendar}
        onChange={(event) => onChange(event.target.value)}
      />
      {value && (
        <button
          type="button"
          aria-label={i18n('Clear')}
          title={i18n('Clear')}
          className="absolute right-9 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          onClick={() => {
            onChange('');
            setOpen(false);
          }}
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}
      <button
        type="button"
        aria-label={i18n('Select date')}
        aria-expanded={open}
        title={i18n('Select date')}
        className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
        onClick={() => (open ? setOpen(false) : openCalendar())}
      >
        <CalendarDays className="h-4 w-4" />
      </button>

      {open && createPortal(
        <div
          ref={panelRef}
          role="dialog"
          aria-label={i18n('Select date')}
          className="fixed z-[10000] w-72 rounded-lg border border-border bg-popover p-3 text-popover-foreground shadow-xl"
          style={{ top: position.top, left: position.left }}
        >
          <div className="mb-3 flex items-center justify-between">
            <button
              type="button"
              aria-label={i18n('Previous month')}
              className="rounded-md p-1.5 hover:bg-muted"
              onClick={() => setVisibleMonth(new Date(visibleMonth.getFullYear(), visibleMonth.getMonth() - 1, 1, 12))}
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <div className="text-sm font-medium">
              {new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'long' }).format(visibleMonth)}
            </div>
            <button
              type="button"
              aria-label={i18n('Next month')}
              className="rounded-md p-1.5 hover:bg-muted"
              onClick={() => setVisibleMonth(new Date(visibleMonth.getFullYear(), visibleMonth.getMonth() + 1, 1, 12))}
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
          <div className="grid grid-cols-7 gap-1 text-center text-xs text-muted-foreground">
            {weekdays.map((weekday, index) => <div key={`${weekday}-${index}`} className="py-1">{weekday}</div>)}
          </div>
          <div className="grid grid-cols-7 gap-1">
            {calendarDates(visibleMonth).map((date) => {
              const dateValue = toDateValue(date);
              const isSelected = dateValue === selectedValue;
              const isToday = dateValue === todayValue;
              const isOutside = date.getMonth() !== visibleMonth.getMonth();
              return (
                <button
                  key={dateValue}
                  type="button"
                  aria-label={dateValue}
                  aria-pressed={isSelected}
                  className={`h-8 rounded-md text-sm transition-colors hover:bg-muted ${
                    isSelected
                      ? 'bg-primary text-primary-foreground hover:bg-primary/90'
                      : isOutside
                        ? 'text-muted-foreground/50'
                        : 'text-foreground'
                  } ${isToday && !isSelected ? 'ring-1 ring-inset ring-primary' : ''}`}
                  onClick={() => selectDate(date)}
                >
                  {date.getDate()}
                </button>
              );
            })}
          </div>
          <div className="mt-3 flex items-center justify-between border-t border-border pt-2">
            <button
              type="button"
              className="rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
              onClick={() => {
                onChange('');
                setOpen(false);
              }}
            >
              {i18n('Clear')}
            </button>
            <button
              type="button"
              className="rounded-md px-2 py-1 text-xs text-primary hover:bg-muted"
              onClick={() => selectDate(new Date())}
            >
              {i18n('Today')}
            </button>
          </div>
        </div>,
        document.body,
      )}
    </div>
  );
};
