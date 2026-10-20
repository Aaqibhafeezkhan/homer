'use client';

import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { WorkflowGraph } from '@/components/agents/workflow-graph';
import { SubRunRow } from '@/components/processes/sub-run-row';
import type { LangGraphRunStatus, LangGraphSubProgress } from '@/types/homer';

type NodeTint = 'done' | 'failed' | 'parked' | 'pending' | 'subDone';

// Translucent tints over the theme's card color, so status reads on every
// theme (same theme-independent semantics as StatusBadge).
const BOX_CLASS: Record<NodeTint, string> = {
  done: 'fill-green-500/15 stroke-green-600',
  failed: 'fill-red-500/15 stroke-red-600',
  parked: 'fill-amber-500/15 stroke-amber-600',
  pending: 'fill-blue-500/15 stroke-blue-600',
  // Parked node whose sub-agent run(s) already finished: green body (the
  // agent's work is done) with the parked amber dashed ring (the join hasn't
  // resolved, so the node itself has not completed yet).
  subDone: 'fill-green-500/15 stroke-amber-600 [stroke-dasharray:4_3]',
};

const DOT_CLASS: Record<NodeTint, string> = {
  done: 'bg-green-500',
  failed: 'bg-red-500',
  parked: 'bg-amber-500',
  pending: 'bg-blue-500',
  subDone: 'bg-green-500 ring-1 ring-amber-500',
};

const LEGEND: Array<[string, string]> = [
  ['done', 'bg-green-500'],
  ['failed', 'bg-red-500'],
  ['parked', 'bg-amber-500'],
  ['sub finished', 'bg-green-500 ring-1 ring-amber-500'],
  ['pending', 'bg-blue-500'],
];

/**
 * Effective tint for one node: a parked node whose sub runs have all
 * FINISHED renders as subDone so parallel fan-out members go green
 * individually instead of only when the whole join resolves.
 */
function nodeTint(
  nodeStatus: string,
  nodeSubs: LangGraphSubProgress[] | undefined
): NodeTint | undefined {
  if (
    nodeStatus === 'parked' &&
    nodeSubs &&
    nodeSubs.length > 0 &&
    nodeSubs.every((sub) => sub.status === 'FINISHED')
  ) {
    return 'subDone';
  }
  return Object.hasOwn(BOX_CLASS, nodeStatus) ? (nodeStatus as NodeTint) : undefined;
}

// Node ids and sub-map keys are agent-authored: a name like "constructor" or
// "toString" must not resolve to an inherited Object.prototype member instead
// of "nothing here" (§12 rule 2). Own-property lookups, not `?.[id]`.
function ownNodeStatus(
  nodes: Record<string, string> | null | undefined,
  id: string
): string | undefined {
  return nodes && Object.hasOwn(nodes, id) ? nodes[id] : undefined;
}

function ownSubs(
  subs: Record<string, LangGraphSubProgress[]> | null | undefined,
  id: string
): LangGraphSubProgress[] | undefined {
  return subs && Object.hasOwn(subs, id) ? subs[id] : undefined;
}

// The collapsible list of every sub the graph dispatched, headed by how many finished.
function SubsSection({
  subs,
  finished,
  open,
  onToggle,
}: {
  subs: Array<LangGraphSubProgress & { node: string }>;
  finished: number;
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="space-y-1 text-xs text-muted-foreground">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex items-center gap-1 rounded hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        {open ? (
          <ChevronDown className="h-3 w-3 shrink-0" aria-hidden="true" />
        ) : (
          <ChevronRight className="h-3 w-3 shrink-0" aria-hidden="true" />
        )}
        {finished}/{subs.length} subs finished
      </button>
      {open &&
        subs.map((sub) => <SubRunRow key={sub.interruptId} sub={sub} node={sub.node} />)}
    </div>
  );
}

// Names the nodes whose oldest sub history the runtime dropped; nothing when none.
function TruncatedSubsNote({ nodes }: { nodes: LangGraphRunStatus['subsTruncated'] }) {
  return (nodes?.length ?? 0) > 0 ? (
    <p className="text-xs text-muted-foreground">
      Older sub history not shown for: {nodes!.join(', ')}
    </p>
  ) : null;
}

// The per-sub command states of each node, for the dots pinned inside its graph box.
function dotsFromSubs(subs: LangGraphRunStatus['subs']): Record<string, string[]> {
  // Null prototype: assigning a "__proto__" node on a plain {} would set the
  // prototype instead of a key, and that node would lose its dots.
  const dots: Record<string, string[]> = Object.create(null);
  for (const [node, nodeSubs] of Object.entries(subs ?? {})) {
    const states = nodeSubs.flatMap((sub) => (sub.commands ?? []).map((cmd) => cmd.state));
    if (states.length > 0) dots[node] = states;
  }
  return dots;
}

