'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Plus } from 'lucide-react';
import { useCreateAgent } from '@/lib/api/queries';
import { HomerApiError } from '@/lib/api/client';

interface NewAgentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const NAME_PATTERN = /^[a-zA-Z0-9_-]+$/;

export function validateAgentName(value: string): string | null {
  if (!value.trim()) return 'Name is required';
  if (!NAME_PATTERN.test(value)) {
    return 'Use letters, digits, dashes, or underscores only';
  }
  return null;
}

// The create failure as shown: a name clash, the backend's 400 reason, or the raw message.
function createErrorMessage(error: Error | null, name: string): string | undefined {
  return error instanceof HomerApiError
    ? error.status === 409
      ? `An agent named "${name}" already exists`
      : error.status === 400
        ? (error.body as { msg?: string })?.msg ||
          error.message
        : error.message
    : error?.message;
}

// The name field's state, reset whenever the dialog opens, and the create action.
function useNewAgentForm(open: boolean, onOpenChange: (open: boolean) => void) {
  const router = useRouter();
  const createAgent = useCreateAgent();
  const { reset: resetCreateAgent } = createAgent;
  const [name, setName] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional form reset when dialog opens
      setName('');
      setLocalError(null);
      resetCreateAgent();
    }
  }, [open, resetCreateAgent]);

  const changeName = (value: string) => {
    setName(value);
    if (localError) setLocalError(null);
  };

  const handleCreate = async () => {
    const err = validateAgentName(name);
    if (err) {
      setLocalError(err);
      return;
    }
    setLocalError(null);
    try {
      const result = await createAgent.mutateAsync(name);
      onOpenChange(false);
      router.push(`/agents/${encodeURIComponent(result.name)}/edit`);
    } catch {
      // Error is surfaced by mutation.error below
    }
  };

  const serverError = createErrorMessage(createAgent.error, name);
  return { name, changeName, localError, serverError, handleCreate, isPending: createAgent.isPending };
}

export function NewAgentDialog({ open, onOpenChange }: NewAgentDialogProps) {
  const { name, changeName, localError, serverError, handleCreate, isPending } = useNewAgentForm(open, onOpenChange);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby="new-agent-desc">
        <DialogHeader>
          <DialogTitle>Create Agent</DialogTitle>
          <DialogDescription id="new-agent-desc">
            Creates a new agent directory with a minimal agent.yaml. You&apos;ll be
            redirected to the editor.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="agent-name">Name</Label>
            <Input
              id="agent-name"
              value={name}
              onChange={(e) => changeName(e.target.value)}
              placeholder="my-agent"
              className={localError ? 'border-destructive' : ''}
            />
            {localError && (
              <p className="text-sm text-destructive">{localError}</p>
            )}
            {serverError && !localError && (
              <p className="text-sm text-destructive">{serverError}</p>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={handleCreate}
            disabled={isPending || !name.trim()}
          >
            <Plus className="mr-2 h-4 w-4" />
            {isPending ? 'Creating...' : 'Create'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
