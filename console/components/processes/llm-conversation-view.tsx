'use client';

import { memo, useRef, useState, type ReactNode } from 'react';
import { ChevronRight, ChevronDown, Loader2 } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { useNow } from '@/lib/hooks/use-now';
import { useAutoScroll } from '@/lib/hooks/use-auto-scroll';
import { stepCostLabel } from '@/lib/utils/cost-source';
import type {
  LlmAccounting,
  LlmEvent,
  LlmSessionStart,
  LlmTurnEnd,
  LlmTurnStatus,
  LlmToolCall,
  LlmToolResult,
  LlmUsage,
} from '@/types/homer';

// ── Helpers ──────────────────────────────────────────────────────────────────

function formatDuration(ms: number): string {
  return `${(ms / 1000).toFixed(1)}s`;
}

function renderToolContent(content: unknown): string {
  if (typeof content === 'string') return content;
  return JSON.stringify(content, null, 2);
}

// ── SummaryCard ───────────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: LlmTurnStatus }) {
  if (status.type === 'success') {
    return <Badge variant="secondary" className="text-xs">success</Badge>;
  }
  if (status.type === 'stopped') {
    // A stop the agent may have opted into (e.g. Claude's error_max_turns) —
    // not a failure, so it gets a neutral badge rather than destructive red.
    return (
      <>
        <Badge variant="outline" className="text-xs">stopped</Badge>
        <span className="text-muted-foreground">{status.reason}</span>
      </>
    );
  }
  return (
    <>
      <Badge variant="destructive" className="text-xs">{status.type}</Badge>
      <span className="text-muted-foreground">{status.reason}</span>
    </>
  );
}

interface SummaryCardProps {
  system: LlmSessionStart | undefined;
  result: LlmTurnEnd | undefined;
  accounting: LlmAccounting | null;
}

// Token counts of a finished turn; the cache and reasoning counts only when non-zero.
function UsageTokens({ usage }: { usage: LlmUsage }) {
  return (
    <span>
      <span className="text-muted-foreground">in </span>
      <span className="font-medium">{usage.input.toLocaleString()}</span>
      <span className="text-muted-foreground"> / out </span>
      <span className="font-medium">{usage.output.toLocaleString()}</span>
      {usage.cacheRead > 0 && (
        <>
          <span className="text-muted-foreground"> / cache↑ </span>
          <span className="font-medium">{usage.cacheRead.toLocaleString()}</span>
        </>
      )}
      {usage.cacheWrite > 0 && (
        <>
          <span className="text-muted-foreground"> / cache+ </span>
          <span className="font-medium">{usage.cacheWrite.toLocaleString()}</span>
        </>
      )}
      {usage.reasoning > 0 && (
        <>
          <span className="text-muted-foreground"> / reasoning </span>
          <span className="font-medium">{usage.reasoning.toLocaleString()}</span>
        </>
      )}
      <span className="text-muted-foreground"> tokens</span>
    </span>
  );
}

function SummaryCard({ system, result, accounting }: SummaryCardProps) {
  // A turn_end's own model wins over the session_start model when both are known.
  const model = result?.model ?? system?.model;

  return (
    <div className="rounded border bg-muted/50 p-3 flex flex-wrap gap-x-6 gap-y-1 text-xs">
      {model && (
        <span className="text-muted-foreground font-mono">{model}</span>
      )}
      {result && (
        <>
          <StatusBadge status={result.status} />
          <span>
            <span className="text-muted-foreground">cost </span>
            <span className="font-medium">
              {stepCostLabel(accounting, result.costUsd)}
            </span>
          </span>
          <UsageTokens usage={result.usage} />
          {result.durationMs !== null && (
            <span>
              <span className="text-muted-foreground">duration </span>
              <span className="font-medium">{formatDuration(result.durationMs)}</span>
              {result.durationApiMs !== null && (
                <>
                  <span className="text-muted-foreground"> (api </span>
                  <span className="font-medium">{formatDuration(result.durationApiMs)}</span>
                  <span className="text-muted-foreground">)</span>
                </>
              )}
            </span>
          )}
          {result.numTurns !== null && (
            <span>
              <span className="text-muted-foreground">turns </span>
              <span className="font-medium">{result.numTurns}</span>
            </span>
          )}
        </>
      )}
    </div>
  );
}

// ── CollapsibleBlock ──────────────────────────────────────────────────────────

interface CollapsibleBlockProps {
  /** Always shown in the toggle row, after the chevron. */
  summary: ReactNode;
  /** One-line excerpt shown after the summary while the block is collapsed. */
  preview: string;
  /** Classes for the expanded body, which already carries its top border. */
  bodyClassName: string;
  /** The expanded body; mounted only while the block is open. */
  children: ReactNode;
}

