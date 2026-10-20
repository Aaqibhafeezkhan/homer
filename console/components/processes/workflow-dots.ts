/**
 * Shared dot-row styling for LangGraph sub-command progress: the fill color
 * per command state (commandDotColor) and the x-positions for a row of dots
 * inside a node box of a given width (dotRow). Consumed by the in-graph dots
 * in components/agents/workflow-graph.tsx and the sub-progress row in
 * components/processes/workflow-status.tsx, so both stay visually identical.
 */

const DOT_FILL: Record<string, string> = {
  ok: '#16a34a',
  failed: '#dc2626',
  skipped: '#9ca3af',
  running: '#2563eb',
};

/** Fill color for one sub-run command state (unknown states render muted). */
export function commandDotColor(state: string): string {
  return DOT_FILL[state] ?? '#9ca3af';
}

export const DOT_RADIUS = 2.5;
const DOT_SPACING = 7;
export const BOTTOM_INSET = 5;
// Overflow clamp: keep the row inside the rect (EDGE_INSET margin each side);
// compress spacing down to MIN_SPACING (dots just apart), then drop the
// oldest commands -- the tail (latest + running) is the informative part.
const EDGE_INSET = 4;
const MIN_SPACING = 5.5;

/** Dot x-positions (relative to the box's left edge) for one node's command states. */
export function dotRow(states: string[], width: number): Array<{ state: string; cx: number }> {
  if (states.length === 0) return [];
  const usable = Math.max(0, width - 2 * EDGE_INSET);
  let row = states;
  let spacing = row.length > 1 ? Math.min(DOT_SPACING, usable / (row.length - 1)) : 0;
  if (row.length > 1 && spacing < MIN_SPACING) {
    const maxDots = Math.max(1, Math.floor(usable / MIN_SPACING) + 1);
    row = row.slice(row.length - maxDots);
    spacing = row.length > 1 ? Math.min(DOT_SPACING, usable / (row.length - 1)) : 0;
  }
  const startX = width / 2 - ((row.length - 1) * spacing) / 2;
  return row.map((state, i) => ({ state, cx: startX + i * spacing }));
}
