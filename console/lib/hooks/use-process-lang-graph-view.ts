'use client';

import { useProcessLangGraph } from '@/lib/api/queries';
import type { ProcessInfo } from '@/types/homer';

/** A failed, killed or lost run is the one Resume is offered for. */
function isResumeEligible(process: ProcessInfo | undefined): boolean {
  return process?.status === 'FAILED' || process?.status === 'KILLED' || process?.status === 'UNKNOWN';
}

function probeEnabled(
  process: ProcessInfo | undefined,
  resumeEligible: boolean,
  anyExpanded: boolean,
): boolean {
  return !!process && (resumeEligible || anyExpanded || !!process.currentCommand);
}

/**
 * The process detail page's two LangGraph queries — the cheap probe and the
 * full status — and what the page derives from them: Resume eligibility,
 * whether the live command is the LangGraph one, and the LangGraph label.
 */
export function useProcessLangGraphView(
  processId: number,
  process: ProcessInfo | undefined,
  anyExpanded: boolean,
  liveWorkflowExpanded: boolean,
) {
  const resumeEligible = isResumeEligible(process);
  // Cheap probe (artifact read only, no python on the backend): drives Resume
  // button visibility, and identifies the LangGraph command's label — for the
  // loading state on expanded execution items and for the live-command
  // workflow toggle. Enabled while a command runs because the artifact is
  // written before the graph starts, so the live view works mid-run.
  const lgProbe = useProcessLangGraph(processId, {
    graph: false,
    enabled: probeEnabled(process, resumeEligible, anyExpanded),
  });
  const liveIsLangGraph =
    !!process?.currentCommand && process.currentCommand === lgProbe.data?.label;
  const liveWorkflowOpen = liveWorkflowExpanded && liveIsLangGraph;
  // Full status (topology + nodes): fetched and polled only while an execution
  // item or the live-command workflow is open. Polling is NOT gated on this
  // process being active: a parked segment is FINISHED while its thread keeps
  // advancing in later segments, so the thread-scoped statuses still change.
  const lgStatusOpen = anyExpanded || liveWorkflowOpen;
  const lgStatus = useProcessLangGraph(processId, {
    graph: true,
    enabled: !!process && lgStatusOpen,
    refetchInterval: lgStatusOpen ? 5000 : false,
  });
  const lgLabel = lgStatus.data?.label ?? lgProbe.data?.label;

  return { resumeEligible, lgProbe, lgStatus, liveIsLangGraph, liveWorkflowOpen, lgLabel };
}
