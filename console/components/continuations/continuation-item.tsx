'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useCancelContinuation } from '@/lib/api/queries';
import { HomerApiError } from '@/lib/api/client';
import { formatTimestamp } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';
import type { Continuation, ContinuationStatus } from '@/types/homer';

const statusStyles: Record<ContinuationStatus, { label: string; className: string }> = {
  PENDING: { label: 'Waiting for watched run', className: 'bg-amber-500 text-white' },
  DISPATCHING: { label: 'Dispatching', className: 'bg-blue-500 text-white' },
  DISPATCHED: { label: 'Dispatched', className: 'bg-green-500 text-white' },
  FAILED: { label: 'Failed', className: 'bg-red-500 text-white' },
  CANCELLED: { label: 'Cancelled', className: 'bg-gray-500 text-white' },
  EXPIRED: { label: 'Expired', className: 'bg-gray-500 text-white' },
};

// The run a dispatched continuation started, with when it fired if known; nothing before that.
function FiredRunNote({ continuation }: { continuation: Continuation }) {
  const { firedProcessId, firedAt } = continuation;
  if (continuation.status !== 'DISPATCHED' || firedProcessId == null) return null;
  return (
    <p className="text-sm text-muted-foreground">
      Started run{' '}
      <Link className="underline" href={`/processes/${firedProcessId}`}>
        #{firedProcessId}
      </Link>
      {firedAt != null && (
        <span className="ml-2 font-mono text-xs">{formatTimestamp(firedAt)}</span>
      )}
      .
    </p>
  );
}

// The cancel failure as shown: a continuation that already fired or was cancelled, or the raw message.
function cancelErrorMessage(err: Error | null): string {
  if (err instanceof HomerApiError && (err.status === 409 || err.status === 404)) {
    return 'This continuation can no longer be cancelled — it already fired or was cancelled.';
  }
  return err instanceof Error ? err.message : 'Failed to cancel the continuation.';
}

// The confirm dialog's open state and the cancel action, whose failure is shown inline.
function useCancelFlow(continuationId: Continuation['id']) {
  const cancelContinuation = useCancelContinuation();
  const [dialogOpen, setDialogOpen] = useState(false);

  const confirmCancel = async () => {
    try {
      await cancelContinuation.mutateAsync(continuationId);
    } catch {
      // surfaced inline via errorMessage below
    } finally {
      setDialogOpen(false);
    }
  };

  const openDialog = () => {
    cancelContinuation.reset();
    setDialogOpen(true);
  };

  const errorMessage = cancelContinuation.isError ? cancelErrorMessage(cancelContinuation.error) : null;
  return { dialogOpen, setDialogOpen, openDialog, confirmCancel, errorMessage, isPending: cancelContinuation.isPending };
}

// What the continuation does: the watched run, the agent it starts, and where it came from.
function ContinuationSummary({ continuation }: { continuation: Continuation }) {
  return (
    <>
      <p className="text-sm">
        When watched run{' '}
        <Link className="underline" href={`/processes/${continuation.watchedProcessId}`}>
          #{continuation.watchedProcessId}
        </Link>{' '}
        finishes, start{' '}
        <span className="font-medium text-foreground">{continuation.agentName}</span>.
      </p>

      <p className="text-xs text-muted-foreground">
        Origin: <span className="font-medium text-foreground">{continuation.originAgentName}</span>{' '}
        (
        <Link className="underline" href={`/processes/${continuation.originProcessId}`}>
          #{continuation.originProcessId}
        </Link>
        )
      </p>
    </>
  );
}

// A pending continuation's cancel button and the inline cancel failure.
function CancelControls({ cancel }: { cancel: ReturnType<typeof useCancelFlow> }) {
  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="text-destructive"
          disabled={cancel.isPending}
          onClick={cancel.openDialog}
        >
          Cancel continuation
        </Button>
      </div>
      {cancel.errorMessage && (
        <p className="text-sm text-destructive" role="alert">
          {cancel.errorMessage}
        </p>
      )}
    </div>
  );
}

function CancelDialog({ continuation, cancel }: { continuation: Continuation; cancel: ReturnType<typeof useCancelFlow> }) {
  return (
    <Dialog open={cancel.dialogOpen} onOpenChange={cancel.setDialogOpen}>
      <DialogContent aria-describedby={`cancel-continuation-desc-${continuation.id}`}>
        <DialogHeader>
          <DialogTitle>Cancel this continuation?</DialogTitle>
          <DialogDescription id={`cancel-continuation-desc-${continuation.id}`}>
            This stops{' '}
            <span className="font-medium text-foreground">{continuation.agentName}</span>{' '}
            from starting when run #{continuation.watchedProcessId} finishes. This cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => cancel.setDialogOpen(false)}
            disabled={cancel.isPending}
          >
            Keep waiting
          </Button>
          <Button
            variant="destructive"
            onClick={cancel.confirmCancel}
            disabled={cancel.isPending}
          >
            Yes, cancel
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function ContinuationItem({ continuation }: { continuation: Continuation }) {
  const cancel = useCancelFlow(continuation.id);

  const pending = continuation.status === 'PENDING';
  const settled = !pending;
  const style = statusStyles[continuation.status];

  return (
    <div
      className={cn('rounded-lg border p-4 space-y-3', settled && 'opacity-80')}
      data-testid={`continuation-${continuation.id}`}
    >
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge
          variant="outline"
          className={cn('border-transparent', style.className)}
          aria-label={`Continuation status: ${style.label}`}
        >
          {style.label}
        </Badge>
        <span className="font-mono">{formatTimestamp(continuation.createdAt)}</span>
      </div>

      <ContinuationSummary continuation={continuation} />

      <FiredRunNote continuation={continuation} />

      {continuation.status === 'FAILED' && (
        <p className="text-sm text-destructive">Continuation failed: {continuation.error}</p>
      )}

      {pending && continuation.expiresAt != null && (
        <p className="text-xs text-muted-foreground">
          Expires {formatTimestamp(continuation.expiresAt)}.
        </p>
      )}

      {pending && <CancelControls cancel={cancel} />}

      <CancelDialog continuation={continuation} cancel={cancel} />
    </div>
  );
}
