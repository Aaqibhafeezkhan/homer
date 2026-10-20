import * as dagre from '@dagrejs/dagre';
import type { LangGraphTopology } from '@/types/homer';

// Console-side cap: layout cost is the console's own resource, so it does not
// rely on the backend's bound. Edges sit below the backend's 1000 because
// synchronous dagre layout grows steeply with them. See ADR-0073.
export const MAX_RENDER_NODES = 200;
const MAX_RENDER_EDGES = 300;
const MAX_LABEL_CHARS = 40;

const START = '__start__';
const END = '__end__';
// Label sizes are estimated (no DOM measurement) so layout stays a pure function.
const CHAR_WIDTH = 7.5;
const LABEL_CHAR_WIDTH = 6.5;
const NODE_PAD_X = 16;
const NODE_HEIGHT = 36;
const TERMINAL_HEIGHT = 28;
const EDGE_LABEL_HEIGHT = 16;
const NODESEP = 32;
// Self-loops are drawn here, not by dagre (its points land away from the node).
// Each further loop on a node reaches LOOP_STEP px further, so they nest; dagre
// gets a box widened by whatever the loops and labels need beyond NODESEP.
const LOOP_REACH = 18;
const LOOP_STEP = 8;
const LOOP_SPREAD = 7;
const LOOP_LABEL_PITCH = EDGE_LABEL_HEIGHT + 2;
const LOOP_CLEARANCE = 8;