// Fallback when topology is absent (an older homer_langgraph runtime's
// `state` output predates it) or the graph cannot be drawn.
function NodeList({
  nodes,
  subs,
}: {
  nodes: Record<string, string>;
  subs: LangGraphRunStatus['subs'];
}) {
  return (
    <div className="space-y-1 text-xs text-muted-foreground" aria-label="node statuses">
      {Object.entries(nodes).map(([name, s]) => {
        const tint = nodeTint(s, ownSubs(subs, name));
        return (
          <p key={name} className="flex items-center gap-1.5">
            <span
              className={`h-2 w-2 rounded-full ${tint ? DOT_CLASS[tint] : 'bg-muted-foreground/40'}`}
              aria-hidden="true"
            />
            <span className="font-medium">{name}</span> — {s}
          </p>
        );
      })}
    </div>
  );
}

// The graph box tint of one node; nothing for an unreached or unknown status.
function nodeBoxClass(status: LangGraphRunStatus, id: string): string | undefined {
  const s = ownNodeStatus(status.nodes, id);
  const tint = s ? nodeTint(s, ownSubs(status.subs, id)) : undefined;
  return tint && BOX_CLASS[tint];
}

// The popover body of a node that dispatched subs; null for any other node.
function nodeSubDetails(status: LangGraphRunStatus, id: string): React.ReactNode {
  const nodeSubs = ownSubs(status.subs, id);
  if (!nodeSubs || nodeSubs.length === 0) return null;
  return (
    <div className="space-y-2 text-xs">
      <p className="font-medium text-foreground">
        {id} — {ownNodeStatus(status.nodes, id) ?? 'unreached'}
      </p>
      <div className="space-y-1 text-muted-foreground">
        {nodeSubs.map((sub) => (
          <SubRunRow key={sub.interruptId} sub={sub} />
        ))}
      </div>
    </div>
  );
}

// The tint legend and the run's thread id.
function Legend({ threadId }: { threadId: LangGraphRunStatus['threadId'] }) {
  return (
    <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
      {LEGEND.map(([label, dot]) => (
        <span key={label} className="flex items-center gap-1">
          <span className={`h-2 w-2 rounded-full ${dot}`} aria-hidden="true" />
          {label}
        </span>
      ))}
      <span className="font-mono">thread {threadId}</span>
    </div>
  );
}

export function WorkflowStatus({ status }: { status: LangGraphRunStatus }) {
  // Collapsed by default: a node keeps every sub it ever dispatched, so this list
  // grows for the life of the thread, and the in-graph dots already carry the
  // per-node progress. (Hooks stay above the early returns.)
  const [subsOpen, setSubsOpen] = useState(false);
  // Mirror of the per-sub command dots, pinned inside the graph's node boxes
  // (hooks stay above the early returns per the rules of hooks).
  const dotsByNode = useMemo(() => dotsFromSubs(status.subs), [status.subs]);

  if (status.error) {
    // Every backend error path ships topology=null (state emits it, so a
    // state failure loses it too — see ADR-0044), hence no graph attempt here.
    return (
      <p className="text-sm text-destructive">Workflow status unavailable: {status.error}</p>
    );
  }
  if (!status.nodes) {
    return <p className="text-sm text-muted-foreground">Loading workflow status…</p>;
  }

  const nodeErrors = Object.entries(status.errors ?? {});
  const subs = Object.entries(status.subs ?? {}).flatMap(([node, nodeSubs]) =>
    nodeSubs.map((sub) => ({ node, ...sub }))
  );
  const finishedSubs = subs.filter((sub) => sub.status === 'FINISHED').length;
  const nodeList = <NodeList nodes={status.nodes} subs={status.subs} />;

  return (
    <div className="space-y-2">
      {status.topology ? (
        <WorkflowGraph
          topology={status.topology}
          dotsByNode={dotsByNode}
          traversed={status.traversed}
          nodeClassName={(id) => nodeBoxClass(status, id)}
          nodeDetails={(id) => nodeSubDetails(status, id)}
          fallback={nodeList}
        />
      ) : (
        nodeList
      )}
      <Legend threadId={status.threadId} />
      {subs.length > 0 && (
        <SubsSection
          subs={subs}
          finished={finishedSubs}
          open={subsOpen}
          onToggle={() => setSubsOpen((open) => !open)}
        />
      )}
      {/* Outside the subs block on purpose: the runtime caps a node's interrupt
          history, and dropping the oldest entries has to be said even when
          nothing matched a continuation and no sub line is rendered at all. */}
      <TruncatedSubsNote nodes={status.subsTruncated} />
      {nodeErrors.map(([node, message]) => (
        <p key={node} className="text-sm text-destructive">
          <span className="font-medium">{node}:</span> {message}
        </p>
      ))}
    </div>
  );
}