/** A conversation entry that starts collapsed to one row and expands on click. */
function CollapsibleBlock({ summary, preview, bodyClassName, children }: CollapsibleBlockProps) {
  const [open, setOpen] = useState(false);
  const Icon = open ? ChevronDown : ChevronRight;

  return (
    <div className="rounded border border-border bg-background text-xs font-mono">
      <button
        onClick={() => setOpen(o => !o)}
        className="flex w-full items-center gap-1.5 px-3 py-2 text-left hover:bg-muted/50"
      >
        <Icon className="h-3 w-3 shrink-0 text-muted-foreground" />
        {summary}
        {!open && (
          <span className="ml-1 truncate text-muted-foreground">{preview}</span>
        )}
      </button>
      {open && <div className={`border-t border-border ${bodyClassName}`}>{children}</div>}
    </div>
  );
}

// ── ToolCallBlock ─────────────────────────────────────────────────────────────

interface ToolCallBlockProps {
  call: LlmToolCall;
  result: LlmToolResult | undefined;
  inflight?: boolean;
}

// Memoized, like AssistantMessageBlock: a new event re-renders the list, but the
// stream republishes the same event objects, so earlier blocks keep their props.
const ToolCallBlock = memo(function ToolCallBlock({
  call,
  result,
  inflight = false,
}: ToolCallBlockProps) {
  return (
    <CollapsibleBlock
      summary={
        <>
          {/* eslint-disable-next-line no-restricted-syntax -- tool-name colour matches the JSON tree's labels, which have no token */}
          <span className="text-blue-600 dark:text-blue-400 font-semibold">{call.name}</span>
          {inflight && (
            <Loader2 data-testid="inflight-tool" className="h-3 w-3 animate-spin text-muted-foreground" aria-label="Tool running" />
          )}
        </>
      }
      preview={JSON.stringify(call.input).slice(0, 80)}
      bodyClassName="divide-y divide-border"
    >
      <ToolCallDetails call={call} result={result} />
    </CollapsibleBlock>
  );
});

/** The expanded tool call: pretty-printed input, then the result once it arrived. */
function ToolCallDetails({ call, result }: Omit<ToolCallBlockProps, 'inflight'>) {
  return (
    <>
      <div className="px-3 py-2">
        <div className="text-muted-foreground mb-1">Input</div>
        <pre className="whitespace-pre-wrap break-all text-foreground">
          {JSON.stringify(call.input, null, 2)}
        </pre>
      </div>
      {result !== undefined && (
        <div className="px-3 py-2">
          <div className="text-muted-foreground mb-1">Result</div>
          <pre
            className={`whitespace-pre-wrap break-all ${
              // eslint-disable-next-line no-restricted-syntax -- error/success status colours are fixed, not theme tokens
              result.isError ? 'text-red-600 dark:text-red-400' : 'text-emerald-700 dark:text-emerald-400'
            }`}
          >
            {renderToolContent(result.output)}
          </pre>
        </div>
      )}
    </>
  );
}

// ── AssistantMessageBlock ─────────────────────────────────────────────────────

const AssistantMessageBlock = memo(function AssistantMessageBlock({ text }: { text: string }) {
  return (
    <CollapsibleBlock
      summary={<span className="text-muted-foreground font-semibold">Assistant message</span>}
      preview={text.slice(0, 100)}
      bodyClassName="px-3 py-2"
    >
      <p className="whitespace-pre-wrap break-all text-foreground">{text}</p>
    </CollapsibleBlock>
  );
});

// ── ConversationList ──────────────────────────────────────────────────────────

interface ConversationListProps {
  events: LlmEvent[];
  live?: boolean;
}

type ConversationItem =
  | { kind: 'text'; text: string; key: string }
  | { kind: 'tool'; call: LlmToolCall; result: LlmToolResult | undefined };

// The rendered items in event order: non-blank assistant texts and tool calls
// paired with their results. A blank-text assistant_message (thinking-only)
// renders nothing here, but still counts toward the live turn count.
function conversationItems(events: LlmEvent[]): ConversationItem[] {
  // Build tool-result lookup map from every tool_result event.
  const toolResults = new Map<string, LlmToolResult>();
  for (const event of events) {
    if (event.type === 'tool_result') {
      toolResults.set(event.id, event);
    }
  }

  const collected: ConversationItem[] = [];
  let textIndex = 0;

  for (const event of events) {
    if (event.type === 'assistant_message') {
      if (event.text.trim()) {
        collected.push({ kind: 'text', text: event.text, key: `text-${textIndex++}` });
      }
    } else if (event.type === 'tool_call') {
      collected.push({ kind: 'tool', call: event, result: toolResults.get(event.id) });
    }
  }
  return collected;
}

