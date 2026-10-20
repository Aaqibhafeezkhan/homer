'use client';

import { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Plus, Trash2, Play, HelpCircle } from 'lucide-react';
import { useDebugCommand } from '@/lib/api/queries';
import { getAgentInputs, getTransformerDescription } from '@/lib/utils/agent';
import { kvListToRecord, type KV } from '@/lib/utils/kv';
import type { AgentInfo, AgentInput } from '@/types/homer';

interface DebugRunDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  agentName: string;
  agent: AgentInfo;
  commandIndex: number;
  initialEnv?: KV[];
  initialParams?: KV[];
  onStarted: (processId: number, env: KV[], params: KV[]) => void;
}

type KVSetter = React.Dispatch<React.SetStateAction<KV[]>>;

const addKV = (setter: KVSetter) =>
  setter((prev) => [...prev, { key: '', value: '' }]);
const removeKV = (setter: KVSetter, index: number) =>
  setter((prev) => prev.filter((_, i) => i !== index));
const updateKV = (
  setter: KVSetter,
  index: number,
  field: 'key' | 'value',
  value: string,
) =>
  setter((prev) =>
    prev.map((kv, i) => (i === index ? { ...kv, [field]: value } : kv)),
  );

// The env and param rows, reset whenever the dialog opens, and the run action.
function useDebugRunForm({
  open,
  agentName,
  agent,
  commandIndex,
  initialEnv,
  initialParams,
  onStarted,
}: Omit<DebugRunDialogProps, 'onOpenChange'>) {
  const [env, setEnv] = useState<KV[]>([]);
  const [params, setParams] = useState<KV[]>([]);
  const debugMutation = useDebugCommand(agentName);
  const { reset: resetDebugMutation } = debugMutation;

  useEffect(() => {
    if (open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional form reset when dialog opens
      setEnv(initialEnv ?? []);
      if (initialParams) {
        setParams(initialParams);
      } else {
        const declared = getAgentInputs(agent);
        setParams(declared.map((p) => ({ key: p.paramName, value: '' })));
      }
      resetDebugMutation();
    }
  }, [open, agent, initialEnv, initialParams, resetDebugMutation]);

  const handleRun = async () => {
    try {
      const result = await debugMutation.mutateAsync({
        commandIndex,
        env: kvListToRecord(env),
        queryParams: kvListToRecord(params),
      });
      onStarted(result.processId, env, params);
    } catch {
      // error surfaced via debugMutation.error
    }
  };

  return { env, setEnv, params, setParams, debugMutation, handleRun };
}

interface KVListProps {
  list: KV[];
  setter: KVSetter;
  addLabel: string;
  keyPlaceholder: string;
  renderHint?: (kv: KV) => React.ReactNode;
}

function KVRow({
  kv,
  index,
  setter,
  keyPlaceholder,
}: {
  kv: KV;
  index: number;
  setter: KVSetter;
  keyPlaceholder: string;
}) {
  return (
    <div className="flex gap-2">
      <Input
        className="flex-1 font-mono text-xs"
        placeholder={keyPlaceholder}
        value={kv.key}
        onChange={(e) => updateKV(setter, index, 'key', e.target.value)}
      />
      <Input
        className="flex-1 font-mono text-xs"
        placeholder="value"
        value={kv.value}
        onChange={(e) => updateKV(setter, index, 'value', e.target.value)}
      />
      <Button
        size="sm"
        variant="ghost"
        className="h-10 w-10 p-0 shrink-0"
        onClick={() => removeKV(setter, index)}
        aria-label="Remove row"
      >
        <Trash2 className="h-4 w-4" />
      </Button>
    </div>
  );
}

// Editable key/value rows, each removable, plus an add-row button.
function KVList({ list, setter, addLabel, keyPlaceholder, renderHint }: KVListProps) {
  return (
    <div className="space-y-2">
      {list.map((kv, i) => (
        <div key={i} className="space-y-1">
          <KVRow kv={kv} index={i} setter={setter} keyPlaceholder={keyPlaceholder} />
          {renderHint?.(kv)}
        </div>
      ))}
      <Button
        size="sm"
        variant="outline"
        onClick={() => addKV(setter)}
        className="w-full"
      >
        <Plus className="mr-1 h-3.5 w-3.5" /> {addLabel}
      </Button>
    </div>
  );
}

