'use client';

import { useQuery } from '@tanstack/react-query';
import { RotateCw, FileText } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { homerApi, type ArtifactEntry } from '@/lib/api/client';
import { queryKeys } from '@/lib/api/queries';
import { formatBytes, formatRelativeTime } from '@/lib/utils/format';

interface ArtifactPickerDialogProps {
  processId: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelect: (path: string) => void;
}

// The listed files as a table, or the empty state while the agent wrote none.
function ArtifactList({
  files,
  onPick,
}: {
  files: ArtifactEntry[];
  onPick: (path: string) => void;
}) {
  if (files.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground">
        <p>No artifacts yet.</p>
        <p className="text-xs mt-1">Files appear here once the agent writes them.</p>
      </div>
    );
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Path</TableHead>
          <TableHead className="w-24 text-right">Size</TableHead>
          <TableHead className="w-32 text-right">Modified</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {files.map((f) => (
          <TableRow
            key={f.path}
            className="cursor-pointer hover:bg-muted/50"
            onClick={() => onPick(f.path)}
          >
            <TableCell className="font-mono text-sm">
              <span className="inline-flex items-center gap-2">
                <FileText className="h-4 w-4 text-muted-foreground" />
                {f.path}
              </span>
            </TableCell>
            <TableCell className="text-right text-sm">{formatBytes(f.size)}</TableCell>
            <TableCell className="text-right text-sm text-muted-foreground">
              {formatRelativeTime(f.modified)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

// The dialog title and description beside the list's refresh button.
function PickerHeader({
  refetch,
  isRefetching,
}: {
  refetch: () => unknown;
  isRefetching: boolean;
}) {
  return (
    <DialogHeader>
      <div className="flex items-center justify-between">
        <div>
          <DialogTitle>Browse Artifacts</DialogTitle>
          <DialogDescription id="artifact-picker-desc">
            Click a file to download it.
          </DialogDescription>
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => refetch()}
          disabled={isRefetching}
          aria-label="Refresh list"
        >
          <RotateCw className={`h-4 w-4 ${isRefetching ? 'animate-spin' : ''}`} />
        </Button>
      </div>
    </DialogHeader>
  );
}

export function ArtifactPickerDialog({
  processId,
  open,
  onOpenChange,
  onSelect,
}: ArtifactPickerDialogProps) {
  const { data, isLoading, isError, refetch, isRefetching } = useQuery({
    queryKey: queryKeys.artifacts(processId),
    queryFn: () => homerApi.listArtifacts(processId),
    enabled: open,
  });

  const files: ArtifactEntry[] = data?.files ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl" aria-describedby="artifact-picker-desc">
        <PickerHeader refetch={refetch} isRefetching={isRefetching} />

        <div className="max-h-[60vh] overflow-y-auto">
          {isLoading && (
            <div className="py-8 text-center text-sm text-muted-foreground">
              Loading…
            </div>
          )}

          {isError && (
            <div className="py-8 text-center">
              <p className="text-sm text-destructive mb-2">Failed to load artifacts.</p>
              <Button variant="outline" size="sm" onClick={() => refetch()}>
                Retry
              </Button>
            </div>
          )}

          {!isLoading && !isError && (
            <ArtifactList
              files={files}
              onPick={(path) => {
                onSelect(path);
                onOpenChange(false);
              }}
            />
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
