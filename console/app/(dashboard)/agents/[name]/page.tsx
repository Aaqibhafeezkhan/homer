'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useAgent, useProcesses } from '@/lib/api/queries';
import { useUrlQueryState } from '@/lib/hooks/use-url-query-state';
import { ProcessTable } from '@/components/processes/process-table';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { BackButton } from '@/components/layout/back-button';
import { Play, Clock, Pencil } from 'lucide-react';
import { RunAgentDialog } from '@/components/agents/run-agent-dialog';
import { WorkflowGraphCard } from '@/components/agents/workflow-graph-card';
import { appConfig } from '@/lib/config/app-config';
import { getAgentInputs, getTransformerDescription } from '@/lib/utils/agent';
import { useCan } from '@/lib/auth/use-can';
import type { AgentInfo, AgentInput, ProcessListResponse } from '@/types/homer';

// The agent's runs, one page at a time, with Previous / Next writing `offset`.
function RunHistory({
  processes,
  loading,
  offset,
  limit,
  setParams,
}: {
  processes: ProcessListResponse | undefined;
  loading: boolean;
  offset: number;
  limit: number;
  setParams: ReturnType<typeof useUrlQueryState>['setParams'];
}) {
  return (
    <>
      {loading && (
        <p className="text-sm text-muted-foreground">Loading...</p>
      )}
      {processes && processes.processes.length === 0 && (
        <p className="text-sm text-muted-foreground">No runs yet.</p>
      )}
      {processes && processes.processes.length > 0 && (
        <>
          <div className="rounded-md border">
            <ProcessTable processes={processes.processes} />
          </div>
          <div className="flex items-center justify-between mt-3">
            <p className="text-sm text-muted-foreground">
              Showing {offset + 1} to {Math.min(offset + limit, processes.total)} of {processes.total}
            </p>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  const prevOffset = Math.max(0, offset - limit);
                  setParams({ offset: prevOffset > 0 ? String(prevOffset) : null });
                }}
                disabled={offset === 0}
              >
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setParams({ offset: String(offset + limit) })}
                disabled={offset + limit >= processes.total}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </>
  );
}

// The `offset` query value; 0 when it is missing, malformed or negative.
function pageOffset(raw: string | null): number {
  const parsedOffset = parseInt(raw ?? '', 10);
  return Number.isNaN(parsedOffset) || parsedOffset < 0 ? 0 : parsedOffset;
}

// The page of the agent's runs the URL's `offset` selects, newest first.
function useRunHistoryPage(agentName: string) {
  const { searchParams, setParams } = useUrlQueryState();
  const offset = pageOffset(searchParams.get('offset'));
  const limit = appConfig.pagination.defaultLimit;

  const { data: processes, isLoading: loading } = useProcesses({
    agentName,
    limit,
    offset,
    order: 'desc',
  });
  return { processes, loading, offset, limit, setParams };
}

function AgentNotFound({ agentName }: { agentName: string }) {
  return (
    <div className="p-6 space-y-3">
      <h2 className="text-2xl font-bold">Agent not found</h2>
      <p className="text-muted-foreground">No agent named <code>{agentName}</code>.</p>
      <Link href="/agents" className="underline">Back to agents</Link>
    </div>
  );
}

// Name, schedule badge and description, with the Edit and Run actions.
function AgentHeader({
  agent,
  canEdit,
  onRun,
}: {
  agent: AgentInfo;
  canEdit: boolean;
  onRun: () => void;
}) {
  return (
    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <div>
        <div className="flex items-center gap-2 flex-wrap">
          <h2 className="text-3xl font-bold tracking-tight">{agent.name}</h2>
          {agent.cron && (
            <Badge variant="secondary" className="gap-1">
              <Clock className="h-3 w-3" aria-hidden="true" />
              Scheduled
            </Badge>
          )}
        </div>
        {agent.description && (
          <p className="text-muted-foreground mt-1">{agent.description}</p>
        )}
      </div>
      <div className="flex items-center gap-2">
        {canEdit && (
          <Button asChild variant="outline">
            <Link
              href={`/agents/${encodeURIComponent(agent.name)}/edit`}
              aria-label={`Edit agent ${agent.name}`}
            >
              <Pencil className="mr-2 h-4 w-4" aria-hidden="true" />
              Edit
            </Link>
          </Button>
        )}
        <Button
          onClick={onRun}
          aria-label={`Run agent ${agent.name}`}
        >
          <Play className="mr-2 h-4 w-4" aria-hidden="true" />
          Run
        </Button>
      </div>
    </div>
  );
}

function ParametersCard({ inputs }: { inputs: AgentInput[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Parameters</CardTitle>
        <CardDescription>Inputs accepted when running this agent</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {inputs.map((param) => (
          <div key={param.paramName} className="space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-mono text-sm">{param.paramName}</span>
              <Badge variant="secondary" className="text-xs">{param.type}</Badge>
              {param.required && (
                <Badge variant="destructive" className="text-xs">required</Badge>
              )}
            </div>
            <p className="text-xs text-muted-foreground">Env: {param.envName}</p>
            <p className="text-xs text-muted-foreground">
              {getTransformerDescription(param.transformer)}
            </p>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

export default function AgentDetailPage({
  params,
}: {
  params: Promise<{ name: string }>;
}) {
  const { name } = use(params);
  const agentName = decodeURIComponent(name);
  const { can } = useCan();
  const { data: agent, isLoading: agentLoading } = useAgent(agentName);
  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const history = useRunHistoryPage(agentName);

  if (agentLoading) {
    return (
      <div className="p-6 text-muted-foreground">Loading agent...</div>
    );
  }

  if (!agent) {
    return <AgentNotFound agentName={agentName} />;
  }

  const inputs = getAgentInputs(agent);

  return (
    <div className="p-6 space-y-6">
      <BackButton fallbackHref="/agents" label="Agents" />
      <AgentHeader
        agent={agent}
        canEdit={Boolean(agent.writable) && can('edit', agent.name)}
        onRun={() => setRunDialogOpen(true)}
      />

      {inputs.length > 0 && <ParametersCard inputs={inputs} />}

      <WorkflowGraphCard agentName={agentName} />

      <Card>
        <CardHeader>
          <CardTitle>Run history</CardTitle>
          <CardDescription>Processes created by this agent</CardDescription>
        </CardHeader>
        <CardContent>
          <RunHistory {...history} />
        </CardContent>
      </Card>

      <RunAgentDialog
        agent={agent}
        open={runDialogOpen}
        onOpenChange={setRunDialogOpen}
      />
    </div>
  );
}
