import { describe, expect, it } from 'vitest';
import { formatTokenWindow } from '../format';

describe('formatTokenWindow', () => {
    it('keeps exact binary multiples compact', () => {
        expect(formatTokenWindow(1024 * 1024)).toBe('1M');
        expect(formatTokenWindow(256 * 1024)).toBe('256K');
    });

    it('floors non-multiples to a whole K instead of leaking decimals', () => {
        expect(formatTokenWindow(200_000)).toBe('195K');
    });

    it('keeps small windows as raw tokens', () => {
        expect(formatTokenWindow(512)).toBe('512');
    });
});
