import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Play, Clock, Pencil } from 'lucide-react';
import Link from 'next/link';
import type { AgentInfo, AgentInput } from '@/types/homer';
import { getAgentInputs, getTransformerDescription } from '@/lib/utils/agent';
import { formatTimestamp } from '@/lib/utils/format';
import { useCan } from '@/lib/auth/use-can';

interface AgentCardProps {
  agent: AgentInfo;
  onRunClick: (agent: AgentInfo) => void;
}

// The schedule's expression and its next and last run, the last linked to its process when known.
function CronSummary({ cron }: { cron: NonNullable<AgentInfo['cron']> }) {
  return (
    <div className="space-y-1 rounded-md bg-muted/50 p-2 text-xs">
      <p className="font-mono text-muted-foreground">{cron.expression}</p>
      {cron.nextRunAt && (
        <p className="text-muted-foreground">
          Next: {formatTimestamp(cron.nextRunAt)}
        </p>
      )}
      {cron.lastRunAt && (
        <p className="text-muted-foreground">
          Last:{' '}
          {cron.lastRunProcessId ? (
            <Link
              href={`/processes/${cron.lastRunProcessId}`}
              className="underline hover:text-foreground"
            >
              {formatTimestamp(cron.lastRunAt)}
            </Link>
          ) : (
            formatTimestamp(cron.lastRunAt)
          )}
        </p>
      )}
    </div>
  );
}

// Edit (when the agent is writable and the caller may edit it) and run buttons.
function AgentActions({ agent, onRunClick }: AgentCardProps) {
  const { can } = useCan();
  return (
    <div className="flex items-center gap-1">
      {agent.writable && can('edit', agent.name) && (
        <Button
          asChild
          variant="ghost"
          size="sm"
          className="h-8 w-8 p-0"
        >
          <Link
            href={`/agents/${encodeURIComponent(agent.name)}/edit`}
            aria-label={`Edit agent ${agent.name}`}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </Link>
        </Button>
      )}
      <Button
        variant="ghost"
        size="sm"
        onClick={() => onRunClick(agent)}
        className="h-8 w-8 p-0"
        aria-label={`Run agent ${agent.name}`}
      >
        <Play className="h-4 w-4" aria-hidden="true" />
      </Button>
    </div>
  );
}

// The agent's linked name, its schedule badge and its description.
function AgentTitle({ agent }: { agent: AgentInfo }) {
  return (
    <div>
      <div className="flex items-center gap-2">
        <CardTitle className="text-lg">
          <Link href={`/agents/${encodeURIComponent(agent.name)}`} className="hover:underline">
            {agent.name}
          </Link>
        </CardTitle>
        {agent.cron && (
          <Badge variant="secondary" className="text-xs gap-1">
            <Clock className="h-3 w-3" aria-hidden="true" />
            Scheduled
          </Badge>
        )}
      </div>
      {agent.description && (
        <p className="text-sm text-muted-foreground mt-1">{agent.description}</p>
      )}
    </div>
  );
}

function ParameterRow({ param }: { param: AgentInput }) {
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2">
        <span className="font-mono text-sm">{param.paramName}</span>
        <Badge variant="secondary" className="text-xs">
          {param.type}
        </Badge>
        {param.required && (
          <Badge variant="destructive" className="text-xs">
            required
          </Badge>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        Env: {param.envName}
      </p>
      <p className="text-xs text-muted-foreground">
        {getTransformerDescription(param.transformer)}
      </p>
    </div>
  );
}

function ParameterList({ inputs }: { inputs: AgentInput[] }) {
  if (inputs.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No parameters required
      </p>
    );
  }
  return (
    <div className="space-y-3">
      <h4 className="text-sm font-medium">Parameters</h4>
      {inputs.map((param) => (
        <ParameterRow key={param.paramName} param={param} />
      ))}
    </div>
  );
}

export function AgentCard({ agent, onRunClick }: AgentCardProps) {
  return (
    <Card className="hover:shadow-md transition-shadow">
      <CardHeader>
        <div className="flex items-center justify-between">
          <AgentTitle agent={agent} />
          <AgentActions agent={agent} onRunClick={onRunClick} />
        </div>
      </CardHeader>
      <CardContent>
        <div className="space-y-4">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <span>Scripts: {agent.scriptCount}</span>
          </div>

          {agent.cron && <CronSummary cron={agent.cron} />}

          <ParameterList inputs={getAgentInputs(agent)} />
        </div>
      </CardContent>
    </Card>
  );
}
