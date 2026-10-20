'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Eye, Loader2, type LucideIcon, Workflow } from 'lucide-react';
import { formatTimestamp, formatDuration } from '@/lib/utils/format';
import { WorkflowStatus } from '@/components/processes/workflow-status';
import { ArtifactOutputTabs } from '@/components/processes/artifact-output-tabs';
import type { LangGraphRunStatus } from '@/types/homer';

interface CurrentCommandBoxProps {
  command: string;
  commandStart: number | null;
  executionsLength: number;
  // Index of the command currently running, from the status API's in-memory
  // tracking; falls back to executionsLength when null (older servers / a
  // restart). Used for both the artifact paths and the /llm-events `cmd`.
  currentCommandIndex?: number | null;
  // Command tag of the running command; passed through to the output tabs
  // as `kind` to gate the Conversation view.
  commandKind?: string | null;
  now: number;
  processId: number;
  onDownloadArtifact: (artifactPath: string | null) => void;
  downloadPending: boolean;
  showWorkflowToggle?: boolean;
  workflowExpanded?: boolean;
  onToggleWorkflow?: () => void;
  workflow?: LangGraphRunStatus | null;
  workflowLoading?: boolean;
}

function CommandTiming({ commandStart, now }: { commandStart: number | null; now: number }) {
  return (
    <>
      {commandStart && (
        <div>{formatTimestamp(commandStart)}</div>
      )}
      {commandStart && (
        <div>{formatDuration(commandStart, now)}</div>
      )}
    </>
  );
}

// A "Show …" / "Hide …" link button for one of the box's expandable panels.
function PanelToggle({
  noun,
  icon: Icon,
  expanded,
  onToggle,
}: {
  noun: 'output' | 'workflow';
  icon: LucideIcon;
  expanded?: boolean;
  onToggle?: () => void;
}) {
  const label = `${expanded ? 'Hide' : 'Show'} ${noun}`;
  return (
    <Button
      variant="link"
      size="sm"
      className="h-auto p-0 font-mono text-muted-foreground hover:text-foreground"
      aria-label={label}
      aria-expanded={expanded}
      onClick={onToggle}
    >
      <Icon className="mr-1 h-3 w-3" aria-hidden="true" />
      {label}
    </Button>
  );
}

// The open workflow panel: the status once fetched, a loading line while the first fetch runs.
function LiveWorkflow({
  workflow,
  loading,
}: {
  workflow?: LangGraphRunStatus | null;
  loading?: boolean;
}) {
  return workflow ? (
    <div className="mt-3 border-t pt-3">
      <WorkflowStatus status={workflow} />
    </div>
  ) : loading ? (
    <div className="mt-3 flex items-center gap-2 border-t pt-3 text-muted-foreground">
      <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
      Loading workflow status…
    </div>
  ) : null;
}

// The open output panel: the running command's artifact tabs.
function LiveOutput({
  processId,
  cmdIndex,
  kind,
  commandStart,
  onDownload,
  downloadPending,
}: {
  processId: number;
  cmdIndex: number;
  kind: string | null;
  commandStart: number | null;
  onDownload: (artifactPath: string | null) => void;
  downloadPending: boolean;
}) {
  return (
    <div className="mt-3 border-t pt-3">
      <ArtifactOutputTabs
        processId={processId}
        stdoutPath={`cmd_${cmdIndex}.out`}
        stderrPath={`cmd_${cmdIndex}.err`}
        executionIndex={cmdIndex}
        kind={kind}
        enabled
        commandStart={commandStart}
        onDownload={onDownload}
        downloadPending={downloadPending}
      />
    </div>
  );
}

export function CurrentCommandBox({
  command,
  commandStart,
  executionsLength,
  currentCommandIndex = null,
  commandKind = null,
  now,
  processId,
  onDownloadArtifact,
  downloadPending,
  showWorkflowToggle,
  workflowExpanded,
  onToggleWorkflow,
  workflow,
  workflowLoading,
}: CurrentCommandBoxProps) {
  const [outputExpanded, setOutputExpanded] = useState(false);

  return (
    <div className="border rounded-lg p-4 bg-muted/30">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-hidden="true" />
          <span className="font-medium">{command}</span>
          <span className="text-sm text-muted-foreground">Running...</span>
          <PanelToggle
            noun="output"
            icon={Eye}
            expanded={outputExpanded}
            onToggle={() => setOutputExpanded((v) => !v)}
          />
          {showWorkflowToggle && (
            <PanelToggle
              noun="workflow"
              icon={Workflow}
              expanded={workflowExpanded}
              onToggle={onToggleWorkflow}
            />
          )}
        </div>
        <div className="text-sm text-muted-foreground text-right">
          <CommandTiming commandStart={commandStart} now={now} />
        </div>
      </div>
      {outputExpanded && (
        <LiveOutput
          processId={processId}
          cmdIndex={currentCommandIndex ?? executionsLength}
          kind={commandKind}
          commandStart={commandStart}
          onDownload={onDownloadArtifact}
          downloadPending={downloadPending}
        />
      )}
      {workflowExpanded && <LiveWorkflow workflow={workflow} loading={workflowLoading} />}
    </div>
  );
}