// A labelled key/value list with its explanatory tooltip.
function KVSection({ text, tooltip, ...list }: KVListProps & { text: string; tooltip: string }) {
  return (
    <div className="space-y-1.5">
      <SectionLabel text={text} tooltip={tooltip} />
      <KVList {...list} />
    </div>
  );
}

// Where a query param row lands: the declared input's env name and transformer.
function ParamHint({ declared }: { declared: AgentInput | undefined }) {
  if (!declared) return null;
  return (
    <p className="pl-1 font-mono text-xs text-muted-foreground">
      → {declared.envName}{' '}
      <span className="italic">
        ({getTransformerDescription(declared.transformer)})
      </span>
    </p>
  );
}

function HintIcon({ tooltip, ariaLabel }: { tooltip: string; ariaLabel: string }) {
  return (
    <span
      className="relative inline-flex cursor-help text-muted-foreground group"
      role="button"
      tabIndex={0}
      aria-label={ariaLabel}
    >
      <HelpCircle className="h-3.5 w-3.5" aria-hidden="true" />
      <span
        role="tooltip"
        className="pointer-events-none absolute left-1/2 top-full z-50 mt-1 w-64 -translate-x-1/2 rounded-md border border-border bg-popover px-2 py-1 text-xs font-normal normal-case tracking-normal text-popover-foreground shadow-md opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
      >
        {tooltip}
      </span>
    </span>
  );
}

function SectionLabel({ text, tooltip }: { text: string; tooltip: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <Label className="text-xs uppercase tracking-wider text-muted-foreground">
        {text}
      </Label>
      <HintIcon tooltip={tooltip} ariaLabel={`About ${text.toLowerCase()}`} />
    </div>
  );
}

// The dialog body: the query param and env lists, and the run failure.
function DebugRunFields({ agent, form }: { agent: AgentInfo; form: ReturnType<typeof useDebugRunForm> }) {
  const { env, setEnv, params, setParams, debugMutation } = form;
  const declaredByParamName = new Map<string, AgentInput>(
    getAgentInputs(agent).map((p) => [p.paramName, p]),
  );
  return (
    <div className="space-y-4 py-2 px-2 max-h-[60vh] overflow-y-auto">
      <KVSection
        text="Query params"
        tooltip="Values run through the agent's declared transformer and land in the command env under the matching envName."
        list={params}
        setter={setParams}
        addLabel="Add query param"
        keyPlaceholder="param"
        renderHint={(kv) => <ParamHint declared={declaredByParamName.get(kv.key.trim())} />}
      />
      <KVSection
        text="Environment"
        tooltip="Key/value pairs merged into the command environment. No transformation — the key is the env var name verbatim."
        list={env}
        setter={setEnv}
        addLabel="Add env var"
        keyPlaceholder="NAME"
      />

      {debugMutation.error && (
        <div className="rounded-md bg-destructive/10 p-2 text-sm text-destructive">
          {debugMutation.error.message}
        </div>
      )}
    </div>
  );
}

export function DebugRunDialog(props: DebugRunDialogProps) {
  const { open, onOpenChange, agent, commandIndex } = props;
  const form = useDebugRunForm(props);
  const { debugMutation, handleRun } = form;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-w-lg"
        aria-describedby="debug-run-desc"
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-1.5">
            Debug Command #{commandIndex + 1}
            <HintIcon
              tooltip="Runs only this command with custom env and query params. A real process is created — same locks and parallel-run slots as a normal run."
              ariaLabel="About debug runs"
            />
          </DialogTitle>
          <DialogDescription id="debug-run-desc" className="sr-only">
            Run only this command with custom env and query params.
          </DialogDescription>
        </DialogHeader>

        <DebugRunFields agent={agent} form={form} />

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleRun} disabled={debugMutation.isPending}>
            <Play className="mr-2 h-4 w-4" />
            {debugMutation.isPending ? 'Starting...' : 'Run'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
