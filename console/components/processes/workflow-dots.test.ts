import { describe, expect, it } from 'vitest';
import { dotRow } from './workflow-dots';

describe('dotRow', () => {
  it('centres a short row inside the box', () => {
    const row = dotRow(['ok', 'running'], 100);
    expect(row.map((d) => d.state)).toEqual(['ok', 'running']);
    expect((row[0].cx + row[1].cx) / 2).toBeCloseTo(50);
  });
  it('drops the oldest dots when the row cannot fit, keeping the latest', () => {
    const states = Array.from({ length: 40 }, (_, i) => (i === 39 ? 'running' : 'ok'));
    const row = dotRow(states, 60);
    expect(row.length).toBeLessThan(40);
    expect(row[row.length - 1].state).toBe('running');
    for (const dot of row) {
      expect(dot.cx).toBeGreaterThanOrEqual(4);
      expect(dot.cx).toBeLessThanOrEqual(56);
    }
  });
  it('returns nothing for no states', () => {
    expect(dotRow([], 100)).toEqual([]);
  });
});
