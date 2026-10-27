'use client';

import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useRunMessages, useSendRunMessage } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';
import { HomerApiError } from '@/lib/api/client';
import { formatTimestamp } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';
import type { ProcessInfo, RunMessage, RunMessageStatus } from '@/types/homer';

const statusStyles: Record<RunMessageStatus, { label: string; className: string }> = {
  QUEUED: { label: 'Queued', className: 'bg-amber-500 text-white' },
  DELIVERED: { label: 'Delivered', className: 'bg-green-500 text-white' },
  UNDELIVERED: { label: 'Undelivered', className: 'bg-gray-500 text-white' },
};

// The server's `msg` when it sent one (400/404/409/429), else the generic message.
function sendErrorText(err: unknown): string {
  if (err instanceof HomerApiError) {
    const msg = (err.body as { msg?: unknown } | undefined)?.msg;
    if (typeof msg === 'string' && msg) return msg;
  }
  return err instanceof Error ? err.message : 'Failed to send the message.';
}

function MessageItem({ message }: { message: RunMessage }) {
  const style = statusStyles[message.status];
  return (
    <div className="rounded-lg border p-3 space-y-2" data-testid={`run-message-${message.id}`}>
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge
          variant="outline"
          className={cn('border-transparent', style.className)}
          aria-label={`Message status: ${style.label}`}
        >
          {style.label}
        </Badge>
        {message.sender && <span>{message.sender}</span>}
        <span className="font-mono">{formatTimestamp(message.createdAt)}</span>
      </div>
      {/* Operator free text: rendered as plain text, never as markup. */}
      <p className="text-sm whitespace-pre-wrap break-words">{message.text}</p>
    </div>
  );
}

function SendForm({ processId }: { processId: number }) {
  const [text, setText] = useState('');
  const send = useSendRunMessage(processId);

  const submit = async () => {
    const trimmed = text.trim();
    if (!trimmed) return;
    try {
      await send.mutateAsync(trimmed);
      setText('');
    } catch {
      // surfaced inline via send.isError; the typed text stays
    }
  };

  return (
    <form
      className="space-y-2"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Type a message for the run…"
        aria-label="Message to the run"
        rows={3}
        disabled={send.isPending}
        className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
      />
      <div className="flex justify-end">
        <Button type="submit" size="sm" disabled={send.isPending || !text.trim()}>
          Send
        </Button>
      </div>
      {send.isError && (
        <p className="text-sm text-destructive" role="alert">
          {sendErrorText(send.error)}
        </p>
      )}
    </form>
  );
}

export function RunMessagePanel({ process }: { process: Pick<ProcessInfo, 'id' | 'status' | 'agentName' | 'owner'> }) {
  const processId = process.id;
  const accepting = process.status === 'CREATED' || process.status === 'WORKING';
  // Sending needs the backend's canControl rule, the same one the Kill button uses.
  const cap = useCan();
  const canSend = accepting && cap.canKillProcess(process);
  const { data } = useRunMessages(processId, accepting);
  const messages = data?.messages ?? [];

  if (messages.length === 0 && !canSend) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Messages</CardTitle>
        <CardDescription>Messages you send reach the run at its next check</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {messages.map((m) => (
          <MessageItem key={m.id} message={m} />
        ))}
        {canSend && <SendForm processId={processId} />}
      </CardContent>
    </Card>
  );
}
