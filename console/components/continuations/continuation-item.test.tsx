import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ContinuationItem } from './continuation-item';
import { HomerApiError } from '@/lib/api/client';
import type { Continuation } from '@/types/homer';

const useCancelContinuationMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useCancelContinuation: useCancelContinuationMock,
}));

function continuation(overrides: Partial<Continuation> = {}): Continuation {
  return {
    id: 5,
    watchedProcessId: 12,
    status: 'PENDING',
    agentName: 'follow-up',
    resumeParam: 'RESULT',
    params: {},
    originProcessId: 7,
    originAgentName: 'starter',
    expiresAt: null,
    createdAt: 1_700_000_000,
    firedAt: null,
    firedProcessId: null,
    error: null,
    ...overrides,
  };
}

interface MutationState {
  mutateAsync: ReturnType<typeof vi.fn>;
  reset: ReturnType<typeof vi.fn>;
  isPending: boolean;
  isError: boolean;
  error: unknown;
}

function setCancelMutation(overrides: Partial<MutationState> = {}): MutationState {
  const state: MutationState = {
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    reset: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
    ...overrides,
  };
  useCancelContinuationMock.mockReturnValue(state);
  return state;
}

describe('ContinuationItem', () => {
  beforeEach(() => {
    useCancelContinuationMock.mockReset();
    setCancelMutation();
  });

  it('shows the target agent and a Cancel button for a pending continuation', () => {
    render(<ContinuationItem continuation={continuation()} />);
    expect(screen.getByText('follow-up')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel continuation' })).toBeInTheDocument();
  });

  it('shows the fired-run link for a dispatched continuation and no Cancel button', () => {
    render(
      <ContinuationItem
        continuation={continuation({ status: 'DISPATCHED', firedProcessId: 99, firedAt: 1_700_000_100 })}
      />
    );
    const link = screen.getByRole('link', { name: '#99' });
    expect(link).toHaveAttribute('href', '/processes/99');
    expect(screen.queryByRole('button', { name: 'Cancel continuation' })).toBeNull();
  });

  it('shows the error for a failed continuation', () => {
    render(
      <ContinuationItem continuation={continuation({ status: 'FAILED', error: 'boom' })} />
    );
    expect(screen.getByText(/Continuation failed: boom/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel continuation' })).toBeNull();
  });

  it('cancels the continuation by id after confirming the dialog', async () => {
    const cancel = setCancelMutation();
    render(<ContinuationItem continuation={continuation()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel continuation' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, cancel' }));

    expect(cancel.mutateAsync).toHaveBeenCalledWith(5);
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Yes, cancel' })).not.toBeInTheDocument()
    );
  });

  it('surfaces a 409 from cancel inline', () => {
    setCancelMutation({
      isError: true,
      error: new HomerApiError('conflict', 409, 'Conflict'),
    });
    render(<ContinuationItem continuation={continuation()} />);
    expect(screen.getByText(/can no longer be cancelled/i)).toBeInTheDocument();
  });

  it.each([
    [new HomerApiError('gone', 404, 'Not Found'), /can no longer be cancelled/i],
    [new HomerApiError('backend down', 500, 'Server Error'), /^backend down$/],
    [new Error('offline'), /^offline$/],
    ['not an error', /^Failed to cancel the continuation\.$/],
  ])('surfaces cancel failure %# inline', (error, message) => {
    setCancelMutation({ isError: true, error });
    render(<ContinuationItem continuation={continuation()} />);
    expect(screen.getByRole('alert')).toHaveTextContent(message);
  });

  it('keeps waiting without cancelling when the dialog is dismissed', async () => {
    const cancel = setCancelMutation();
    render(<ContinuationItem continuation={continuation()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Cancel continuation' }));
    await userEvent.click(screen.getByRole('button', { name: 'Keep waiting' }));

    expect(cancel.mutateAsync).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Keep waiting' })).not.toBeInTheDocument()
    );
  });
});
