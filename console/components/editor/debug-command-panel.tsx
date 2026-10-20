'use client';

import { useState } from 'react';
import type { AgentInfo } from '@/types/homer';
import { Button } from '@/components/ui/button';
import { Play, RotateCcw } from 'lucide-react';
import { useDebugCommand } from '@/lib/api/queries';
import { kvListToRecord, type KV } from '@/lib/utils/kv';
import { DebugRunDialog } from './debug-run-dialog';
import { DebugLogPanel } from './debug-log-panel';

interface DebugCommandPanelProps {
  agentName: string;
  agent: AgentInfo;
}

interface ActiveRun {
  processId: number;
  commandIndex: number;
}

interface LastParams {
  env: KV[];
  params: KV[];
}

// Re-runs a command with the env and params last submitted for it.
function useRecall(
  agentName: string,
  lastParamsByIndex: Record<number, LastParams>,
  onStarted: (run: ActiveRun) => void,
) {
  const [recallPending, setRecallPending] = useState<number | null>(null);
  const [recallError, setRecallError] = useState<string | null>(null);

  const recallMutation = useDebugCommand(agentName);

  const handleRecall = async (commandIndex: number) => {
    const last = lastParamsByIndex[commandIndex];
    if (!last) return;
    setRecallError(null);
    setRecallPending(commandIndex);
    try {
      const result = await recallMutation.mutateAsync({
        commandIndex,
        env: kvListToRecord(last.env),
        queryParams: kvListToRecord(last.params),
      });
      onStarted({ processId: result.processId, commandIndex });
    } catch (e) {
      setRecallError(e instanceof Error ? e.message : 'Recall run failed');
    } finally {
      setRecallPending(null);
    }
  };

  return { recallPending, recallError, handleRecall };
}

// One command: its recall-last-params and debug buttons.
function CommandRow({
  index,
  hasLast,
  isRecalling,
  onRecall,
  onDebug,
}: {
  index: number;
  hasLast: boolean;
  isRecalling: boolean;
  onRecall: () => void;
  onDebug: () => void;
}) {
  return (
    <div className="flex items-center justify-between rounded border border-border bg-card px-2 py-1.5">
      <span className="text-sm font-mono">Command #{index + 1}</span>
      <div className="flex items-center gap-1">
        <Button
          size="sm"
          variant="outline"
          className="h-7 px-2"
          onClick={onRecall}
          disabled={!hasLast || isRecalling}
          aria-label={`Recall last params for command ${index + 1}`}
          title={
            hasLast
              ? 'Re-run with last submitted params'
              : 'No previous run to recall'
          }
        >
          <RotateCcw className="h-3.5 w-3.5" />
        </Button>
        <Button
          size="sm"
          variant="outline"
          className="h-7 px-2"
          onClick={onDebug}
          aria-label={`Debug command ${index + 1}`}
        >
          <Play className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}

// A row per command, or a note when the agent has none, plus the last recall failure.
function CommandList({
  commandCount,
  lastParamsByIndex,
  recallPending,
  recallError,
  onRecall,
  onDebug,
}: {
  commandCount: number;
  lastParamsByIndex: Record<number, LastParams>;
  recallPending: number | null;
  recallError: string | null;
  onRecall: (commandIndex: number) => void;
  onDebug: (commandIndex: number) => void;
}) {
  const indices = Array.from({ length: commandCount }, (_, i) => i);
  return (
    <div className="p-3 space-y-2 border-b border-border">
      {commandCount === 0 ? (
        <p className="text-xs text-muted-foreground">
          No commands defined for this agent.
        </p>
      ) : (
        indices.map((i) => (
          <CommandRow
            key={i}
            index={i}
            hasLast={Boolean(lastParamsByIndex[i])}
            isRecalling={recallPending === i}
            onRecall={() => onRecall(i)}
            onDebug={() => onDebug(i)}
          />
        ))
      )}
      {recallError && (
        <p className="text-xs text-destructive">{recallError}</p>
      )}
    </div>
  );
}

export function DebugCommandPanel({
  agentName,
  agent,
}: DebugCommandPanelProps) {
  const [dialogFor, setDialogFor] = useState<number | null>(null);
  const [activeRun, setActiveRun] = useState<ActiveRun | null>(null);
  const [lastParamsByIndex, setLastParamsByIndex] = useState<
    Record<number, LastParams>
  >({});
  const { recallPending, recallError, handleRecall } = useRecall(agentName, lastParamsByIndex, setActiveRun);

  return (
    <div className="flex flex-col h-full">
      <div className="p-2 border-b border-border">
        <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
          Debug
        </span>
      </div>

      <CommandList
        commandCount={agent.scriptCount}
        lastParamsByIndex={lastParamsByIndex}
        recallPending={recallPending}
        recallError={recallError}
        onRecall={handleRecall}
        onDebug={setDialogFor}
      />

      {activeRun && (
        <div className="flex-1 min-h-0 flex flex-col">
          <DebugLogPanel
            processId={activeRun.processId}
            commandIndex={activeRun.commandIndex}
            onClose={() => setActiveRun(null)}
          />
        </div>
      )}

      {dialogFor !== null && (
        <DebugRunDialog
          open={dialogFor !== null}
          onOpenChange={(o) => !o && setDialogFor(null)}
          agentName={agentName}
          agent={agent}
          commandIndex={dialogFor}
          initialEnv={lastParamsByIndex[dialogFor]?.env}
          initialParams={lastParamsByIndex[dialogFor]?.params}
          onStarted={(processId, env, params) => {
            setLastParamsByIndex((prev) => ({
              ...prev,
              [dialogFor]: { env, params },
            }));
            setActiveRun({ processId, commandIndex: dialogFor });
            setDialogFor(null);
          }}
        />
      )}
    </div>
  );
}
