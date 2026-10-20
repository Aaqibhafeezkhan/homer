import { describe, expect, it, vi } from 'vitest';
import { layoutTopology } from './layout';

// dagre itself throws: the layout falls back to null (the render-cap fallback) instead of crashing the page.
vi.mock('@dagrejs/dagre', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dagrejs/dagre')>();
  return {
    ...actual,
    layout: () => {
      throw new Error('dagre failed');
    },
  };
});

describe('layoutTopology when dagre throws', () => {
  it('returns null', () => {
    expect(
      layoutTopology({
        nodes: [{ id: 'a' }, { id: 'b' }],
        edges: [{ source: 'a', target: 'b', label: null, conditional: false }],
      }),
    ).toBeNull();
  });
});