// Index of the last text block — rendered directly, all others are collapsible.
function lastTextIndex(collected: ConversationItem[]): number {
  for (let i = collected.length - 1; i >= 0; i--) {
    if (collected[i].kind === 'text') return i;
  }
  return -1;
}

// The most recent tool_call with no matching tool_result is "in flight".
function inflightToolCallId(collected: ConversationItem[]): string | undefined {
  for (let i = collected.length - 1; i >= 0; i--) {
    const item = collected[i];
    if (item.kind === 'tool' && item.result === undefined) return item.call.id;
  }
  return undefined;
}

function ConversationList({ events, live = false }: ConversationListProps) {
  const collected = conversationItems(events);
  if (collected.length === 0) return null;

  const lastTextIdx = lastTextIndex(collected);
  const inflightToolId = inflightToolCallId(collected);

  return (
    <div className="flex flex-col gap-2">
      {collected.map((item, i) => {
        if (item.kind === 'tool') {
          return (
            <ToolCallBlock
              key={item.call.id}
              call={item.call}
              result={item.result}
              inflight={live && item.call.id === inflightToolId}
            />
          );
        }
        if (i === lastTextIdx) {
          return (
            <div key={item.key} className="flex flex-col gap-0.5">
              <span className="text-xs font-medium text-muted-foreground">Assistant</span>
              <p className="text-sm whitespace-pre-wrap text-foreground leading-relaxed">
                {item.text}
              </p>
            </div>
          );
        }
        return <AssistantMessageBlock key={item.key} text={item.text} />;
      })}
    </div>
  );
}

// ── LiveHeader ────────────────────────────────────────────────────────────────

interface LiveHeaderProps {
  system: LlmSessionStart | undefined;
  turns: number;
  commandStart: number | null | undefined;
}

function LiveHeader({ system, turns, commandStart }: LiveHeaderProps) {
  const now = useNow(commandStart);

  const elapsed = commandStart && now ? Math.max(0, now - commandStart) : null;

  return (
    <div className="rounded border bg-muted/50 p-3 flex flex-wrap items-center gap-x-6 gap-y-1 text-xs">
      <Badge variant="secondary" className="text-xs">Live</Badge>
      {system?.model && <span className="text-muted-foreground font-mono">{system.model}</span>}
      {turns > 0 && <span className="font-medium">turn {turns}</span>}
      {elapsed !== null && (
        <span data-testid="elapsed-timer">
          <span className="text-muted-foreground">elapsed </span>
          <span className="font-medium">{Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, '0')}</span>
        </span>
      )}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────

interface LlmConversationViewProps {
  events: LlmEvent[];
  live?: boolean;
  commandStart?: number | null;
  /** True once the event buffer has dropped earlier events past its cap (CWE-400). */
  truncated?: boolean;
  /** The step's resolved cost accounting, once the step has finished. */
  accounting?: LlmAccounting | null;
}

// Turns are counted by distinct message id across assistant_message and
// tool_call events, so a tool-only message (or a blank thinking-only one)
// still counts once.
function countTurns(events: LlmEvent[]): number {
  const turnIds = new Set<string>();
  for (const event of events) {
    if (event.type === 'assistant_message' && event.messageId) turnIds.add(event.messageId);
    else if (event.type === 'tool_call' && event.messageId) turnIds.add(event.messageId);
  }
  return turnIds.size;
}

export function LlmConversationView({
  events,
  live = false,
  commandStart,
  truncated = false,
  accounting = null,
}: LlmConversationViewProps) {
  // The first session_start whose model is known — Claude emits model-less
  // session_start events for hook lines before init.
  const system = events.find(
    (e): e is LlmSessionStart => e.type === 'session_start' && e.model !== null
  );
  const result = events.find((e): e is LlmTurnEnd => e.type === 'turn_end');

  const turns = countTurns(events);

  const showLiveHeader = live && result === undefined;
  const bottomRef = useRef<HTMLDivElement>(null);
  useAutoScroll(bottomRef, live, [events.length]);

  return (
    <div className="flex flex-col gap-3">
      {truncated && (
        <div className="text-xs text-muted-foreground italic">Earlier events not shown</div>
      )}
      {showLiveHeader ? (
        <LiveHeader system={system} turns={turns} commandStart={commandStart} />
      ) : (
        <SummaryCard system={system} result={result} accounting={accounting} />
      )}
      <ConversationList events={events} live={live} />
      <div ref={bottomRef} />
    </div>
  );
}
