'use client';

import { AlertTriangle } from 'lucide-react';
import { Badge } from '@/components/ui/badge';

// The list form sits beside the FAILED status in a table row, so its text is cut to this
// many characters; the whole reason stays reachable as the badge's tooltip and for a
// screen reader.
const INLINE_MAX_LENGTH = 80;

function inlineText(reason: string): string {
  return reason.length <= INLINE_MAX_LENGTH
    ? reason
    : `${reason.slice(0, INLINE_MAX_LENGTH - 1)}…`;
}

export interface FailureReasonProps {
  reason: string;
  // `detail` is the prominent box on the run's own page; `inline` is the badge the
  // process list shows next to the status.
  variant: 'detail' | 'inline';
}

/**
 * Why a run ended FAILED before any of its commands started — a claim timeout, a
 * `SKIP_CURRENT` lock skip, a parallel-run cap or a full queue.
 *
 * The reason is built by the server from typed fields, and is still rendered as plain
 * text: it is data about the run, never markup.
 */
export function FailureReason({ reason, variant }: FailureReasonProps) {
  if (variant === 'inline') {
    return (
      <Badge
        variant="outline"
        className="gap-1 border-transparent bg-red-100 font-normal text-red-800"
        title={reason}
      >
        <AlertTriangle className="h-3 w-3 shrink-0" aria-hidden="true" />
        <span aria-hidden="true">{inlineText(reason)}</span>
        <span className="sr-only">Failure reason: {reason}</span>
      </Badge>
    );
  }

  return (
    <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-900">
      <p className="font-medium">Failed before it started</p>
      <p className="mt-1 break-words">{reason}</p>
    </div>
  );
}
