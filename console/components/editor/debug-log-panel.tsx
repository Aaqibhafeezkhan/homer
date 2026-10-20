'use client';

import { useEffect, useRef } from 'react';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { X, ExternalLink } from 'lucide-react';
import { useProcessLogStream } from '@/lib/api/streams';

interface DebugLogPanelProps {
  processId: number;
  commandIndex: number;
  onClose: () => void;
}

function StreamPane({
  title,
  processId,
  commandIndex,
  stream,
}: {
  title: string;
  processId: number;
  commandIndex: number;
  stream: 'out' | 'err';
}) {
  const { content, ended } = useProcessLogStream(
    processId,
    commandIndex,
    stream,
    true,
  );
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [content]);

  return (
    <div className="flex flex-col min-h-0 flex-1">
      <div className="flex items-center justify-between px-2 py-1 border-b border-border bg-muted/30">
        <span className="text-xs font-medium uppercase tracking-wider">
          {title}
        </span>
        {ended && (
          <span className="text-xs text-muted-foreground">ended</span>
        )}
      </div>
      <div
        ref={ref}
        className="flex-1 overflow-y-auto bg-black/40 p-2 font-mono text-xs whitespace-pre-wrap"
      >
        {content.length === 0 ? (
          <span className="text-muted-foreground">
            {ended ? '(empty)' : 'Waiting for output...'}
          </span>
        ) : (
          content
        )}
      </div>
    </div>
  );
}

export function DebugLogPanel({
  processId,
  commandIndex,
  onClose,
}: DebugLogPanelProps) {
  return (
    <div className="flex flex-col min-h-0 flex-1 border-t border-border">
      <div className="flex items-center justify-between px-2 py-1 border-b border-border">
        <span className="text-xs">
          Process{' '}
          <Link
            href={`/processes/${processId}`}
            className="underline font-mono"
          >
            #{processId}
          </Link>
        </span>
        <div className="flex items-center gap-1">
          <Button
            asChild
            size="sm"
            variant="ghost"
            className="h-6 w-6 p-0"
          >
            <Link
              href={`/processes/${processId}`}
              aria-label="Open process detail"
            >
              <ExternalLink className="h-3.5 w-3.5" />
            </Link>
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-6 w-6 p-0"
            onClick={onClose}
            aria-label="Close log panel"
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>
      <StreamPane
        title="stdout"
        processId={processId}
        commandIndex={commandIndex}
        stream="out"
      />
      <StreamPane
        title="stderr"
        processId={processId}
        commandIndex={commandIndex}
        stream="err"
      />
    </div>
  );
}
