'use client';

import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import type { LangGraphEdgeRef, LangGraphTopology } from '@/types/homer';
import { layoutTopology, type LaidOutEdge, type LaidOutNode } from '@/lib/langgraph/layout';
import { BOTTOM_INSET, DOT_RADIUS, commandDotColor, dotRow } from '@/components/processes/workflow-dots';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils/cn';

interface WorkflowGraphProps {
  topology: LangGraphTopology;
  /** Extra classes for a node's box (e.g. its run status); start/end are never styled. */
  nodeClassName?: (id: string) => string | undefined;
  /** Sub-command states per node, drawn as dots along the box's bottom edge. */
  dotsByNode?: Record<string, string[]>;
  /** Shown instead of the graph when it cannot be drawn (above the render cap, or layout failed). */
  fallback?: ReactNode;
  /** Popover body for a node's dispatched sub-agent runs; null/undefined means no trigger. Start/end are never clickable. */
  nodeDetails?: (id: string) => ReactNode | null;
  /** Edges this run has taken, drawn green; a non-null array also shows the legend entry (ADR-0075). */
  traversed?: LangGraphEdgeRef[] | null;
}

// Solid = add_edge (always taken); dashed = a router's conditional edge.
const EDGE_LEGEND: Array<[string, boolean]> = [
  ['fixed edge', false],
  ['conditional edge', true],
];

/** Collision-free key for a (source, target) pair — never a plain-object lookup (§12 rule 2). */
function edgeKey(source: string, target: string): string {
  return JSON.stringify([source, target]);
}

/** The keys of the traversed (source, target) pairs; empty when there is no traversal. */
function useTraversedKeys(traversed: LangGraphEdgeRef[] | null | undefined): Set<string> {
  return useMemo(() => {
    const keys = new Set<string>();
    if (Array.isArray(traversed)) {
      for (const { source, target } of traversed) keys.add(edgeKey(source, target));
    }
    return keys;
  }, [traversed]);
}

/** The node whose runs popover is open, plus a ref mirroring the committed value. */
function useOpenNode() {
  const [openNode, setOpenNode] = useState<string | null>(null);
  // Mirrors the committed openNode: Radix calls onCloseAutoFocus after the close commits, so render state is stale there.
  const openNodeRef = useRef<string | null>(null);
  useEffect(() => {
    openNodeRef.current = openNode;
  }, [openNode]);
  return { openNode, setOpenNode, openNodeRef };
}

