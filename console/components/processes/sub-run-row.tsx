import Link from 'next/link';
import { commandDotColor } from '@/components/processes/workflow-dots';
import type { LangGraphSubProgress } from '@/types/homer';

/**
 * One dispatched sub-agent run: agent name (linked, or plain "sub" text when
 * absent), `#processId` (omitted when purged), status, and its command-progress
 * dots. Shared by the collapsible subs list (with its `node:` prefix) and the
 * per-node runs popover (no prefix — the popover header already names the node).
 */
export function SubRunRow({ sub, node }: { sub: LangGraphSubProgress; node?: string }) {
  return (
    <p className="flex flex-wrap items-center gap-1">
      {node && <span className="font-medium">{node}:</span>}{' '}
      {sub.agentName ? (
        <Link
          href={`/agents/${encodeURIComponent(sub.agentName)}`}
          className="underline underline-offset-2 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {sub.agentName}
        </Link>
      ) : (
        <span>sub</span>
      )}{' '}
      {sub.processId != null && (
        <Link
          href={`/processes/${sub.processId}`}
          className="underline underline-offset-2 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={`Open process ${sub.processId} details`}
        >
          #{sub.processId}
        </Link>
      )}{' '}
      {/* A resolved node keeps its sub lines, and an old sub run may have been
          evicted from the process store by then — say so rather than trailing
          off after the dash. */}
      — {sub.status ?? 'purged'}
      {(sub.commands?.length ?? 0) > 0 && (
        <span className="ml-1 inline-flex items-center gap-0.5" aria-label="command progress">
          {sub.commands!.map((cmd, i) => (
            <span
              key={i}
              title={`${cmd.label}: ${cmd.state}`}
              className={`inline-block h-2 w-2 rounded-sm${cmd.state === 'running' ? ' animate-pulse' : ''}`}
              style={{ backgroundColor: commandDotColor(cmd.state) }}
            />
          ))}
        </span>
      )}
    </p>
  );
}
