'use client';

import { memo } from 'react';
import { Check, X, Loader2, MinusCircle } from 'lucide-react';
import { formatTimestamp, formatDuration } from '@/lib/utils/format';
import { costReasonText, costSourceLabel, costSourceTitle, stepCostLabel } from '@/lib/utils/cost-source';
import { WorkflowStatus } from '@/components/processes/workflow-status';
import { ArtifactOutputTabs } from '@/components/processes/artifact-output-tabs';
import type { ProcessExecution, LangGraphRunStatus, LlmAccounting } from '@/types/homer';

interface ExecutionItemProps {
  execution: ProcessExecution;
  index: number;
  expanded: boolean;
  onToggle: (index: number) => void;
  processId: number;
  onDownloadArtifact: (artifactPath: string | null) => void;
  downloadPending: boolean;
  workflow?: LangGraphRunStatus | null;
  workflowLoading?: boolean;
}

// The step's resolved LLM cost: amount, source, fallback reason, token usage and session.
function LlmCostLine({ accounting }: { accounting: LlmAccounting }) {
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
      <span className="font-medium">LLM cost:</span>
      <span>
        {stepCostLabel(accounting)}
      </span>
      <span className="rounded border px-1 text-xs" title={costSourceTitle(accounting.source)}>
        {costSourceLabel(accounting.source)}
      </span>
      {accounting.fallbackReason && (
        <span className="text-muted-foreground text-xs">
          ({costReasonText(accounting.source, accounting.fallbackReason)})
        </span>
      )}
      {accounting.usage && (
        <span className="text-muted-foreground text-xs">
          · {accounting.usage.input.toLocaleString()} in
          {' / '}
          {accounting.usage.output.toLocaleString()} out
          {accounting.usage.reasoning > 0 &&
            ` / ${accounting.usage.reasoning.toLocaleString()} reasoning`}
        </span>
      )}
      {accounting.sessionId && (
        <span className="text-muted-foreground">
          session <span className="font-mono">{accounting.sessionId}</span>
        </span>
      )}
    </div>
  );
}

function WorkflowSection({
  workflow,
  loading,
}: {
  workflow?: LangGraphRunStatus | null;
  loading?: boolean;
}) {
  return workflow ? (
    <div className="pt-2">
      <span className="font-medium">Workflow:</span>
      <div className="mt-2">
        <WorkflowStatus status={workflow} />
      </div>
    </div>
  ) : loading ? (
    <div className="flex items-center gap-2 pt-2 text-muted-foreground">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
      Loading workflow status…
    </div>
  ) : null;
}

// The always-visible header row: result icon, label, exit code and timing.
function ExecutionSummary({ execution }: { execution: ProcessExecution }) {
  return (
    <>
      <div className="flex items-center gap-2">
        {execution.skipped ? (
          <MinusCircle
            className="h-4 w-4 text-muted-foreground"
            aria-label="Skipped"
          />
        ) : execution.resultCode === 0 ? (
          <Check className="h-4 w-4 text-green-500" aria-hidden="true" />
        ) : (
          <X className="h-4 w-4 text-red-500" aria-hidden="true" />
        )}
        <span className="font-medium">{execution.label}</span>
        <span
          className="text-sm text-muted-foreground"
          title={execution.skipped ? execution.errMsg ?? undefined : undefined}
        >
          {execution.skipped ? 'Skipped' : `Exit code: ${execution.resultCode}`}
        </span>
      </div>
      <span className="text-sm text-muted-foreground font-mono whitespace-nowrap">
        {execution.skipped
          ? formatDuration(execution.start, execution.end)
          : `${formatTimestamp(execution.start)} → ${formatTimestamp(execution.end)} · ${formatDuration(execution.start, execution.end)}`}
      </span>
    </>
  );
}

// The expanded body: error, LLM cost, output artifacts and workflow status.
function ExecutionDetails({
  execution,
  index,
  processId,
  onDownloadArtifact,
  downloadPending,
  workflow,
  workflowLoading,
}: Omit<ExecutionItemProps, 'expanded' | 'onToggle'>) {
  return (
    <div className="space-y-2 pt-2 border-t text-sm">
      {execution.errMsg && (
        <div>
          <span className="font-medium">Error:</span>{' '}
          <span className="text-destructive">{execution.errMsg}</span>
        </div>
      )}
      {execution.llmAccounting && (
        <LlmCostLine accounting={execution.llmAccounting} />
      )}
      {execution.stdOut || execution.stdErr ? (
        <ArtifactOutputTabs
          processId={processId}
          stdoutPath={execution.stdOut}
          stderrPath={execution.stdErr}
          executionIndex={index}
          defaultTab={execution.stdOut ? 'stdout' : 'stderr'}
          kind={execution.kind}
          accounting={execution.llmAccounting ?? null}
          enabled
          onDownload={onDownloadArtifact}
          downloadPending={downloadPending}
        />
      ) : (
        <p className="text-sm text-muted-foreground">No output artifacts</p>
      )}
      <WorkflowSection workflow={workflow} loading={workflowLoading} />
    </div>
  );
}

export const ExecutionItem = memo(function ExecutionItem({
  execution,
  index,
  expanded,
  onToggle,
  processId,
  onDownloadArtifact,
  downloadPending,
  workflow,
  workflowLoading,
}: ExecutionItemProps) {
  return (
    <div className="border rounded-lg p-4 space-y-2">
      <div
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        className="flex items-center justify-between cursor-pointer -m-4 p-4 rounded-lg hover:bg-muted/50"
        onClick={() => onToggle(index)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onToggle(index);
          }
        }}
      >
        <ExecutionSummary execution={execution} />
      </div>

      {expanded && (
        <ExecutionDetails
          execution={execution}
          index={index}
          processId={processId}
          onDownloadArtifact={onDownloadArtifact}
          downloadPending={downloadPending}
          workflow={workflow}
          workflowLoading={workflowLoading}
        />
      )}
    </div>
  );
});