function ArrowMarkers({ markerId, takenMarkerId }: { markerId: string; takenMarkerId: string }) {
  return (
    <defs>
      <marker id={markerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
        <path d="M0,0 L10,5 L0,10 z" className="fill-muted-foreground" />
      </marker>
      <marker id={takenMarkerId} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
        <path d="M0,0 L10,5 L0,10 z" className="fill-green-600" />
      </marker>
    </defs>
  );
}

function GraphEdge({ edge, taken, markerId, takenMarkerId }: { edge: LaidOutEdge; taken: boolean; markerId: string; takenMarkerId: string }) {
  return (
    <g>
      <path
        d={edge.path}
        fill="none"
        strokeWidth={taken ? 2 : 1.25}
        data-conditional={edge.conditional ? 'true' : undefined}
        data-traversed={taken ? 'true' : undefined}
        className={cn(
          taken ? 'stroke-green-600' : 'stroke-muted-foreground',
          edge.conditional && '[stroke-dasharray:4_3]',
        )}
        markerEnd={`url(#${taken ? takenMarkerId : markerId})`}
      />
      <EdgeLabel edge={edge} />
    </g>
  );
}

// An edge's label on a card-colored backing, centred where layout placed it; nothing for an unlabelled edge.
function EdgeLabel({ edge }: { edge: LaidOutEdge }) {
  if (!edge.label) return null;
  return (
    <g transform={`translate(${edge.labelX},${edge.labelY})`}>
      <rect x={-edge.labelWidth / 2} y={-8} width={edge.labelWidth} height={16} rx={4} className="fill-card" />
      <text textAnchor="middle" dominantBaseline="central" className="fill-muted-foreground">
        {edge.label}
      </text>
    </g>
  );
}

// Sub-command dots along the node box's bottom edge; a running one pulses.
function NodeDots({ states, node }: { states: string[]; node: LaidOutNode }) {
  return dotRow(states, node.width).map((dot, i) => (
    <circle
      key={i}
      data-lg-dot=""
      cx={dot.cx}
      cy={node.height - BOTTOM_INSET}
      r={DOT_RADIUS}
      style={{ fill: commandDotColor(dot.state) }}
    >
      {dot.state === 'running' && (
        <animate attributeName="opacity" values="1;0.3;1" dur="1.5s" repeatCount="indefinite" />
      )}
    </circle>
  ));
}

function GraphNode({
  node,
  nodeClassName,
  dotsByNode,
}: {
  node: LaidOutNode;
  nodeClassName?: (id: string) => string | undefined;
  dotsByNode?: Record<string, string[]>;
}) {
  return (
    <g data-node-id={node.id} transform={`translate(${node.x},${node.y})`}>
      <title>{node.title}</title>
      <rect
        width={node.width}
        height={node.height}
        rx={node.terminal ? node.height / 2 : 8}
        strokeWidth={1.5}
        className={cn(
          node.terminal
            ? 'fill-transparent stroke-muted-foreground [stroke-dasharray:3_3]'
            : 'fill-card stroke-border',
          !node.terminal && nodeClassName?.(node.id),
        )}
      />
      <text
        x={node.width / 2}
        y={node.height / 2}
        textAnchor="middle"
        dominantBaseline="central"
        className={node.terminal ? 'fill-muted-foreground' : 'fill-foreground'}
      >
        {node.label}
      </text>
      <NodeDots
        // Own-property check: node.id is agent-authored and a name like
        // "constructor" must not resolve to an inherited Object.prototype
        // member instead of "no dots for this node" (§12 rule 2).
        states={dotsByNode && Object.hasOwn(dotsByNode, node.id) ? dotsByNode[node.id] : []}
        node={node}
      />
    </g>
  );
}

// A transparent trigger over a node's box that opens the popover of its dispatched runs.
function NodeRunsPopover({
  node,
  details,
  openNode,
}: {
  node: LaidOutNode;
  details: ReactNode;
  openNode: ReturnType<typeof useOpenNode>;
}) {
  return (
    <Popover
      open={openNode.openNode === node.id}
      onOpenChange={(open) =>
        // A deferred outside-click dismiss of the previously open popover runs
        // after this open — only clear when this node is still the open one.
        openNode.setOpenNode((prev) => (open ? node.id : prev === node.id ? null : prev))
      }
    >
      <PopoverTrigger
        type="button"
        aria-label={`Show runs for ${node.id}`}
        aria-haspopup="dialog"
        className="absolute cursor-pointer rounded-lg bg-transparent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        style={{ left: node.x, top: node.y, width: node.width, height: node.height }}
      />
      <PopoverContent
        aria-label={`Runs for ${node.id}`}
        onCloseAutoFocus={(e) => {
          // Restore focus only on a solo close; another node's open owns focus now.
          const current = openNode.openNodeRef.current;
          if (current !== null && current !== node.id) e.preventDefault();
        }}
      >
        {details}
      </PopoverContent>
    </Popover>
  );
}

// One runs popover per non-terminal node that has details.
function NodeRunsPopovers({
  nodes,
  nodeDetails,
  openNode,
}: {
  nodes: LaidOutNode[];
  nodeDetails: (id: string) => ReactNode | null;
  openNode: ReturnType<typeof useOpenNode>;
}) {
  return nodes
    .filter((node) => !node.terminal)
    .map((node) => {
      const details = nodeDetails(node.id);
      if (details == null) return null;
      return <NodeRunsPopover key={node.id} node={node} details={details} openNode={openNode} />;
    });
}

function LegendLine({ dashed }: { dashed: boolean }) {
  return (
    <svg width="20" height="6" aria-hidden="true">
      <line
        x1="0"
        y1="3"
        x2="20"
        y2="3"
        strokeWidth={1.25}
        className={cn('stroke-muted-foreground', dashed && '[stroke-dasharray:4_3]')}
      />
    </svg>
  );
}

// The edge styles, plus the taken path once a traversal is known.
function EdgeLegend({ showTaken }: { showTaken: boolean }) {
  return (
    <div className="mt-3 flex flex-wrap items-center justify-center gap-4 text-xs text-muted-foreground" aria-label="edge legend">
      {EDGE_LEGEND.map(([label, dashed]) => (
        <span key={label} className="flex items-center gap-1.5">
          <LegendLine dashed={dashed} />
          {label}
        </span>
      ))}
      {showTaken && (
        <span className="flex items-center gap-1.5">
          <svg width="20" height="6" aria-hidden="true">
            <line x1="0" y1="3" x2="20" y2="3" strokeWidth={2} className="stroke-green-600" />
          </svg>
          taken path
        </span>
      )}
    </div>
  );
}

/** A LangGraph workflow drawn from its topology, in the console's theme tokens. See ADR-0073. */
export function WorkflowGraph({
  topology,
  nodeClassName,
  dotsByNode,
  fallback,
  nodeDetails,
  traversed,
}: WorkflowGraphProps) {
  const uid = useId().replace(/[^a-zA-Z0-9]/g, '');
  const markerId = `lg-arrow-${uid}`;
  const takenMarkerId = `lg-arrow-taken-${uid}`;
  const layout = useMemo(() => layoutTopology(topology), [topology]);
  const traversedKeys = useTraversedKeys(traversed);
  const openNode = useOpenNode();
  if (!layout) {
    return (
      fallback ?? (
        <p className="text-sm text-muted-foreground">
          Graph could not be drawn ({topology.nodes.length} nodes, {topology.edges.length} edges).
        </p>
      )
    );
  }
  const isTaken = (edge: LaidOutEdge) => traversedKeys.has(edgeKey(edge.source, edge.target));
  return (
    <div className="overflow-x-auto rounded-md border bg-card p-4">
      <div className="relative mx-auto" style={{ width: layout.width, height: layout.height }}>
        <svg
          role="img"
          aria-label="Workflow graph"
          width={layout.width}
          height={layout.height}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          className="block text-xs"
        >
          <ArrowMarkers markerId={markerId} takenMarkerId={takenMarkerId} />
          {/* Taken edges render last (on top of the rest), per edge z-order. */}
          {[...layout.edges]
            .sort((a, b) => Number(isTaken(a)) - Number(isTaken(b)))
            .map((edge) => (
              <GraphEdge key={edge.key} edge={edge} taken={isTaken(edge)} markerId={markerId} takenMarkerId={takenMarkerId} />
            ))}
          {layout.nodes.map((node) => (
            <GraphNode key={node.id} node={node} nodeClassName={nodeClassName} dotsByNode={dotsByNode} />
          ))}
        </svg>
        {nodeDetails && <NodeRunsPopovers nodes={layout.nodes} nodeDetails={nodeDetails} openNode={openNode} />}
      </div>
      <EdgeLegend showTaken={Array.isArray(traversed)} />
    </div>
  );
}
