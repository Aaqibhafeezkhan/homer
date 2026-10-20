'use client';

import { useReducer, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Play, Copy } from 'lucide-react';
import type { AgentInfo, AgentInput } from '@/types/homer';
import type { ExecuteAgentOptions } from '@/lib/api/client';
import { useExecuteAgent } from '@/lib/api/queries';
import {
  validateParameterValue,
  validateAllParameters,
  isFormValid,
  type ValidationResult,
} from '@/lib/utils/validation';
import { generateAgentCurlCommand, copyToClipboard } from '@/lib/utils/curl';
import { getAgentInputs, getTransformerDescription } from '@/lib/utils/agent';

interface RunAgentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  agent: AgentInfo;
}

interface GroupedInputs {
  query: AgentInput[];
  body: AgentInput[];
  header: AgentInput[];
}

function buildExecuteOpts(
  grouped: GroupedInputs,
  paramValues: Record<string, string>
): ExecuteAgentOptions {
  // Drop blank body params (like curl.ts drops blank query params) so the
  // exported command never carries an empty-string field the server rejects.
  const bodyEntries = grouped.body
    .filter((p) => (paramValues[p.paramName] ?? '').trim())
    .map((p) => [p.paramName, paramValues[p.paramName]] as const);
  return {
    queryParams: Object.fromEntries(
      grouped.query.map((p) => [p.paramName, paramValues[p.paramName] ?? ''])
    ),
    body: bodyEntries.length > 0 ? Object.fromEntries(bodyEntries) : undefined,
    headers:
      grouped.header.length > 0
        ? Object.fromEntries(
            grouped.header.map((p) => [p.paramName, paramValues[p.paramName] ?? ''])
          )
        : undefined,
  };
}

interface FormState {
  activeTab: 'run' | 'curl';
  paramValues: Record<string, string>;
  validationErrors: Record<string, string>;
  copySuccess: boolean;
}

type FormAction =
  | { type: 'RESET'; paramValues: Record<string, string> }
  | { type: 'SET_TAB'; tab: 'run' | 'curl' }
  | { type: 'CHANGE_PARAM'; paramName: string; value: string; validation: ValidationResult | null }
  | { type: 'SET_ERRORS'; errors: Record<string, string> }
  | { type: 'SET_COPY'; copySuccess: boolean };

const initialFormState: FormState = {
  activeTab: 'run',
  paramValues: {},
  validationErrors: {},
  copySuccess: false,
};

// The errors after one parameter was re-validated: cleared when valid, set when invalid with a message.
function nextValidationErrors(
  errors: Record<string, string>,
  paramName: string,
  validation: ValidationResult,
): Record<string, string> {
  const next = { ...errors };
  if (validation.isValid) {
    delete next[paramName];
  } else if (validation.error) {
    next[paramName] = validation.error;
  }
  return next;
}

function formReducer(state: FormState, action: FormAction): FormState {
  switch (action.type) {
    case 'RESET':
      return {
        activeTab: 'run',
        paramValues: action.paramValues,
        validationErrors: {},
        copySuccess: false,
      };
    case 'SET_TAB':
      return { ...state, activeTab: action.tab };
    case 'CHANGE_PARAM': {
      const paramValues = { ...state.paramValues, [action.paramName]: action.value };
      if (!action.validation) {
        return { ...state, paramValues };
      }
      const validationErrors = nextValidationErrors(
        state.validationErrors,
        action.paramName,
        action.validation,
      );
      return { ...state, paramValues, validationErrors };
    }
    case 'SET_ERRORS':
      return { ...state, validationErrors: action.errors };
    case 'SET_COPY':
      return { ...state, copySuccess: action.copySuccess };
  }
}

function groupInputs(inputs: AgentInput[]): GroupedInputs {
  return {
    query: inputs.filter((i) => i.src === 'query'),
    body: inputs.filter((i) => i.src === 'body'),
    header: inputs.filter((i) => i.src === 'header'),
  };
}

// Starts a run of the agent, then closes the dialog and opens the new process.
function useStartRun(agentName: string, onOpenChange: (open: boolean) => void) {
  const router = useRouter();
  const executeAgent = useExecuteAgent();
  const startRun = async (opts: ExecuteAgentOptions) => {
    try {
      const result = await executeAgent.mutateAsync({ agentName, opts });

      // Close dialog and navigate to process detail
      onOpenChange(false);
      router.push(`/processes/${result.processId}`);
    } catch (error) {
      // Error is handled by the mutation and displayed in the UI
      console.error('Failed to execute agent:', error);
    }
  };
  return { executeAgent, startRun };
}

