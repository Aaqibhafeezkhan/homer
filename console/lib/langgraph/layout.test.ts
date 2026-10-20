import { describe, expect, it } from 'vitest';
import { MAX_RENDER_NODES, layoutTopology, smoothPath, truncateLabel } from './layout';
import type { LangGraphTopology } from '@/types/homer';

const chain: LangGraphTopology = {
  nodes: [{ id: '__start__' }, { id: 'plan' }, { id: 'write' }, { id: '__end__' }],
  edges: [
    { source: '__start__', target: 'plan', label: null, conditional: false },
    { source: 'plan', target: 'write', label: null, conditional: false },
    { source: 'write', target: '__end__', label: 'done', conditional: true },
  ],
};

describe('layoutTopology', () => {
  it('lays a chain out top to bottom', () => {
    const layout = layoutTopology(chain)!;
    const y = Object.fromEntries(layout.nodes.map((n) => [n.id, n.y]));
    expect(y.plan).toBeGreaterThan(y.__start__);
    expect(y.write).toBeGreaterThan(y.plan);
    expect(y.__end__).toBeGreaterThan(y.write);
    expect(layout.width).toBeGreaterThan(0);
    expect(layout.height).toBeGreaterThan(0);
  });

  it('renames start/end and marks them terminal', () => {
    const nodes = Object.fromEntries(layoutTopology(chain)!.nodes.map((n) => [n.id, n]));
    expect(nodes.__start__).toMatchObject({ label: 'start', terminal: true });
    expect(nodes.__end__).toMatchObject({ label: 'end', terminal: true });
    expect(nodes.plan).toMatchObject({ label: 'plan', terminal: false });
  });

  it('keeps conditional flags and labels on edges', () => {
    const edge = layoutTopology(chain)!.edges.find((e) => e.label === 'done')!;
    expect(edge.conditional).toBe(true);
    expect(edge.path.startsWith('M')).toBe(true);
  });

  it('keeps both of two edges between the same pair, and a self-loop', () => {
    const layout = layoutTopology({
      nodes: [{ id: 'a' }, { id: 'b' }],
      edges: [
        { source: 'a', target: 'b', label: 'x', conditional: true },
        { source: 'a', target: 'b', label: 'y', conditional: true },
        { source: 'b', target: 'b', label: 'again', conditional: true },
      ],
    })!;
    expect(layout.edges.map((e) => e.label).sort()).toEqual(['again', 'x', 'y']);
  });

  it('draws a self-loop attached to the right side of its own node', () => {
    // dagre's own self-edge points land away from the node (a dangling zig-zag).
    const layout = layoutTopology({
      nodes: [{ id: 'a' }, { id: 'b' }],
      edges: [
        { source: 'a', target: 'b', label: null, conditional: false },
        { source: 'b', target: 'b', label: 'again', conditional: true },
      ],
    })!;
    const b = layout.nodes.find((n) => n.id === 'b')!;
    const loop = layout.edges.find((e) => e.label === 'again')!;
    const nums = loop.path.match(/-?[\d.]+/g)!.map(Number);
    const [startX, startY] = nums.slice(0, 2);
    const [endX, endY] = nums.slice(-2);
    const right = b.x + b.width;
    for (const [x, y] of [
      [startX, startY],
      [endX, endY],
    ]) {
      expect(x).toBeCloseTo(right);
      expect(y).toBeGreaterThan(b.y);
      expect(y).toBeLessThan(b.y + b.height);
    }
    expect(loop.labelX - loop.labelWidth / 2).toBeGreaterThan(right);
    expect(loop.labelY).toBeCloseTo(b.y + b.height / 2);
    expect(layout.width).toBeGreaterThanOrEqual(loop.labelX + loop.labelWidth / 2);
  });

  it('keeps a labeled self-loop clear of its same-rank neighbour', () => {
    // Loops are drawn after dagre, so dagre must reserve the room they take.
    const layout = layoutTopology({
      nodes: [{ id: 'root' }, { id: 'busy' }, { id: 'idle' }],
      edges: [
        { source: 'root', target: 'idle', label: null, conditional: false },
        { source: 'root', target: 'busy', label: null, conditional: false },
        { source: 'busy', target: 'busy', label: 'retry', conditional: true },
      ],
    })!;
    const busy = layout.nodes.find((n) => n.id === 'busy')!;
    const idle = layout.nodes.find((n) => n.id === 'idle')!;
    const loop = layout.edges.find((e) => e.label === 'retry')!;
    expect(idle.y).toBe(busy.y);
    expect(idle.x).toBeGreaterThan(busy.x);
    expect(loop.labelX + loop.labelWidth / 2).toBeLessThan(idle.x);
    // Tight: the reservation buys the label its clearance, not extra spacing.
    expect(idle.x - (loop.labelX + loop.labelWidth / 2)).toBeLessThanOrEqual(8 + 0.5);
    expect(layout.width).toBeGreaterThanOrEqual(idle.x + idle.width);
  });

  it('nests two self-loops on one node and stacks their labels', () => {
    const layout = layoutTopology({
      nodes: [{ id: 'a' }],
      edges: [
        { source: 'a', target: 'a', label: 'retry', conditional: true },
        { source: 'a', target: 'a', label: 'again', conditional: true },
      ],
    })!;
    const a = layout.nodes[0];
    const [first, second] = layout.edges;
    expect(first.path).not.toBe(second.path);
    const top = (e: typeof first) => e.labelY - 8;
    const bottom = (e: typeof first) => e.labelY + 8;
    const disjoint = bottom(first) <= top(second) || bottom(second) <= top(first);
    expect(disjoint).toBe(true);
    expect((first.labelY + second.labelY) / 2).toBeCloseTo(a.y + a.height / 2);
    const loopRight = (e: typeof first) => Math.max(...e.path.match(/-?[\d.]+/g)!.map(Number).filter((_, i) => i % 2 === 0));
    const outer = Math.max(loopRight(first), loopRight(second));
    for (const e of [first, second]) expect(e.labelX - e.labelWidth / 2).toBeGreaterThan(outer);
  });

  it('ends edges on the real rect of a node that reserves room for a self-loop label', () => {
    // The long label makes the reserved box much wider than the node itself.
    const layout = layoutTopology({
      nodes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      edges: [
        { source: 'a', target: 'b', label: null, conditional: false },
        { source: 'b', target: 'c', label: null, conditional: false },
        { source: 'b', target: 'b', label: 'again and again and again', conditional: true },
      ],
    })!;
    const b = layout.nodes.find((n) => n.id === 'b')!;
    const onRect = ([x, y]: number[]) => {
      const eps = 0.01;
      const inX = x >= b.x - eps && x <= b.x + b.width + eps;
      const inY = y >= b.y - eps && y <= b.y + b.height + eps;
      const onTopOrBottom = Math.abs(y - b.y) < eps || Math.abs(y - (b.y + b.height)) < eps;
      const onSide = Math.abs(x - b.x) < eps || Math.abs(x - (b.x + b.width)) < eps;
      return (inX && onTopOrBottom) || (inY && onSide);
    };
    const nums = (e: { path: string }) => e.path.match(/-?[\d.]+/g)!.map(Number);
    const into = layout.edges.find((e) => e.source === 'a')!;
    const outOf = layout.edges.find((e) => e.source === 'b' && e.target === 'c')!;
    expect(onRect(nums(into).slice(-2))).toBe(true);
    expect(onRect(nums(outOf).slice(0, 2))).toBe(true);
  });

  it('carries the real source/target ids on an edge and on a self-loop', () => {
    const layout = layoutTopology({
      nodes: [{ id: 'a' }, { id: 'b' }],
      edges: [
        { source: 'a', target: 'b', label: null, conditional: false },
        { source: 'b', target: 'b', label: 'again', conditional: true },
      ],
    })!;
    const edge = layout.edges.find((e) => e.label === null)!;
    expect(edge.source).toBe('a');
    expect(edge.target).toBe('b');
    const loop = layout.edges.find((e) => e.label === 'again')!;
    expect(loop.source).toBe('b');
    expect(loop.target).toBe('b');
  });

  it('carries the real source/target for a node named constructor', () => {
    // Object.prototype's own "constructor" must not leak through as a
    // dagre-facing key; the real id still has to come back out on the edge.
    const layout = layoutTopology({
      nodes: [{ id: 'constructor' }, { id: 'b' }],
      edges: [{ source: 'constructor', target: 'b', label: null, conditional: false }],
    })!;
    expect(layout.edges[0].source).toBe('constructor');
    expect(layout.edges[0].target).toBe('b');
  });

  it('drops edges to unknown nodes and duplicate node ids', () => {
    const layout = layoutTopology({
      nodes: [{ id: 'a' }, { id: 'a' }],
      edges: [{ source: 'a', target: 'ghost', label: null, conditional: false }],
    })!;
    expect(layout.nodes).toHaveLength(1);
    expect(layout.edges).toHaveLength(0);
  });

  it('returns null above the render cap', () => {
    const nodes = Array.from({ length: MAX_RENDER_NODES + 1 }, (_, i) => ({ id: `n${i}` }));
    expect(layoutTopology({ nodes, edges: [] })).toBeNull();
  });

  it('caps edges at 300: null at 301, laid out at exactly 300', () => {
    // Dagre's cost grows steeply with edges; the cap keeps the main thread responsive.
    const nodes = Array.from({ length: 103 }, (_, i) => ({ id: `n${i}` }));
    const edgesOf = (count: number) =>
      Array.from({ length: count }, (_, j) => ({
        source: `n${j % 100}`,
        target: `n${(j % 100) + 1 + Math.floor(j / 100)}`,
        label: null,
        conditional: false,
      }));
    expect(layoutTopology({ nodes, edges: edgesOf(301) })).toBeNull();
    expect(layoutTopology({ nodes, edges: edgesOf(300) })).not.toBeNull();
  });

  it('lays out nodes named after Object.prototype members without crashing', () => {
    // Node ids are agent-authored; dagre/graphlib object-keys internally, so a
    // real id must never become a dagre-facing key (§12 rule 2) — this is the
    // installed dagre crashing today without the internal-id indirection.
    const ids = ['constructor', 'toString', '__proto__', 'hasOwnProperty'];
    const layout = layoutTopology({
      nodes: ids.map((id) => ({ id })),
      edges: [
        { source: 'constructor', target: 'toString', label: null, conditional: false },
        { source: 'toString', target: '__proto__', label: null, conditional: false },
        { source: '__proto__', target: 'hasOwnProperty', label: null, conditional: false },
      ],
    });
    expect(layout).not.toBeNull();
    expect(layout!.nodes.map((n) => n.id).sort()).toEqual([...ids].sort());
    expect(layout!.edges).toHaveLength(3);
  });

  it('truncates long labels but keeps the full name as title', () => {
    const id = 'x'.repeat(80);
    const node = layoutTopology({ nodes: [{ id }], edges: [] })!.nodes[0];
    expect(node.label.length).toBeLessThanOrEqual(40);
    expect(node.label.endsWith('…')).toBe(true);
    expect(node.title).toBe(id);
  });
});

describe('smoothPath / truncateLabel', () => {
  it('draws a straight segment for two points and curves through the middle ones', () => {
    expect(smoothPath([{ x: 0, y: 0 }, { x: 0, y: 10 }])).toBe('M0,0 L0,10');
    expect(smoothPath([{ x: 0, y: 0 }, { x: 10, y: 10 }, { x: 20, y: 0 }])).toBe('M0,0 Q10,10 15,5 L20,0');
  });
  it('leaves short labels alone', () => {
    expect(truncateLabel('plan')).toBe('plan');
  });
});
