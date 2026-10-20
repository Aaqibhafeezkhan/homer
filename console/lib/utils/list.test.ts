import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { splitCommaList } from './list';

describe('splitCommaList', () => {
  it('returns no items for an absent or blank value', () => {
    expect(splitCommaList(undefined)).toEqual([]);
    expect(splitCommaList(null)).toEqual([]);
    expect(splitCommaList('')).toEqual([]);
    expect(splitCommaList(' , ,, ')).toEqual([]);
  });

  it('trims each item and drops empty ones, keeping order and duplicates', () => {
    expect(splitCommaList(' b , a,,b ,')).toEqual(['b', 'a', 'b']);
  });

  it('keeps inner whitespace and every non-comma character of an item', () => {
    expect(splitCommaList("a b ,'x':y")).toEqual(['a b', "'x':y"]);
  });

  // Property: whatever the input, every item is trimmed, non-empty and comma-free.
  it('only ever yields trimmed, non-empty, comma-free items', () => {
    fc.assert(
      fc.property(fc.string(), (raw) => {
        for (const item of splitCommaList(raw)) {
          expect(item).toBe(item.trim());
          expect(item.length).toBeGreaterThan(0);
          expect(item).not.toContain(',');
        }
      }),
    );
  });

  // Property: joining items with commas and padding round-trips to the items
  // that are non-blank, trimmed — nothing is lost, merged or invented.
  it('round-trips comma-free items joined with padded commas', () => {
    const item = fc.string().filter((s) => !s.includes(','));
    const padding = fc.constantFrom('', ' ', '\t', '  ');
    fc.assert(
      fc.property(fc.array(fc.tuple(padding, item, padding)), (parts) => {
        const raw = parts.map(([before, s, after]) => `${before}${s}${after}`).join(',');
        const expected = parts.map(([, s]) => s.trim()).filter((s) => s.length > 0);
        expect(splitCommaList(raw)).toEqual(expected);
      }),
    );
  });
});