// The form state, reset whenever the dialog opens, and the run and copy actions.
function useRunAgentForm(open: boolean, agent: AgentInfo, onOpenChange: (open: boolean) => void) {
  const { executeAgent, startRun } = useStartRun(agent.name, onOpenChange);

  const [state, dispatch] = useReducer(formReducer, initialFormState);
  const { paramValues, validationErrors } = state;

  const inputs = getAgentInputs(agent);
  const grouped = groupInputs(inputs);

  // Initialize param values
  useEffect(() => {
    if (open && inputs.length > 0) {
      const initialValues: Record<string, string> = {};
      inputs.forEach((param) => {
        initialValues[param.paramName] = '';
      });
      dispatch({ type: 'RESET', paramValues: initialValues });
    }
  }, [open, agent]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleParamChange = (paramName: string, value: string) => {
    // Validate the changed parameter
    const param = inputs.find((p) => p.paramName === paramName);
    const validation = param ? validateParameterValue(param, value) : null;
    dispatch({ type: 'CHANGE_PARAM', paramName, value, validation });
  };

  const handleRun = async () => {
    // Validate all parameters before submission (if there are any)
    if (inputs.length > 0) {
      const errors = validateAllParameters(inputs, paramValues);
      dispatch({ type: 'SET_ERRORS', errors });

      if (Object.keys(errors).length > 0) {
        return;
      }
    }

    await startRun(buildExecuteOpts(grouped, paramValues));
  };

  const curlCommand = generateAgentCurlCommand(agent.name, buildExecuteOpts(grouped, paramValues));

  const handleCopyCurl = async () => {
    const success = await copyToClipboard(curlCommand);
    if (success) {
      dispatch({ type: 'SET_COPY', copySuccess: true });
      setTimeout(() => dispatch({ type: 'SET_COPY', copySuccess: false }), 2000);
    }
  };

  const canRun = inputs.length === 0 || isFormValid(inputs, paramValues, validationErrors);

  return {
    state,
    setTab: (tab: 'run' | 'curl') => dispatch({ type: 'SET_TAB', tab }),
    hasInputs: inputs.length > 0,
    grouped,
    curlCommand,
    canRun,
    executeAgent,
    handleParamChange,
    handleRun,
    handleCopyCurl,
  };
}

export function RunAgentDialog({
  open,
  onOpenChange,
  agent,
}: RunAgentDialogProps) {
  const form = useRunAgentForm(open, agent, onOpenChange);
  const { activeTab, paramValues, validationErrors, copySuccess } = form.state;
  const { executeAgent } = form;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl" aria-describedby="run-agent-desc">
        <DialogHeader>
          <DialogTitle>Run Agent: {agent.name}</DialogTitle>
          <DialogDescription id="run-agent-desc">
            {agent.description || 'Provide parameters to start a new run.'}
          </DialogDescription>
        </DialogHeader>

        <Tabs value={activeTab} onValueChange={(v) => form.setTab(v as 'run' | 'curl')}>
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="run">Configure & Run</TabsTrigger>
            <TabsTrigger value="curl">Export Curl</TabsTrigger>
          </TabsList>

          <TabsContent value="run" className="space-y-4">
            <RunInputs
              grouped={form.grouped}
              hasInputs={form.hasInputs}
              paramValues={paramValues}
              validationErrors={validationErrors}
              onParamChange={form.handleParamChange}
            />

            {executeAgent.error && (
              <div className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
                Failed to execute agent: {executeAgent.error.message}
              </div>
            )}
          </TabsContent>

          <TabsContent value="curl" className="space-y-4">
            <CurlExport
              curlCommand={form.curlCommand}
              hasBody={form.grouped.body.length > 0}
              copySuccess={copySuccess}
              onCopy={form.handleCopyCurl}
            />
          </TabsContent>
        </Tabs>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          {activeTab === 'run' && (
            <RunButton canRun={form.canRun} isPending={executeAgent.isPending} onClick={form.handleRun} />
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// The Export Curl tab: the command, how to authorize it, and the copy button.
function CurlExport({
  curlCommand,
  hasBody,
  copySuccess,
  onCopy,
}: {
  curlCommand: string;
  hasBody: boolean;
  copySuccess: boolean;
  onCopy: () => void;
}) {
  return (
    <>
      <ScrollArea className="h-[200px] rounded-md border bg-muted p-4">
        <pre className="text-sm font-mono">{curlCommand}</pre>
      </ScrollArea>
      <p className="text-xs text-muted-foreground">
        Set{' '}
        <code className="font-mono">HOMER_AUTH_TOKEN</code> in your shell
        environment before pasting so the{' '}
        <code className="font-mono">Authorization</code> header resolves.
      </p>
      {!hasBody && (
        <p className="text-xs text-muted-foreground">
          This command carries no JSON body. An agent whose body is
          defined by an <code className="font-mono">inputSchema</code>{' '}
          (not surfaced here) must have its body added manually, e.g.{' '}
          <code className="font-mono">
            -H &apos;Content-Type: application/json&apos; -d &apos;{'{…}'}&apos;
          </code>
          .
        </p>
      )}
      <Button
        onClick={onCopy}
        className="w-full"
        variant="outline"
      >
        <Copy className="mr-2 h-4 w-4" />
        {copySuccess ? 'Copied!' : 'Copy to Clipboard'}
      </Button>
    </>
  );
}

// The parameter sections of the Run tab, or a note when the agent takes none.
function RunInputs({
  grouped,
  hasInputs,
  paramValues,
  validationErrors,
  onParamChange,
}: {
  grouped: GroupedInputs;
  hasInputs: boolean;
  paramValues: Record<string, string>;
  validationErrors: Record<string, string>;
  onParamChange: (paramName: string, value: string) => void;
}) {
  return hasInputs ? (
    <ScrollArea className="[&>[data-radix-scroll-area-viewport]]:max-h-[400px] pr-4">
      <div className="space-y-6">
        {grouped.query.length > 0 && (
          <InputSection
            title="Query Parameters"
            params={grouped.query}
            paramValues={paramValues}
            validationErrors={validationErrors}
            onParamChange={onParamChange}
          />
        )}
        {grouped.body.length > 0 && (
          <InputSection
            title="Body (JSON)"
            params={grouped.body}
            paramValues={paramValues}
            validationErrors={validationErrors}
            onParamChange={onParamChange}
          />
        )}
        {grouped.header.length > 0 && (
          <InputSection
            title="Headers"
            params={grouped.header}
            paramValues={paramValues}
            validationErrors={validationErrors}
            onParamChange={onParamChange}
          />
        )}
      </div>
    </ScrollArea>
  ) : (
    <div className="py-8 text-center text-sm text-muted-foreground">
      This agent has no parameters
    </div>
  );
}

function RunButton({
  canRun,
  isPending,
  onClick,
}: {
  canRun: boolean;
  isPending: boolean;
  onClick: () => void;
}) {
  return (
    <Button onClick={onClick} disabled={!canRun || isPending}>
      <Play className="mr-2 h-4 w-4" />
      {isPending ? 'Executing...' : 'Run Agent'}
    </Button>
  );
}

interface InputSectionProps {
  title: string;
  params: AgentInput[];
  paramValues: Record<string, string>;
  validationErrors: Record<string, string>;
  onParamChange: (paramName: string, value: string) => void;
}

function InputSection({
  title,
  params,
  paramValues,
  validationErrors,
  onParamChange,
}: InputSectionProps) {
  return (
    <div className="space-y-3">
      <h4 className="text-sm font-semibold text-foreground">{title}</h4>
      <div className="space-y-4">
        {params.map((param) => (
          <div key={param.paramName} className="space-y-2">
            <div className="flex items-center gap-2">
              <Label htmlFor={param.paramName}>{param.paramName}</Label>
              <Badge variant="secondary" className="text-xs">
                {param.type}
              </Badge>
              {param.required && (
                <Badge variant="destructive" className="text-xs">
                  required
                </Badge>
              )}
            </div>
            <Input
              id={param.paramName}
              value={paramValues[param.paramName] || ''}
              onChange={(e) => onParamChange(param.paramName, e.target.value)}
              placeholder={`Enter ${param.paramName}`}
              className={validationErrors[param.paramName] ? 'border-destructive' : ''}
            />
            {validationErrors[param.paramName] && (
              <p className="text-sm text-destructive">
                {validationErrors[param.paramName]}
              </p>
            )}
            <ParamHints param={param} />
          </div>
        ))}
      </div>
    </div>
  );
}

// A parameter's env name and transformer, plus its default and allowed values unless it is secret.
function ParamHints({ param }: { param: AgentInput }) {
  return (
    <div className="space-y-1">
      <p className="text-xs text-muted-foreground">Env: {param.envName}</p>
      <p className="text-xs text-muted-foreground">
        {getTransformerDescription(param.transformer)}
      </p>
      {param.default != null && !param.secret && (
        <p className="text-xs text-muted-foreground">
          Default: {param.default}
        </p>
      )}
      {param.values && param.values.length > 0 && !param.secret && (
        <p className="text-xs text-muted-foreground">
          Allowed: {param.values.join(', ')}
        </p>
      )}
    </div>
  );
}
