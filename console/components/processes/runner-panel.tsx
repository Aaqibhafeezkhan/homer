'use client';

import { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Check, Copy } from 'lucide-react';
import { cn } from '@/lib/utils/cn';
import { formatTimestamp } from '@/lib/utils/format';
import type { PodPhase, PodStartupFailure, RunnerState } from '@/types/homer';

interface RunnerPanelProps {
  runner: RunnerState;
}

const phaseStyles: Record<PodPhase, { label: string; className: string }> = {
  NONE: { label: 'None', className: 'bg-gray-400 text-white' },
  CREATING: { label: 'Creating', className: 'bg-blue-400 text-white animate-pulse' },
  PENDING: { label: 'Pending', className: 'bg-blue-500 text-white animate-pulse' },
  RUNNING: { label: 'Running', className: 'bg-blue-600 text-white' },
  READY: { label: 'Ready', className: 'bg-green-500 text-white' },
  SUCCEEDED: { label: 'Succeeded', className: 'bg-green-600 text-white' },
  FAILED: { label: 'Failed', className: 'bg-red-500 text-white' },
  DELETED: { label: 'Deleted', className: 'bg-gray-500 text-white' },
  KEPT_FOR_DEBUG: { label: 'Kept for debug', className: 'bg-orange-500 text-white' },
};

// The pod's created / ready / ended times; nothing when none is known yet.
function RunnerTimestamps({ runner }: RunnerPanelProps) {
  if (!(runner.createdAt || runner.readyAt || runner.endedAt)) return null;
  return (
    <div className="space-y-1 border-t pt-3">
      {runner.createdAt && (
        <div>
          <span className="font-medium">Created:</span>{' '}
          <span className="font-mono text-muted-foreground">
            {formatTimestamp(runner.createdAt)}
          </span>
        </div>
      )}
      {runner.readyAt && (
        <div>
          <span className="font-medium">Ready:</span>{' '}
          <span className="font-mono text-muted-foreground">
            {formatTimestamp(runner.readyAt)}
          </span>
        </div>
      )}
      {runner.endedAt && (
        <div>
          <span className="font-medium">Ended:</span>{' '}
          <span className="font-mono text-muted-foreground">
            {formatTimestamp(runner.endedAt)}
          </span>
        </div>
      )}
    </div>
  );
}

function StartupFailureDetails({ failure }: { failure: PodStartupFailure }) {
  return (
    <details className="border-t pt-3">
      <summary className="cursor-pointer font-medium text-destructive">
        Startup failure: {failure.reason}
      </summary>
      <div className="mt-2 space-y-2">
        <p className="text-destructive">{failure.message}</p>
        {failure.events.length > 0 && (
          <ul className="list-disc space-y-1 pl-5 font-mono text-xs text-muted-foreground">
            {failure.events.map((event, index) => (
              <li key={index}>{event}</li>
            ))}
          </ul>
        )}
      </div>
    </details>
  );
}

// A non-Kubernetes run has no pod, so its phase is the placeholder NONE —
// render the backend alone rather than a meaningless "None" badge.
function runnerHasPod(runner: RunnerState): boolean {
  return runner.podName != null || runner.podPhase !== 'NONE';
}

// The raw alias is admin-only (CWE-200, fail closed); a non-admin viewer gets
// the generic backend type instead, under a matching label. See ADR-0077.
function backendOf(runner: RunnerState): { backendLabel: string; backendValue: string } {
  const alias = runner.alias ?? null;
  return {
    backendLabel: alias != null ? 'Alias' : 'Backend',
    backendValue: alias ?? runner.type,
  };
}

// The pod name with a button that copies it to the clipboard.
function PodNameRow({ podName }: { podName: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopyPodName = async () => {
    try {
      await navigator.clipboard.writeText(podName);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard may be unavailable in insecure contexts
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="font-medium">Pod:</span>
      <span className="font-mono text-muted-foreground break-all">
        {podName}
      </span>
      <Button
        variant="ghost"
        size="sm"
        className="h-6 w-6 p-0"
        aria-label="Copy pod name"
        onClick={handleCopyPodName}
      >
        {copied ? (
          <Check className="h-3 w-3" aria-hidden="true" />
        ) : (
          <Copy className="h-3 w-3" aria-hidden="true" />
        )}
      </Button>
    </div>
  );
}

export function RunnerPanel({ runner }: RunnerPanelProps) {
  const phase = phaseStyles[runner.podPhase] ?? phaseStyles.NONE;
  const hasPod = runnerHasPod(runner);
  const { backendLabel, backendValue } = backendOf(runner);

  return (
    <Card>
      <CardHeader>
        <CardTitle>Runner</CardTitle>
        <CardDescription>
          Execution backend for this process
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{backendLabel}:</span>
          <span className="font-mono text-muted-foreground">{backendValue}</span>
          {hasPod && (
            <Badge
              variant="outline"
              className={cn('border-transparent', phase.className)}
              aria-label={`Pod phase: ${phase.label}`}
            >
              {phase.label}
            </Badge>
          )}
        </div>

        {runner.podName && <PodNameRow podName={runner.podName} />}

        {hasPod && <RunnerTimestamps runner={runner} />}

        {hasPod && runner.startupFailure && (
          <StartupFailureDetails failure={runner.startupFailure} />
        )}
      </CardContent>
    </Card>
  );
}
