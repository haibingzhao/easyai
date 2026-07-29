import { useState, useEffect } from 'react';

interface TokenInputProps {
  /** Value in raw tokens */
  value: number | undefined;
  onChange: (value: number | undefined) => void;
  placeholder?: string;
  className?: string;
}

type Unit = 'K' | 'M';

/**
 * A number input with a K/M unit selector.
 * The value is always stored/emitted in raw tokens; the display converts to/from the selected unit.
 */
export function TokenInput({ value, onChange, placeholder, className }: TokenInputProps) {
  const [unit, setUnit] = useState<Unit>(detectUnit(value));
  const [raw, setRaw] = useState(() => toDisplay(value, detectUnit(value)));

  // Sync display when value changes externally (e.g. form reset)
  useEffect(() => {
    const u = detectUnit(value);
    setUnit(u);
    setRaw(toDisplay(value, u));
  }, [value]);

  const switchUnit = (newUnit: Unit) => {
    if (newUnit === unit) return;
    const parsed = parseFloat(raw);
    if (!isNaN(parsed) && parsed > 0) {
      // Keep the actual token amount the same, just change display unit
      const tokens = Math.round(unit === 'M' ? parsed * 1024 * 1024 : parsed * 1024);
      setRaw(toDisplay(tokens, newUnit));
    }
    setUnit(newUnit);
  };

  const commit = () => {
    const parsed = parseFloat(raw);
    if (raw.trim() === '' || isNaN(parsed) || parsed <= 0) {
      onChange(undefined);
      return;
    }
    const tokens = Math.round(unit === 'M' ? parsed * 1024 * 1024 : parsed * 1024);
    onChange(tokens);
    setRaw(toDisplay(tokens, unit));
  };

  return (
    <div className="flex gap-1">
      <input
        type="number"
        min="1"
        step="1"
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => { if (e.key === 'Enter') commit(); }}
        placeholder={placeholder}
        className={className}
      />
      <select
        value={unit}
        onChange={(e) => switchUnit(e.target.value as Unit)}
        className="h-9 rounded-md border border-input bg-transparent px-1.5 text-sm"
      >
        <option value="K">K</option>
        <option value="M">M</option>
      </select>
    </div>
  );
}

function detectUnit(value: number | undefined): Unit {
  return value != null && value > 0 && value % (1024 * 1024) === 0 ? 'M' : 'K';
}

function toDisplay(value: number | undefined, unit: Unit): string {
  if (value == null) return '';
  return unit === 'M' ? String(value / (1024 * 1024)) : String(value / 1024);
}