export interface LaidOutNode {
  id: string;
  label: string;
  title: string;
  terminal: boolean;
  /** Top-left corner. */
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface LaidOutEdge {
  key: string;
  path: string;
  conditional: boolean;
  label: string | null;
  /** Real node ids (not dagre internal ids), for matching against `traversed`. */
  source: string;
  target: string;
  labelX: number;
  labelY: number;
  labelWidth: number;
}

export interface GraphLayout {
  width: number;
  height: number;
  nodes: LaidOutNode[];
  edges: LaidOutEdge[];
}

export function truncateLabel(text: string): string {
  return text.length > MAX_LABEL_CHARS ? `${text.slice(0, MAX_LABEL_CHARS - 1)}…` : text;
}

/** Polyline through dagre's points, rounded at each interior bend. */
export function smoothPath(points: Array<{ x: number; y: number }>): string {
  if (points.length === 0) return '';
  let d = `M${points[0].x},${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const p = points[i];
    const next = points[i + 1];
    d += ` Q${p.x},${p.y} ${(p.x + next.x) / 2},${(p.y + next.y) / 2}`;
  }
  if (points.length > 1) {
    const last = points[points.length - 1];
    d += ` L${last.x},${last.y}`;
  }
  return d;
}

function edgeLabelWidth(label: string | null): number {
  return label ? label.length * LABEL_CHAR_WIDTH + 8 : 0;
}

/** Where the segment from `rect`'s center toward `point` leaves the rect (dagre's own intersectRect). */
function clipToRect(
  rect: { x: number; y: number; width: number; height: number },
  point: { x: number; y: number },
): { x: number; y: number } {
  const dx = point.x - rect.x;
  const dy = point.y - rect.y;
  if (dx === 0 && dy === 0) return { x: rect.x, y: rect.y };
  let w = rect.width / 2;
  let h = rect.height / 2;
  if (Math.abs(dy) * w > Math.abs(dx) * h) {
    if (dy < 0) h = -h;
    return { x: rect.x + (h * dx) / dy, y: rect.y + h };
  }
  if (dx < 0) w = -w;
  return { x: rect.x + w, y: rect.y + (w * dy) / dx };
}

function nodeLabel(id: string): { label: string; terminal: boolean } {
  if (id === START) return { label: 'start', terminal: true };
  if (id === END) return { label: 'end', terminal: true };
  return { label: truncateLabel(id), terminal: false };
}

function outerReach(loopCount: number): number {
  return LOOP_REACH + (loopCount - 1) * LOOP_STEP;
}

/**
 * Adds each distinct node to `g` under an index-based internal id, sized for its
 * label, and returns the id maps both ways plus each real id's label.
 */
function addNodes(g: dagre.graphlib.Graph, topologyNodes: LangGraphTopology['nodes']) {
  const internalId = new Map<string, string>(); // real id -> internal id
  const realId = new Map<string, string>(); // internal id -> real id
  const meta = new Map<string, { label: string; terminal: boolean }>(); // keyed by real id
  for (const { id } of topologyNodes) {
    if (meta.has(id)) continue;
    const info = nodeLabel(id);
    meta.set(id, info);
    const iid = `n${internalId.size}`;
    internalId.set(id, iid);
    realId.set(iid, id);
    g.setNode(iid, {
      width: Math.max(info.terminal ? 56 : 72, info.label.length * CHAR_WIDTH + 2 * NODE_PAD_X),
      height: info.terminal ? TERMINAL_HEIGHT : NODE_HEIGHT,
    });
  }
  return { internalId, realId, meta };
}

/**
 * Widens (and heightens) the dagre box of each node with self-loops by what its
 * loops and their labels need beyond NODESEP. Returns the original size of every
 * node it grew.
 */
function reserveLoopSpace(
  g: dagre.graphlib.Graph,
  loopsByNode: Map<string, Array<{ label: string | null }>>,
): Map<string, { width: number; height: number }> {
  const realSize = new Map<string, { width: number; height: number }>();
  for (const [iid, loops] of loopsByNode) {
    const n = g.node(iid);
    const labelWidths = loops.map((l) => edgeLabelWidth(l.label)).filter((w) => w > 0);
    const extent = outerReach(loops.length) + (labelWidths.length ? 2 + Math.max(...labelWidths) : 0);
    const reserve = Math.max(0, extent + LOOP_CLEARANCE - NODESEP);
    const stackHeight = labelWidths.length * LOOP_LABEL_PITCH;
    if (reserve > 0 || stackHeight > n.height) {
      realSize.set(iid, { width: n.width, height: n.height });
      n.width += reserve;
      n.height = Math.max(n.height, stackHeight);
    }
  }
  return realSize;
}

/** The self-loops grouped by the internal id of their node, each group in edge order. */
function groupByNode<T extends { iid: string }>(loops: T[]): Map<string, T[]> {
  const loopsByNode = new Map<string, T[]>();
  for (const loop of loops) {
    const list = loopsByNode.get(loop.iid) ?? [];
    list.push(loop);
    loopsByNode.set(loop.iid, list);
  }
  return loopsByNode;
}

interface EdgeMeta {
  label: string | null;
  conditional: boolean;
  source: string;
  target: string;
}

interface SelfLoop extends EdgeMeta {
  key: string;
  iid: string;
}

/** A node's drawn rect, as a center point plus size. */
type Rect = { x: number; y: number; width: number; height: number };

function newGraph(): dagre.graphlib.Graph {
  const g = new dagre.graphlib.Graph({ multigraph: true });
  g.setGraph({ rankdir: 'TB', nodesep: NODESEP, ranksep: 44, edgesep: 16, marginx: 8, marginy: 8 });
  g.setDefaultEdgeLabel(() => ({}));
  return g;
}

/**
 * Adds every edge between two known nodes to `g`, keyed by its index, and sets
 * the self-loops aside (they are drawn after layout, not by dagre).
 */
function addEdges(
  g: dagre.graphlib.Graph,
  topologyEdges: LangGraphTopology['edges'],
  internalId: Map<string, string>,
) {
  const edgeMeta = new Map<string, EdgeMeta>();
  const selfLoops: SelfLoop[] = [];
  topologyEdges.forEach((edge, i) => {
    const sourceIid = internalId.get(edge.source);
    const targetIid = internalId.get(edge.target);
    if (!sourceIid || !targetIid) return;
    const label = edge.label ? truncateLabel(edge.label) : null;
    const key = String(i);
    const info = { label, conditional: !!edge.conditional, source: edge.source, target: edge.target };
    if (sourceIid === targetIid) {
      selfLoops.push({ key, iid: sourceIid, ...info });
      return;
    }
    edgeMeta.set(key, info);
    g.setEdge(
      sourceIid,
      targetIid,
      label ? { label, width: edgeLabelWidth(label), height: EDGE_LABEL_HEIGHT, labelpos: 'c' } : {},
      key,
    );
  });
  return { edgeMeta, selfLoops };
}

/** The drawn rect of a laid-out node: its real size at the left of its (maybe widened) dagre box. */
function drawnRect(g: dagre.graphlib.Graph, realSize: Map<string, { width: number; height: number }>) {
  return (iid: string): Rect => {
    const n = g.node(iid);
    const size = realSize.get(iid) ?? { width: n.width, height: n.height };
    return { x: n.x - n.width / 2 + size.width / 2, y: n.y, ...size };
  };
}

function laidOutNodes(
  g: dagre.graphlib.Graph,
  rectOf: (iid: string) => Rect,
  realId: Map<string, string>,
  meta: Map<string, { label: string; terminal: boolean }>,
): LaidOutNode[] {
  return g.nodes().map((iid) => {
    const r = rectOf(iid);
    const id = realId.get(iid)!;
    const info = meta.get(id)!;
    return {
      id,
      label: info.label,
      title: id,
      terminal: info.terminal,
      x: r.x - r.width / 2,
      y: r.y - r.height / 2,
      width: r.width,
      height: r.height,
    };
  });
}

function laidOutEdges(
  g: dagre.graphlib.Graph,
  rectOf: (iid: string) => Rect,
  grown: Map<string, unknown>,
  edgeMeta: Map<string, EdgeMeta>,
): LaidOutEdge[] {
  return g.edges().map((e) => {
    const laid = g.edge(e);
    const info = edgeMeta.get(e.name!)!;
    // dagre clipped the endpoints to the widened box; move them onto the drawn rect.
    const points = [...(laid.points ?? [])];
    if (points.length > 1) {
      if (grown.has(e.v)) points[0] = clipToRect(rectOf(e.v), points[1]);
      if (grown.has(e.w)) points[points.length - 1] = clipToRect(rectOf(e.w), points[points.length - 2]);
    }
    return {
      key: e.name!,
      path: smoothPath(points),
      conditional: info.conditional,
      label: info.label,
      source: info.source,
      target: info.target,
      labelX: laid.x ?? 0,
      labelY: laid.y ?? 0,
      labelWidth: edgeLabelWidth(info.label),
    };
  });
}

/**
 * The self-loops of one node, nested on its right side with their labels
 * stacked beyond the outermost loop, plus how far right those labels reach.
 */
function nodeLoopEdges(r: Rect, loops: SelfLoop[]): { edges: LaidOutEdge[]; extent: number } {
  const right = r.x + r.width / 2;
  const labelLeft = right + outerReach(loops.length) + 2;
  const labeledCount = loops.filter((l) => l.label).length;
  let row = 0;
  let extent = 0;
  const edges = loops.map((loop, i): LaidOutEdge => {
    const reach = LOOP_REACH + i * LOOP_STEP;
    const labelWidth = edgeLabelWidth(loop.label);
    const labelY = loop.label ? r.y + (row++ - (labeledCount - 1) / 2) * LOOP_LABEL_PITCH : r.y;
    extent = Math.max(extent, labelLeft + labelWidth + 8);
    return {
      key: loop.key,
      path:
        `M${right},${r.y - LOOP_SPREAD}` +
        ` C${right + reach},${r.y - 2 * LOOP_SPREAD} ${right + reach},${r.y + 2 * LOOP_SPREAD} ${right},${r.y + LOOP_SPREAD}`,
      conditional: loop.conditional,
      label: loop.label,
      source: loop.source,
      target: loop.target,
      labelX: labelLeft + labelWidth / 2,
      labelY,
      labelWidth,
    };
  });
  return { edges, extent };
}

/** Positions for every node and edge, or null when the graph is above the render cap. */
export function layoutTopology(topology: LangGraphTopology): GraphLayout | null {
  if (topology.nodes.length > MAX_RENDER_NODES || topology.edges.length > MAX_RENDER_EDGES) {
    return null;
  }
  const g = newGraph();
  // Node ids are agent-authored and can collide with Object.prototype keys
  // (constructor, toString, __proto__, …), which dagre/graphlib object-keys
  // internally and mishandles or crashes on. Every dagre-facing node id is
  // therefore an index-based internal id, never the real one (§12 rule 2).
  const { internalId, realId, meta } = addNodes(g, topology.nodes);
  const { edgeMeta, selfLoops } = addEdges(g, topology.edges, internalId);
  const loopsByNode = groupByNode(selfLoops);
  // Nodes whose dagre box was grown for their loops: the drawn rect keeps this
  // size at the box's left, vertically centered.
  const realSize = reserveLoopSpace(g, loopsByNode);

  // Belt-and-braces: internal ids already avoid the known prototype-key
  // crashes, but a future dagre version or an id shape we have not seen must
  // not take the whole page down — fail into the render-cap fallback instead.
  try {
    dagre.layout(g);
  } catch {
    return null;
  }

  const rectOf = drawnRect(g, realSize);
  const nodes = laidOutNodes(g, rectOf, realId, meta);
  const edges = laidOutEdges(g, rectOf, realSize, edgeMeta);
  const graph = g.graph();
  let width = graph.width ?? 0;
  for (const [iid, loops] of loopsByNode) {
    const laid = nodeLoopEdges(rectOf(iid), loops);
    edges.push(...laid.edges);
    width = Math.max(width, laid.extent);
  }
  return { width, height: graph.height ?? 0, nodes, edges };
}
