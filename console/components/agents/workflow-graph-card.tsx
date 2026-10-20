'use client';

import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useAgentLangGraph } from '@/lib/api/queries';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { WorkflowGraph } from './workflow-graph';

export function WorkflowGraphCard({ agentName }: { agentName: string }) {
  const { data } = useAgentLangGraph(agentName);
  const [open, setOpen] = useState(false);
  if (!data || data.length === 0) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-xl">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            className="flex w-full items-center gap-2 text-left"
          >
            {open ? (
              <ChevronDown className="h-4 w-4 shrink-0" aria-hidden="true" />
            ) : (
              <ChevronRight className="h-4 w-4 shrink-0" aria-hidden="true" />
            )}
            Workflow graph
          </button>
        </CardTitle>
        <CardDescription>LangGraph command structure</CardDescription>
      </CardHeader>
      {open && (
        <CardContent className="space-y-6">
          {data.map((graph) => (
            <div key={graph.label} className="space-y-2">
              {data.length > 1 && (
                <p className="font-mono text-sm">{graph.label}</p>
              )}
              {graph.topology ? (
                <WorkflowGraph topology={graph.topology} />
              ) : (
                <p className="text-sm text-destructive">
                  {graph.error ? <>Couldn&apos;t render graph: {graph.error}</> : 'Graph structure unavailable.'}
                </p>
              )}
            </div>
          ))}
        </CardContent>
      )}
    </Card>
  );
}
