'use client';

import { Button } from '@/components/ui/button';
import { FileDown, RotateCcw, StepForward } from 'lucide-react';
import type { ProcessInfo } from '@/types/homer';
import { useCan } from '@/lib/auth/use-can';
import { isActiveStatus, isRetryableStatus } from '@/lib/utils/process';

interface ProcessActionsProps {
  process: ProcessInfo;
  onDownloadArtifactClick: () => void;
  onRetryClick: () => void;
  onKillClick: () => void;
  retryPending: boolean;
  canResume: boolean;
  onResumeClick: () => void;
  resumePending: boolean;
}

export function ProcessActions({
  process,
  onDownloadArtifactClick,
  onRetryClick,
  onKillClick,
  retryPending,
  canResume,
  onResumeClick,
  resumePending,
}: ProcessActionsProps) {
  const cap = useCan();
  const canKill = isActiveStatus(process.status) && cap.canKillProcess(process);
  const canRetry = isRetryableStatus(process.status) && cap.canRetryProcess(process);
  const resolvedCanResume = canResume && cap.canRetryProcess(process);

  return (
    <>
      <Button
        variant="outline"
        size="default"
        onClick={onDownloadArtifactClick}
        aria-label={`Download artifact for process ${process.id}`}
      >
        <FileDown className="mr-2 h-4 w-4" aria-hidden="true" />
        Download Artifact
      </Button>
      {canRetry && (
        <Button
          variant="outline"
          onClick={onRetryClick}
          disabled={retryPending}
          aria-label={`Retry process ${process.id}`}
        >
          <RotateCcw className="mr-2 h-4 w-4" aria-hidden="true" />
          Retry
        </Button>
      )}
      {resolvedCanResume && (
        <Button
          variant="outline"
          onClick={onResumeClick}
          disabled={resumePending}
          aria-label={`Resume process ${process.id}`}
        >
          <StepForward className="mr-2 h-4 w-4" aria-hidden="true" />
          Resume
        </Button>
      )}
      {canKill && (
        <Button
          variant="destructive"
          onClick={onKillClick}
          aria-label={`Kill process ${process.id}`}
        >
          Kill Process
        </Button>
      )}
    </>
  );
}
